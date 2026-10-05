package cn.sutone.ai.domain.agent.service.memory.governance;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.GovernanceDecision;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.service.memory.MemoryExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 * 记忆治理决策<b>计算</b>服务（只读）。
 *
 * <p>从 {@code MemoryGovernanceJob} 拆出的计算层：只做查询与判定，产出
 * {@link GovernanceDecision} 列表，<b>绝不调用 updateStatus / recordUndo / insert / delete</b>。
 * 落库由 {@link MemoryGovernanceApplyService} 负责。</p>
 *
 * <p>拆分动机：评测平台的治理 replay（{@code /eval/governance/replay}）需要在不改变语料的前提下
 * 重放治理判定，验证「治理规则是否会误伤 / 误合并 / 误归档」。</p>
 */
@Slf4j
@Service
public class MemoryGovernanceComputeService {

    /** 单组（同 user_id + type）全量聚类的上限；超过则改「最近 N 天新增」增量聚类，规避 O(N²) */
    private static final int GROUP_FULL_SCAN_MAX = 5000;

    /** 增量聚类：仅纳入最近 N 天新增的记忆 */
    private static final int INCREMENTAL_RECENT_DAYS = 7;

    /** 判重复簇的余弦相似度阈值 */
    private static final double DUPLICATE_SIM_THRESHOLD = 0.9;

    /** 重复粗筛时向 Qdrant 请求的候选数（top-K） */
    private static final int DUPLICATE_TOP_K = 20;

    /** 过期清理：距最后访问超过 N 天才软归档 */
    private static final int EXPIRED_INACTIVE_DAYS = 30;

    /** 幻觉抽检 confidence 灰色地带下界 / 上界 */
    private static final double HALLUCINATION_MIN_CONFIDENCE = 0.8;
    private static final double HALLUCINATION_MAX_CONFIDENCE = 0.9;

    /** 幻觉抽检单次抽样上限 */
    private static final int HALLUCINATION_SAMPLE_LIMIT = 100;

    /** 样本导出单桶条数上限（评测对账用，避免响应过大；同时返回真实总数） */
    private static final int SAMPLE_EXPORT_LIMIT = 500;

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
    private MemoryExtractor memoryExtractor;

    /**
     * 重复聚类决策：按 (user_id, type) 分组 → 组内向量相似粗筛 → 余弦 &gt; 0.9 判簇 →
     * 产出「非代表行 → MERGE_PENDING」决策，合并目标为代表行。
     */
    public List<GovernanceDecision> computeDuplicates() {
        // 生产治理任务扫全库：它本就要处理所有用户。与 samples() 的强制 userId 形成对照
        // ——那里是**对外导出内容**，这里是**内部计算决策**。
        return computeDuplicates(null);
    }

    /**
     * @param userId {@code null} = 扫全库（生产治理任务）；非 null = 只扫该用户（评测按命名空间作用域）。
     *
     *               <p><b>为什么需要按用户这一档</b>：平台侧调 {@code /eval/governance/replay}
     *               时期望的是「只算本次 run 那个命名空间的决策」。此前该端点接收
     *               {@code evalUserId} 却只用于越界校验，实际扫描仍是全库——于是别的命名空间
     *               （例如一次失败 run 留下的行）会混进本次的结果，被平台按「实际决策」计入，
     *               解析不到内容就退化成 {@code i:<id>} 而永远匹配不上期望，**虚高误伤率**
     *               （{@code false_action_rate} / {@code wrong_*_rate}）。</p>
     */
    public List<GovernanceDecision> computeDuplicates(Long userId) {
        List<MemoryRecordEntity> actives = memoryRepository.selectActiveForDuplicateScan(userId);
        if (actives == null || actives.isEmpty()) {
            return List.of();
        }
        Map<String, List<MemoryRecordEntity>> groups = actives.stream()
                .filter(e -> e.getUserId() != null && e.getType() != null)
                .collect(Collectors.groupingBy(e -> e.getUserId() + ":" + e.getType().getCode()));

        List<GovernanceDecision> decisions = new ArrayList<>();
        for (List<MemoryRecordEntity> group : groups.values()) {
            if (group.size() > GROUP_FULL_SCAN_MAX) {
                List<MemoryRecordEntity> recent = filterRecent(group, INCREMENTAL_RECENT_DAYS);
                if (recent.isEmpty()) {
                    continue;
                }
                decisions.addAll(clusterWithinGroup(recent));
            } else {
                decisions.addAll(clusterWithinGroup(group));
            }
        }
        return decisions;
    }

    /**
     * 事实一致性决策：按 (user_id, subject, predicate) 聚合 ACTIVE 记忆，
     * 同 predicate 异 value 的互斥对 → 保留最早创建行，其余产出 DISPUTED 决策。
     *
     * <p><b>⚠️ 本方法在当前写入路径的不变量下结构性不可达，恒定返回空列表。</b>
     * 不是「碰巧没触发」，是「不可能触发」。证据是两条，恰好互为反面：</p>
     *
     * <ol>
     *   <li><b>数据库层</b>：唯一索引 {@code uk_user_sp_active} 建在生成列 {@code active_sp_uk} 上，
     *       该列的定义是「{@code status='ACTIVE' 且 subject/predicate 非空} 的行取
     *       {@code CONCAT_WS('|', user_id, subject, predicate)}」——
     *       也就是说<b>每个 (user, subject, predicate) 最多只允许一条 ACTIVE 行</b>。</li>
     *   <li><b>本方法层</b>：下面 filter 的非空条件与 groupingBy 拼出的键
     *       （{@code userId|subject|predicate}）<b>与上面那个生成列逐字段相同</b>。</li>
     * </ol>
     *
     * <p>两者合起来，分组大小恒 ≤ 1，于是 {@code if (group.size() < 2) continue;} <b>恒成立</b>，
     * 永远走不到产出决策的分支。</p>
     *
     * <p><b>为什么保留代码而不是删掉</b>：它实现的是「允许冲突事实并存 + 事后巡检检出」这一设计，
     * 而当前系统选的是「写入期消解」（靠唯一索引压根不让第二条写进来）。
     * <b>这两种设计互斥，不能同时成立</b>，必须二选一。本方法正是前一种设计下该有的实现，
     * 删掉它等于把这个选项从代码里抹掉。若将来决定放宽不变量（允许并存），
     * 本方法无需改动即可生效；<b>但在那之前，它不该被任何人当作「一致性正在被巡检」。</b></p>
     *
     * <p><b>对应的守护测试</b>：
     * {@code cn.sutone.ai.test.integration.GovernanceConsistencyInvariantTest}
     * 直接对真实 MySQL 连写两条冲突 ACTIVE 行并断言被唯一索引拒绝。
     * <b>若哪天有人移除了那个索引，那条测试会变红</b>——它就是在提醒：设计变了，
     * 本方法从死代码变成活代码，需要同步复核调用方（定时任务 / 评测 replay）与评测集。</p>
     */
    public List<GovernanceDecision> computeConsistency() {
        // 生产扫全库，同 computeDuplicates 的理由。
        return computeConsistency(null);
    }

    /** @param userId {@code null} = 扫全库；非 null = 只扫该用户（评测按命名空间作用域）。 */
    public List<GovernanceDecision> computeConsistency(Long userId) {
        List<MemoryRecordEntity> actives = memoryRepository.selectActiveForConsistencyScan(userId);
        if (actives == null || actives.isEmpty()) {
            return List.of();
        }
        Map<String, List<MemoryRecordEntity>> groups = actives.stream()
                .filter(e -> e.getUserId() != null && e.getSubject() != null && e.getPredicate() != null)
                .collect(Collectors.groupingBy(e -> e.getUserId() + "|" + e.getSubject() + "|" + e.getPredicate()));

        List<GovernanceDecision> decisions = new ArrayList<>();
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
            List<GovernanceDecision.Item> items = new ArrayList<>();
            for (int i = 1; i < sorted.size(); i++) {
                MemoryRecordEntity e = sorted.get(i);
                items.add(new GovernanceDecision.Item(e.getId(),
                        MemoryStatus.ACTIVE.getCode(), MemoryStatus.DISPUTED.getCode()));
            }
            if (!items.isEmpty()) {
                decisions.add(new GovernanceDecision(ACTION_DISPUTE, null,
                        "同 (user,subject,predicate) 存在 " + distinctValues.size() + " 个异 value", items));
            }
        }
        return decisions;
    }

    /**
     * 过期清理决策：{@code expire_time < now} 且距最后访问超过 30 天（或从未访问）的 ACTIVE 记忆
     * → 产出 ARCHIVED 决策。
     */
    public List<GovernanceDecision> computeExpired() {
        return computeExpired(null);
    }

    /** @param userId {@code null} = 扫全库；非 null = 只扫该用户（评测按命名空间作用域）。 */
    public List<GovernanceDecision> computeExpired(Long userId) {
        LocalDateTime inactiveBefore = LocalDateTime.now().minusDays(EXPIRED_INACTIVE_DAYS);
        List<MemoryRecordEntity> expired = memoryRepository.selectExpiredForArchive(inactiveBefore, userId);
        if (expired == null || expired.isEmpty()) {
            return List.of();
        }
        List<GovernanceDecision.Item> items = expired.stream()
                .map(e -> new GovernanceDecision.Item(e.getId(),
                        MemoryStatus.ACTIVE.getCode(), MemoryStatus.ARCHIVED.getCode()))
                .toList();
        return List.of(new GovernanceDecision(ACTION_ARCHIVE, null,
                "已过期且 " + EXPIRED_INACTIVE_DAYS + " 天未访问", items));
    }

    /**
     * 幻觉抽检决策：抽样 confidence 灰色地带的记忆 → 回溯 evidence 校验 →
     * 不支撑者产出 QUARANTINED 决策。
     */
    public List<GovernanceDecision> computeHallucination() {
        return computeHallucination(null);
    }

    /** @param userId {@code null} = 扫全库；非 null = 只扫该用户（评测按命名空间作用域）。 */
    public List<GovernanceDecision> computeHallucination(Long userId) {
        List<MemoryRecordEntity> samples = memoryRepository.selectSampleForHallucinationCheck(
                HALLUCINATION_MIN_CONFIDENCE, HALLUCINATION_MAX_CONFIDENCE,
                HALLUCINATION_SAMPLE_LIMIT, userId);
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        List<GovernanceDecision.Item> items = new ArrayList<>();
        for (MemoryRecordEntity e : samples) {
            if (!memoryExtractor.verifyEvidence(e.getContent(), e.getEvidence())) {
                items.add(new GovernanceDecision.Item(e.getId(),
                        MemoryStatus.ACTIVE.getCode(), MemoryStatus.QUARANTINED.getCode()));
            }
        }
        if (items.isEmpty()) {
            return List.of();
        }
        return List.of(new GovernanceDecision(ACTION_QUARANTINE, null,
                "evidence 不支撑断言（疑似幻觉）", items));
    }

    /**
     * 导出四类治理任务的输入候选样本（只读，供评测平台对账）。
     *
     * <p><b>{@code userId} 必填，不接受 null。</b>这些桶里带记忆的 {@code content}，
     * 而调用方是评测端点（{@code /api/v1/eval/governance/samples}）——评测是按命名空间
     * 隔离的，不按用户过滤就等于把一个命名空间的记忆内容暴露给所有命名空间。</p>
     *
     * <p>刻意**不提供无参重载**：这个能力一旦存在，就会有人图省事调用它，
     * 而「忘了传 userId」在编译期没有任何提示、在运行期也不报错——正是本次修复的缺陷形态。
     * 想让编译器替你拦住这类错误，就不能留那条更省事的路。</p>
     */
    public GovernanceSamples samples(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "samples 必须指定 userId：治理样本包含记忆内容，不能跨命名空间导出");
        }
        List<MemoryRecordEntity> duplicateCandidates = memoryRepository.selectActiveForDuplicateScan(userId);
        List<MemoryRecordEntity> consistencyCandidates = memoryRepository.selectActiveForConsistencyScan(userId);
        LocalDateTime inactiveBefore = LocalDateTime.now().minusDays(EXPIRED_INACTIVE_DAYS);
        List<MemoryRecordEntity> expiredCandidates =
                memoryRepository.selectExpiredForArchive(inactiveBefore, userId);
        List<MemoryRecordEntity> hallucinationCandidates = memoryRepository.selectSampleForHallucinationCheck(
                HALLUCINATION_MIN_CONFIDENCE, HALLUCINATION_MAX_CONFIDENCE,
                HALLUCINATION_SAMPLE_LIMIT, userId);

        return new GovernanceSamples(
                toBucket(duplicateCandidates),
                toBucket(consistencyCandidates),
                toBucket(expiredCandidates),
                toBucket(hallucinationCandidates));
    }

    private GovernanceSampleBucket toBucket(List<MemoryRecordEntity> records) {
        if (records == null || records.isEmpty()) {
            return new GovernanceSampleBucket(0, List.of());
        }
        List<GovernanceSampleItem> items = records.stream()
                .limit(SAMPLE_EXPORT_LIMIT)
                .map(e -> new GovernanceSampleItem(
                        e.getId(),
                        e.getUserId(),
                        e.getType() != null ? e.getType().getCode() : null,
                        e.getContent(),
                        e.getStatus() != null ? e.getStatus().getCode() : null,
                        e.getSubject(),
                        e.getPredicate(),
                        e.getValue(),
                        e.getConfidence()))
                .toList();
        return new GovernanceSampleBucket(records.size(), items);
    }

    /** 单组内重复聚类：产出每个重复簇的 MERGE 决策 */
    private List<GovernanceDecision> clusterWithinGroup(List<MemoryRecordEntity> group) {
        if (group.size() < 2) {
            return List.of();
        }
        Map<Long, MemoryRecordEntity> byId = group.stream()
                .collect(Collectors.toMap(MemoryRecordEntity::getId, e -> e, (a, b) -> a));
        Set<Long> groupIds = byId.keySet();
        Set<Long> visited = new HashSet<>();
        List<GovernanceDecision> decisions = new ArrayList<>();

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
            if (representative == null) {
                continue;
            }
            List<GovernanceDecision.Item> items = new ArrayList<>();
            for (Long id : clusterIds) {
                if (id.equals(representative.getId())) {
                    continue;
                }
                MemoryRecordEntity e = byId.get(id);
                if (e == null || !MemoryStatus.ACTIVE.equals(e.getStatus())) {
                    continue;
                }
                items.add(new GovernanceDecision.Item(id,
                        MemoryStatus.ACTIVE.getCode(), MemoryStatus.MERGE_PENDING.getCode()));
            }
            visited.addAll(clusterIds);
            if (!items.isEmpty()) {
                decisions.add(new GovernanceDecision(ACTION_MERGE, representative.getId(),
                        "重复簇余弦 ≥ " + DUPLICATE_SIM_THRESHOLD + "，代表行 id=" + representative.getId(), items));
            }
        }
        return decisions;
    }

    /** 取最近 N 天新增的记忆（增量聚类用） */
    private List<MemoryRecordEntity> filterRecent(List<MemoryRecordEntity> group, int recentDays) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(recentDays);
        return group.stream()
                .filter(e -> e.getCreateTime() != null && e.getCreateTime().isAfter(cutoff))
                .toList();
    }

    /** 治理样本快照：四类任务的输入候选 */
    public record GovernanceSamples(
            GovernanceSampleBucket duplicates,
            GovernanceSampleBucket consistency,
            GovernanceSampleBucket expired,
            GovernanceSampleBucket hallucination
    ) {
    }

    /**
     * 单类样本桶。
     *
     * @param total 真实候选总数（可能大于 items.size()——导出有条数上限）
     * @param items 导出的样本明细（最多 {@value #SAMPLE_EXPORT_LIMIT} 条）
     */
    public record GovernanceSampleBucket(long total, List<GovernanceSampleItem> items) {
    }

    /** 单条治理样本（脱敏后的只读视图） */
    public record GovernanceSampleItem(
            Long memoryId,
            Long userId,
            String type,
            String content,
            String status,
            String subject,
            String predicate,
            String value,
            Double confidence
    ) {
    }
}
