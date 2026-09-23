package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.service.memory.MemoryPersistService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MemoryPersistService 单元测试")
@ExtendWith(MockitoExtension.class)
class MemoryPersistServiceTest {

    @Mock
    private IMemoryRepository memoryRepository;

    private MemoryPersistService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new MemoryPersistService();
        var f = MemoryPersistService.class.getDeclaredField("memoryRepository");
        f.setAccessible(true);
        f.set(service, memoryRepository);
    }

    @Test
    @DisplayName("版本化 UPDATE：旧版本 SUPERSEDED、新版本插入、旧向量待删、写 history")
    void shouldVersionedUpdate() {
        MemoryRecordEntity newRecord = MemoryRecordEntity.create(null, 1L, "fact", "新内容", "newhash", "s");
        newRecord.setVersion(2);
        when(memoryRepository.insertVersioned(newRecord)).thenReturn(11L);

        service.persistSurvivors(1L, "s", "trace",
                List.of(), List.of(new MemoryPersistService.UpdatePlan(10L, "旧内容", newRecord)), List.of());

        verify(memoryRepository).closeVersion(eq(10L), any(LocalDateTime.class));
        verify(memoryRepository).markVectorDeletePending(10L);
        verify(memoryRepository).insertVersioned(newRecord);
        verify(memoryRepository).markVectorPending(11L);
        verify(memoryRepository).insertHistory(10L, "旧内容", "新内容", "UPDATE", "s");
    }

    @Test
    @DisplayName("纯 DELETE：关闭旧版本，不插入新版本")
    void shouldDeleteOnly() {
        service.persistSurvivors(1L, "s", "trace",
                List.of(), List.of(new MemoryPersistService.UpdatePlan(10L, "旧内容", null)), List.of());

        verify(memoryRepository).closeVersion(eq(10L), any(LocalDateTime.class));
        verify(memoryRepository).markVectorDeletePending(10L);
        verify(memoryRepository, never()).insertVersioned(any(MemoryRecordEntity.class));
        verify(memoryRepository).insertHistory(10L, "旧内容", null, "DELETE", "s");
    }

    @Test
    @DisplayName("DISPUTED：插入多版本行（status=DISPUTED），不触碰旧 ACTIVE 行")
    void shouldInsertDisputed() {
        MemoryRecordEntity disputed = MemoryRecordEntity.create(null, 1L, "fact", "Python", "h", "s");
        disputed.setStatus(MemoryStatus.DISPUTED);
        when(memoryRepository.insert(disputed)).thenReturn(12L);

        service.persistSurvivors(1L, "s", "trace", List.of(), List.of(), List.of(disputed));

        verify(memoryRepository).insert(disputed);
        verify(memoryRepository).insertHistory(12L, null, "Python", "DISPUTED", "s");
        verify(memoryRepository, never()).closeVersion(anyLong(), any());
    }
}
