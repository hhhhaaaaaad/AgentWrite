package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆持久化事务服务（P1-4）。
 *
 * <p>独立 Spring bean，避免 {@code @Transactional} 自调用失效：{@link MemoryManager}
 * 通过注入本服务调用 {@link #persistSurvivors}，而非 {@code this.} 自调用，确保
 * 「版本化更新 + 历史 + 向量状态标记」在同一事务内原子提交（方案 B，见计划 P1-4）。</p>
 *
 * <p>写路径不再同步 upsert 向量：事务内只写 MySQL 并置 {@code vector_status='PENDING'}，
 * 由 {@link MemoryVectorSyncJob} 异步 embed + upsert（复用 Outbox 思想），
 * 消除「MySQL 成功 + Qdrant 失败 → 误标 SYNCED」的双写不一致。</p>
 */
@Slf4j
@Service
public class MemoryPersistService {

    @Resource
    private IMemoryRepository memoryRepository;

    /**
     * 事务内原子持久化幸存候选。
     *
     * <ul>
     *   <li>UPDATE：关闭旧版本（SUPERSEDED + valid_to=now）→ 插入新版本（ACTIVE，version=旧+1）→ 写 history</li>
     *   <li>DELETE：仅关闭旧版本（SUPERSEDED），不插入新版本，写 history</li>
     *   <li>ADD：直接插入 ACTIVE 行，写 history</li>
     *   <li>DISPUTED：插入 status=DISPUTED 的多版本行，不触碰旧 ACTIVE 行（保留两行）</li>
     * </ul>
     *
     * @param userId    用户 id（审计 / 日志用）
     * @param sessionId 会话 id（写 history）
     * @param traceId   本次抽取批次 trace_id（落库 trace_id 列）
     * @param toInsert  ADD 候选（已构建好实体，status=ACTIVE、version=1）
     * @param updates   UPDATE / DELETE 计划
     * @param toDispute DISPUTED 多版本行（已构建好实体，status=DISPUTED）
     */
    @Transactional
    public void persistSurvivors(Long userId, String sessionId, String traceId,
                                 List<MemoryRecordEntity> toInsert,
                                 List<UpdatePlan> updates,
                                 List<MemoryRecordEntity> toDispute) {
        for (UpdatePlan u : updates) {
            // 旧版本 SUPERSEDED + valid_to=now，保留原始行与向量供追溯
            memoryRepository.closeVersion(u.targetId(), LocalDateTime.now());
            // 旧向量待删（Qdrant 删除腾 top-K 召回槽位），由 MemoryVectorSyncJob 删除
            memoryRepository.markVectorDeletePending(u.targetId());
            if (u.newRecord() != null) {
                Long newId = memoryRepository.insertVersioned(u.newRecord());
                // insert SQL 已显式置 vector_status='PENDING'，此处再标一次确保状态（幂等）
                memoryRepository.markVectorPending(newId);
                memoryRepository.insertHistory(u.targetId(), u.oldContent(), u.newRecord().getContent(),
                        "UPDATE", sessionId);
                log.debug("记忆 UPDATE(版本化): oldId={}, newId={}, version={}",
                        u.targetId(), newId, u.newRecord().getVersion());
            } else {
                memoryRepository.insertHistory(u.targetId(), u.oldContent(), null, "DELETE", sessionId);
                log.debug("记忆 DELETE(关闭版本): id={}", u.targetId());
            }
        }
        for (MemoryRecordEntity record : toInsert) {
            Long id = memoryRepository.insert(record);
            memoryRepository.insertHistory(id, null, record.getContent(), "ADD", sessionId);
            log.debug("记忆 ADD: id={}", id);
        }
        for (MemoryRecordEntity disputed : toDispute) {
            Long id = memoryRepository.insert(disputed);
            memoryRepository.insertHistory(id, null, disputed.getContent(), "DISPUTED", sessionId);
            log.debug("记忆 DISPUTED(保留多版本): id={}, subject={}, predicate={}",
                    id, disputed.getSubject(), disputed.getPredicate());
        }
        log.info("记忆持久化完成: userId={}, traceId={}, insert={}, update={}, disputed={}",
                userId, traceId, toInsert.size(), updates.size(), toDispute.size());
    }

    /**
     * 版本化更新计划（P1-4）。
     *
     * @param targetId   旧版本 id（被 SUPERSEDED）
     * @param oldContent 旧版本内容（写 history）
     * @param newRecord  新版本实体（version=旧+1、status=ACTIVE、valid_from=now）；
     *                   {@code null} 表示纯 DELETE（仅关闭旧版本，不插入新版本）
     */
    public record UpdatePlan(Long targetId, String oldContent, MemoryRecordEntity newRecord) {
    }
}
