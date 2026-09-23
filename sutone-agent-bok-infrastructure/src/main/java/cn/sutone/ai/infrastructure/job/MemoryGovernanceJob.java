package cn.sutone.ai.infrastructure.job;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.domain.agent.service.memory.MemoryExtractor;
import cn.sutone.ai.domain.agent.service.memory.circuit.MemoryCircuitBreaker;
import cn.sutone.ai.infrastructure.adapter.repository.MemoryGovernanceUndoRepository;
import cn.sutone.ai.infrastructure.dao.po.MemoryGovernanceUndoItemPO;
import cn.sutone.ai.infrastructure.metrics.MemoryMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 记忆离线巡检治理任务（P3，对应计划 §2.5 / §2.4 错误分级决策表）。
 *
 * <p><b>可逆性契约</b>：所有治理操作一律「先软标记、后硬化」两段式——先把受影响行置为
 * {@code MERGE_PENDING / ARCHIVED / SUPERSEDED / DISPUTED / QUARANTINED}（保留原始行与向量），
 * 并写 {@code memory_governance_undo} + {@code memory_governance_undo_item} 撤销记录；
 * 人工 / 评测确认后才物理删除或真正合并。回滚只改 status，无需从 {@code memory_history} 逆推。</p>
 *
 * <p><b>治理任务 1-4 已实现真实逻辑</b>；任务 5「纠错熔断巡检」由收尾阶段接，当前留 TODO。</p>
 */
@Slf4j
@Component
public class MemoryGovernanceJob {

    /** 单组（同 user_id + type）全量聚类的上限；超过则跳过全量、改「最近 N 天新增」增量聚类，规避 O(N²) */
    private static final int GROUP_FULL_SCAN_MAX = 5000;

    /** 增量聚类：仅纳入最近 N 天新增的记忆 */
    private static final int INCREMENTAL_RECENT_DAYS = 7;

    /** 判重复簇的余弦相似度阈值 */
    private static final double DUPLICATE_SIM_THRESHOLD = 0.9;

    /** 重复粗筛时向 Qdrant 请求的候选数（top-K） */
    private static final int DUPLICATE_TOP_K = 20;

    /** 过期清理：距最后访问超过 N 天才软归档 */
    private static final int EXPIRED_INACTIVE_DAYS = 30;

    /** 幻觉抽检 confidence 灰色地带下界 */
    private static final double HALLUCINATION_MIN_CONFIDENCE = 0.8;

    /** 幻觉抽检 confidence 灰色地带上界 */
    private static final double HALLUCINATION_MAX_CONFIDENCE = 0.9;

    /** 幻觉抽检单次抽样上限 */
    private static final int HALLUCINATION_SAMPLE_LIMIT = 100;

    /** 熔断巡检：向量同步积压告警阈值（条），超过则触发一级降级 */
    private static final long VECTOR_SYNC_PENDING_THRESHOLD = 500;

    private static final String ACTION_MERGE = "MERGE";
    private static final String ACTION_ARCHIVE = "ARCHIVE";
    private static final String ACTION_DISPUTE = "DISPUTE";
    private static final String ACTION_QUARANTINE = "QUARANTINE";

    /** 代表行选择：创建时间最早，其次 id 最小（保证聚类结果稳定可复现） */
    private static final Comparator<MemoryRecordEntity> BY_CREATE_TIME_THEN_ID = Comparator
            .comparing((MemoryRecordEntity e) -> e.getCreateTime() != null ? e.getCreateTime() : LocalDateTime.MAX)
            .thenComparing(e -> e.getId() != null ? e.getId() : Long.MAX_VALUE);

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private IMemoryVectorStore vectorStore;

    @Resource
    private IMemoryEmbeddingClient embeddingClient;

    @Resource
    private MemoryGovernanceUndoRepository governanceUndoRepository;

    @Resource
    private MemoryCircuitBreaker circuitBreaker;

    @Resource
    private MemoryMetrics memoryMetrics;

    @Resource
    private MemoryProperties memoryProperties;

    @Resource
    private MemoryExtractor memoryExtractor;

    /**
     * 重复聚类（每日凌晨 3:30）。
     *
     * <p>按 {@code (user_id, type)} 分组（分组内做、避免全表 O(N²)）→ 组内向量相似粗筛
     * （对每条记忆 embed 后复用 Qdrant search 取 top-K 候选）→ 余弦 &gt; 0.9 判重复簇 →
     * 对簇内非代表行软标记 {@code status=MERGE_PENDING}（保留原始行与向量），并写 undo 记录
     * （action=MERGE，带 merged_into_id）。单组 &gt; 5000 条跳过全量、改「最近 N 天新增」增量聚类。</p>
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void clusterDuplicates() {
        long begin = System.currentTimeMillis();
        log.info("[governance:cluster-duplicates] 开始每日重复聚类巡检");
        try {
            List<MemoryRecordEntity> actives = memoryRepository.selectActiveForDuplicateScan();
            if (actives == null || actives.isEmpty()) {
                log.info("[governance:cluster-duplicates] 无 ACTIVE 记忆，跳过");
                return;
            }
            Map<String, List<MemoryRecordEntity>> groups = actives.stream()
                    .filter(e -> e.getUserId() != null && e.getType() != null)
                    .collect(Collectors.groupingBy(e -> e.getUserId() + ":" + e.getType().getCode()));

            int marked = 0;
            int skippedFullGroups = 0;
            for (List<MemoryRecordEntity> group : groups.values()) {
                if (group.size() > GROUP_FULL_SCAN_MAX) {
                    List<MemoryRecordEntity> recent = filterRecent(group, INCREMENTAL_RECENT_DAYS);
                    if (recent.isEmpty()) {
                        skippedFullGroups++;
                        continue;
                    }
                    marked += clusterWithinGroup(recent);
                } else {
                    marked += clusterWithinGroup(group);
                }
            }
            log.info("[governance:cluster-duplicates] 完成，软标记 MERGE_PENDING {} 条，跳过全量组 {} 个，耗时 {}ms",
                    marked, skippedFullGroups, System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.error("[governance:cluster-duplicates] 失败", e);
        }
    }

    /**
     * 事实一致性巡检（每周一 4:00）。
     *
     * <p>按 {@code (user_id, subject, predicate)} 聚合 {@code status=ACTIVE} 记忆 →
     * 同 predicate 异 value 的互斥对 → 保留最早创建行为 ACTIVE（不动旧 ACTIVE 行），
     * 其余软标记 {@code status=DISPUTED}（不进默认召回 / 注入，等待用户裁决），并写 undo 记录。</p>
     */
    @Scheduled(cron = "0 0 4 ? * MON")
    public void checkFactConsistency() {
        long begin = System.currentTimeMillis();
        log.info("[governance:fact-consistency] 开始每周事实一致性巡检");
        try {
            List<MemoryRecordEntity> actives = memoryRepository.selectActiveForConsistencyScan();
            if (actives == null || actives.isEmpty()) {
                log.info("[governance:fact-consistency] 无带 subject+predicate 的 ACTIVE 记忆，跳过");
                return;
            }
            Map<String, List<MemoryRecordEntity>> groups = actives.stream()
                    .filter(e -> e.getUserId() != null && e.getSubject() != null && e.getPredicate() != null)
                    .collect(Collectors.groupingBy(e -> e.getUserId() + "|" + e.getSubject() + "|" + e.getPredicate()));

            int marked = 0;
            for (List<MemoryRecordEntity> group : groups.values()) {
                if (group.size() < 2) {
                    continue;
                }
                Set<String> distinctValues = group.stream()
                        .map(MemoryRecordEntity::getValue)
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .collect(Collectors.toSet());
                if (distinctValues.size() < 2) {
                    continue;
                }
                List<MemoryRecordEntity> sorted = group.stream().sorted(BY_CREATE_TIME_THEN_ID).toList();
                List<MemoryGovernanceUndoItemPO> items = new ArrayList<>();
                for (int i = 1; i < sorted.size(); i++) {
                    MemoryRecordEntity e = sorted.get(i);
                    memoryRepository.updateStatus(e.getId(), MemoryStatus.DISPUTED);
                    items.add(buildItem(e, MemoryStatus.ACTIVE));
                    marked++;
                }
                if (!items.isEmpty()) {
                    governanceUndoRepository.recordUndo(ACTION_DISPUTE, items, null);
                }
            }
            log.info("[governance:fact-consistency] 完成，软标记 DISPUTED {} 条，耗时 {}ms",
                    marked, System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.error("[governance:fact-consistency] 失败", e);
        }
    }

    /**
     * 过期清理（每日 5:00）。
     *
     * <p>扫描 {@code expire_time < now} 且 {@code last_accessed_at} 早于 30 天前（或从未访问）的 ACTIVE 记忆 →
     * 软归档 {@code status=ARCHIVED}（保留原始行与向量，物理删除推迟到确认后），并写 undo 记录（action=ARCHIVE）。</p>
     */
    @Scheduled(cron = "0 0 5 * * *")
    public void cleanExpired() {
        long begin = System.currentTimeMillis();
        log.info("[governance:clean-expired] 开始每日过期清理");
        try {
            LocalDateTime inactiveBefore = LocalDateTime.now().minusDays(EXPIRED_INACTIVE_DAYS);
            List<MemoryRecordEntity> expired = memoryRepository.selectExpiredForArchive(inactiveBefore);
            if (expired == null || expired.isEmpty()) {
                log.info("[governance:clean-expired] 无过期记忆，跳过");
                return;
            }
            List<MemoryGovernanceUndoItemPO> items = new ArrayList<>();
            for (MemoryRecordEntity e : expired) {
                memoryRepository.updateStatus(e.getId(), MemoryStatus.ARCHIVED);
                items.add(buildItem(e, MemoryStatus.ACTIVE));
            }
            governanceUndoRepository.recordUndo(ACTION_ARCHIVE, items, null);
            log.info("[governance:clean-expired] 完成，软归档 ARCHIVED {} 条，耗时 {}ms",
                    expired.size(), System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.error("[governance:clean-expired] 失败", e);
        }
    }

    /**
     * 幻觉抽检（每周日 6:00）。
     *
     * <p>抽样 {@code confidence} 落在 0.8-0.9 区间（灰色地带）的记忆 → 回溯 {@code evidence} 校验是否被原文支撑 →
     * 不支撑者软隔离 {@code status=QUARANTINED} + 触发同类抽检 + 产出抽取规则迭代建议。</p>
     *
     * <p>抽样后调用 {@link MemoryExtractor#verifyEvidence(String, String)} 回溯 evidence 校验，
     * 判定为「不支撑」的样本交给 {@link #markQuarantined(List)} 软隔离并写 undo。</p>
     */
    @Scheduled(cron = "0 0 6 ? * SUN")
    public void spotCheckHallucination() {
        long begin = System.currentTimeMillis();
        log.info("[governance:hallucination-spot-check] 开始每周幻觉抽检");
        try {
            List<MemoryRecordEntity> samples = memoryRepository.selectSampleForHallucinationCheck(
                    HALLUCINATION_MIN_CONFIDENCE, HALLUCINATION_MAX_CONFIDENCE, HALLUCINATION_SAMPLE_LIMIT);
            if (samples == null || samples.isEmpty()) {
                log.info("[governance:hallucination-spot-check] 无 confidence 0.8-0.9 的样本，跳过");
                return;
            }
            // LLM 回溯 evidence 校验：不支撑者软隔离 QUARANTINED
            List<MemoryRecordEntity> unsupported = new ArrayList<>();
            for (MemoryRecordEntity e : samples) {
                if (!memoryExtractor.verifyEvidence(e.getContent(), e.getEvidence())) {
                    unsupported.add(e);
                }
            }
            if (!unsupported.isEmpty()) {
                markQuarantined(unsupported);
            }
            log.info("[governance:hallucination-spot-check] 抽样 {} 条，软隔离疑似幻觉 {} 条，耗时 {}ms",
                    samples.size(), unsupported.size(), System.currentTimeMillis() - begin);
        } catch (Exception e) {
            log.error("[governance:hallucination-spot-check] 失败", e);
        }
    }

    /**
     * 纠错熔断巡检（每分钟）。
     *
     * <p>TODO 后续逻辑：统计可测信号——Qdrant 写失败率（{@code memory.qdrant.failures[op=upsert]}）、
     * {@code memory.vector.sync.pending} 积压、抽取驳回率（{@code memory.extraction.reject_rate}）、
     * 检索 P99 延迟，任一超阈值则调用 {@link MemoryCircuitBreaker#open(String)} 触发一级降级（关闭注入）。</p>
     *
     * <p>注意：{@code memory.user.correction} 在纠错信号检测机制落地前恒 0，不参与熔断。</p>
     */
    @Scheduled(cron = "0 * * * * *")
    public void inspectCorrectionCircuit() {
        try {
            MemoryProperties.Alert alert = memoryProperties.getAlert();
            if (alert == null || !alert.isEnabled()) {
                return; // 首月不启用熔断（先埋点后调阈值）
            }
            // 信号 1：抽取驳回率持续过高 → 抽取规则异常/幻觉增多
            double rejectRate = memoryMetrics.getExtractionRejectRate();
            if (rejectRate > alert.getExtractionRejectRate()) {
                circuitBreaker.open("抽取驳回率 " + rejectRate + " 超过阈值 " + alert.getExtractionRejectRate());
                return;
            }
            // 信号 2：向量同步积压 → 补偿任务失效 / Qdrant 故障
            long pending = memoryMetrics.getVectorSyncPendingCount();
            if (pending > VECTOR_SYNC_PENDING_THRESHOLD) {
                circuitBreaker.open("向量同步积压 " + pending + " 超过阈值 " + VECTOR_SYNC_PENDING_THRESHOLD);
            }
        } catch (Exception e) {
            log.error("[governance:correction-circuit] 熔断巡检失败", e);
        }
    }

    /** 单组内重复聚类：embed + top-K 粗筛 → 余弦 > 阈值判簇 → 非代表行软标记 MERGE_PENDING + 写 undo */
    private int clusterWithinGroup(List<MemoryRecordEntity> group) {
        if (group.size() < 2) {
            return 0;
        }
        Map<Long, MemoryRecordEntity> byId = group.stream()
                .collect(Collectors.toMap(MemoryRecordEntity::getId, e -> e, (a, b) -> a));
        Set<Long> groupIds = byId.keySet();
        Set<Long> visited = new HashSet<>();
        int marked = 0;
        for (MemoryRecordEntity source : group) {
            if (source.getId() == null || visited.contains(source.getId())) {
                continue;
            }
            float[] queryVector = embeddingClient.embed(source.getContent());
            if (queryVector == null || queryVector.length == 0) {
                continue;
            }
            List<ScoredMemory> candidates = vectorStore.search(source.getUserId(), queryVector, DUPLICATE_TOP_K);
            Set<Long> clusterIds = new LinkedHashSet<>();
            clusterIds.add(source.getId());
            for (ScoredMemory sm : candidates) {
                if (sm.id() == null || sm.id().equals(source.getId())) {
                    continue;
                }
                if (!groupIds.contains(sm.id())) {
                    continue;
                }
                if (sm.score() < DUPLICATE_SIM_THRESHOLD) {
                    continue;
                }
                clusterIds.add(sm.id());
            }
            visited.add(source.getId());
            if (clusterIds.size() < 2) {
                continue;
            }
            MemoryRecordEntity representative = clusterIds.stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .min(BY_CREATE_TIME_THEN_ID)
                    .orElse(null);
            List<MemoryGovernanceUndoItemPO> items = new ArrayList<>();
            for (Long id : clusterIds) {
                if (id.equals(representative.getId())) {
                    continue;
                }
                MemoryRecordEntity e = byId.get(id);
                if (e == null || !MemoryStatus.ACTIVE.equals(e.getStatus())) {
                    continue;
                }
                memoryRepository.updateStatus(id, MemoryStatus.MERGE_PENDING);
                items.add(buildItem(e, MemoryStatus.ACTIVE));
                marked++;
            }
            visited.addAll(clusterIds);
            if (!items.isEmpty()) {
                governanceUndoRepository.recordUndo(ACTION_MERGE, items, representative.getId());
            }
        }
        return marked;
    }

    /** 软隔离幻觉抽检判定为「不支撑」的样本（QUARANTINED + 写 undo），由 LLM 校验接入后产出 ids 调用 */
    private void markQuarantined(List<MemoryRecordEntity> unsupported) {
        if (unsupported == null || unsupported.isEmpty()) {
            return;
        }
        List<MemoryGovernanceUndoItemPO> items = new ArrayList<>();
        for (MemoryRecordEntity e : unsupported) {
            memoryRepository.updateStatus(e.getId(), MemoryStatus.QUARANTINED);
            items.add(buildItem(e, MemoryStatus.ACTIVE));
        }
        governanceUndoRepository.recordUndo(ACTION_QUARANTINE, items, null);
    }

    /** 取最近 N 天新增的记忆（增量聚类用） */
    private List<MemoryRecordEntity> filterRecent(List<MemoryRecordEntity> group, int recentDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(recentDays);
        return group.stream()
                .filter(e -> e.getCreateTime() != null && e.getCreateTime().isAfter(cutoff))
                .toList();
    }

    /** 构造撤销明细：记忆 id + 动作前 status（回滚目标值） */
    private MemoryGovernanceUndoItemPO buildItem(MemoryRecordEntity e, MemoryStatus before) {
        return MemoryGovernanceUndoItemPO.builder()
                .memoryId(e.getId())
                .beforeStatus(before.getCode())
                .build();
    }
}
