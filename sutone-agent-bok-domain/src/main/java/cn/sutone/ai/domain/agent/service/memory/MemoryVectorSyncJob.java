package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆向量同步补偿任务（P1-4，升级自 P0-5 的 {@code MemoryManager.syncPendingVectors}）。
 *
 * <p>状态机：</p>
 * <pre>
 * PENDING --(claimVectorSync CAS)--> SYNCING --(upsert ok)--> SYNCED
 *                                          \--(fail)--> retry_count+1, next_retry_at=指数退避 --> 回 PENDING
 *                                          \--(retry_count>=maxRetry)--> FAILED
 * </pre>
 *
 * <p>同时负责「旧向量删除」：版本化 UPDATE 后，被 SUPERSEDED 的旧行由
 * {@link MemoryPersistService} 标记 {@code vector_status='DELETE_PENDING'}，本任务在
 * upsert 新版本后一并删除旧向量（Qdrant delete 幂等），腾出 top-K 召回槽位。</p>
 */
@Slf4j
@Component
public class MemoryVectorSyncJob {

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private IMemoryVectorStore vectorStore;

    @Resource
    private IMemoryEmbeddingClient embeddingClient;

    @Resource
    private IMemoryMetricsPort metrics;

    /** 最近一轮扫描到的 pending 积压数（供 vector.sync.pending Gauge 读取，避免每次 scrape 查库） */
    private volatile int pendingCount = 0;

    @Value("${memory.vector-sync.max-retry:5}")
    private int maxRetry;

    /** 指数退避基数（毫秒） */
    @Value("${memory.vector-sync.base-backoff-ms:60000}")
    private long baseBackoffMs;

    /** 注册 vector.sync.pending 积压 Gauge（读最近一轮扫描的 pendingCount，规避 scrape 时查库） */
    @PostConstruct
    void registerMetrics() {
        metrics.registerVectorSyncPendingGauge(() -> (long) pendingCount);
    }

    @Scheduled(fixedDelayString = "${memory.vector-sync.delay-ms:30000}")
    public void syncPendingVectors() {
        // 1) 同步 PENDING 向量（CAS 抢占 + 指数退避重试）
        List<MemoryRecordEntity> pending = memoryRepository.selectPendingVectors();
        this.pendingCount = pending.size();
        for (MemoryRecordEntity p : pending) {
            syncOne(p);
        }
        // 2) 删除旧版本向量（DELETE_PENDING -> DELETED）
        List<MemoryRecordEntity> deletePending = memoryRepository.selectVectorDeletePending();
        for (MemoryRecordEntity d : deletePending) {
            deleteOne(d);
        }
    }

    private void syncOne(MemoryRecordEntity record) {
        if (memoryRepository.claimVectorSync(record.getId()) == 0) {
            // 已被其他实例抢占 / 未到 next_retry_at 重试时间
            return;
        }
        try {
            float[] emb = embeddingClient.embed(record.getContent());
            if (emb.length == 0) {
                scheduleRetry(record, "embedding 返回空");
                return;
            }
            vectorStore.upsert(record.getId(), record.getUserId(), emb, record.getContent(), record.getContentHash());
            memoryRepository.markVectorSynced(record.getId());
            metrics.incrementVectorSyncSuccess();
            log.debug("向量同步成功: id={}", record.getId());
        } catch (Exception e) {
            scheduleRetry(record, e.getMessage());
            log.warn("向量同步失败 id={}: {}", record.getId(), e.getMessage());
        }
    }

    private void deleteOne(MemoryRecordEntity record) {
        try {
            vectorStore.delete(record.getId());
            memoryRepository.markVectorDeleted(record.getId());
            log.debug("旧向量删除成功: id={}", record.getId());
        } catch (Exception e) {
            // 保留 DELETE_PENDING，下轮重试（Qdrant delete 幂等）
            log.warn("旧向量删除失败 id={}: {}", record.getId(), e.getMessage());
        }
    }

    /** 失败重试：retry_count+1 + next_retry_at 指数退避，超限标 FAILED */
    private void scheduleRetry(MemoryRecordEntity record, String lastError) {
        int next = (record.getRetryCount() == null ? 0 : record.getRetryCount()) + 1;
        LocalDateTime nextRetryAt = LocalDateTime.now().plusNanos(
                baseBackoffMs * 1_000_000L * (1L << Math.min(next - 1, 20)));
        int retry = memoryRepository.scheduleVectorRetry(record.getId(), "PENDING", nextRetryAt, lastError);
        if (retry >= maxRetry) {
            memoryRepository.updateVectorStatus(record.getId(), "FAILED");
            metrics.incrementVectorSyncFailed();
            log.error("向量同步重试超限 id={}, retry={}, 标记 FAILED", record.getId(), retry);
        }
    }
}
