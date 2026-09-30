package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.service.memory.MemoryVectorSyncJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MemoryVectorSyncJob 单元测试")
@ExtendWith(MockitoExtension.class)
class MemoryVectorSyncJobTest {

    @Mock
    private IMemoryRepository memoryRepository;

    @Mock
    private IMemoryVectorStore vectorStore;

    @Mock
    private IMemoryEmbeddingClient embeddingClient;

    /** P3 可观测改造新增字段：不注入会导致 syncOne/deleteOne 里 NPE */
    @Mock
    private IMemoryMetricsPort metrics;

    private MemoryVectorSyncJob job;

    @BeforeEach
    void setUp() throws Exception {
        job = new MemoryVectorSyncJob();
        setField("memoryRepository", memoryRepository);
        setField("vectorStore", vectorStore);
        setField("embeddingClient", embeddingClient);
        setField("metrics", metrics);
        setField("maxRetry", 5);
        setField("baseBackoffMs", 1000L);
    }

    private void setField(String name, Object value) throws Exception {
        var f = MemoryVectorSyncJob.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(job, value);
    }

    private MemoryRecordEntity pending(long id) {
        return MemoryRecordEntity.create(id, 1L, "fact", "内容", "hash", "s");
    }

    @Test
    @DisplayName("CAS 抢占失败（返回 0）时跳过，不 upsert")
    void shouldSkipWhenClaimFails() {
        when(memoryRepository.selectPendingVectors()).thenReturn(List.of(pending(7L)));
        when(memoryRepository.claimVectorSync(7L)).thenReturn(0);

        job.syncPendingVectors();

        verify(vectorStore, never()).upsert(anyLong(), anyLong(), any(float[].class), anyString(), anyString());
        verify(memoryRepository, never()).markVectorSynced(anyLong());
    }

    @Test
    @DisplayName("抢占成功 + upsert 成功 → 标 SYNCED")
    void shouldMarkSyncedOnSuccess() {
        when(memoryRepository.selectPendingVectors()).thenReturn(List.of(pending(7L)));
        when(memoryRepository.claimVectorSync(7L)).thenReturn(1);
        when(embeddingClient.embed("内容")).thenReturn(new float[]{0.3f});

        job.syncPendingVectors();

        verify(memoryRepository).markVectorSynced(7L);
    }

    @Test
    @DisplayName("抢占成功 + upsert 失败 + retry<max → 回 PENDING，不标 FAILED")
    void shouldRetryWithoutFail() {
        when(memoryRepository.selectPendingVectors()).thenReturn(List.of(pending(7L)));
        when(memoryRepository.claimVectorSync(7L)).thenReturn(1);
        when(embeddingClient.embed("内容")).thenReturn(new float[]{0.3f});
        doThrow(new RuntimeException("qdrant down")).when(vectorStore)
                .upsert(anyLong(), anyLong(), any(float[].class), anyString(), anyString());
        when(memoryRepository.scheduleVectorRetry(eq(7L), eq("PENDING"), any(LocalDateTime.class), anyString()))
                .thenReturn(3);

        job.syncPendingVectors();

        verify(memoryRepository, never()).updateVectorStatus(7L, "FAILED");
    }

    @Test
    @DisplayName("抢占成功 + upsert 失败 + retry>=max → 标 FAILED")
    void shouldMarkFailedWhenRetryExceedsLimit() {
        when(memoryRepository.selectPendingVectors()).thenReturn(List.of(pending(7L)));
        when(memoryRepository.claimVectorSync(7L)).thenReturn(1);
        when(embeddingClient.embed("内容")).thenReturn(new float[]{0.3f});
        doThrow(new RuntimeException("qdrant down")).when(vectorStore)
                .upsert(anyLong(), anyLong(), any(float[].class), anyString(), anyString());
        when(memoryRepository.scheduleVectorRetry(eq(7L), eq("PENDING"), any(LocalDateTime.class), anyString()))
                .thenReturn(5);

        job.syncPendingVectors();

        verify(memoryRepository).updateVectorStatus(7L, "FAILED");
    }

    @Test
    @DisplayName("DELETE_PENDING 旧向量 → 删除并标记 DELETED")
    void shouldDeleteOldVector() {
        when(memoryRepository.selectPendingVectors()).thenReturn(List.of());
        when(memoryRepository.selectVectorDeletePending()).thenReturn(List.of(pending(20L)));

        job.syncPendingVectors();

        verify(vectorStore).delete(20L);
        verify(memoryRepository).markVectorDeleted(20L);
    }
}
