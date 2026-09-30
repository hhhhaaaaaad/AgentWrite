package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryGovernanceUndoPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.GovernanceDecision;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.service.memory.MemoryExtractor;
import cn.sutone.ai.domain.agent.service.memory.governance.MemoryGovernanceApplyService;
import cn.sutone.ai.domain.agent.service.memory.governance.MemoryGovernanceComputeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 治理 compute / apply 拆分测试（AW-6）。
 *
 * <p><b>核心不变量</b>：compute 层只读——评测 replay 走 compute，绝不能改动 memory_record；
 * 落库只发生在 apply 层。</p>
 */
@DisplayName("治理 compute/apply 拆分测试")
class MemoryGovernanceSplitTest {

    private static MemoryRecordEntity record(Long id, Long userId, MemoryTypeVO type, String content,
                                             LocalDateTime createTime) {
        return MemoryRecordEntity.builder()
                .id(id)
                .userId(userId)
                .type(type)
                .content(content)
                .status(MemoryStatus.ACTIVE)
                .createTime(createTime)
                .build();
    }

    @Nested
    @DisplayName("compute 层（只读）")
    @ExtendWith(MockitoExtension.class)
    class Compute {

        @Mock
        private IMemoryRepository memoryRepository;
        @Mock
        private IMemoryVectorStore vectorStore;
        @Mock
        private IMemoryEmbeddingClient embeddingClient;
        @Mock
        private MemoryExtractor memoryExtractor;

        private MemoryGovernanceComputeService computeService;

        @BeforeEach
        void setUp() throws Exception {
            computeService = new MemoryGovernanceComputeService();
            setField("memoryRepository", memoryRepository);
            setField("vectorStore", vectorStore);
            setField("embeddingClient", embeddingClient);
            setField("memoryExtractor", memoryExtractor);
        }

        private void setField(String name, Object value) throws Exception {
            var f = MemoryGovernanceComputeService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(computeService, value);
        }

        /** 断言 compute 过程未对 memory_record 做任何写操作 */
        private void assertNoPersistence() {
            verify(memoryRepository, never()).updateStatus(anyLong(), any(MemoryStatus.class));
            verify(memoryRepository, never()).insert(any(MemoryRecordEntity.class));
            verify(memoryRepository, never()).deleteById(anyLong());
        }

        @Test
        @DisplayName("过期清理：只产出 ARCHIVE 决策，不落库")
        void expiredProducesDecisionWithoutPersisting() {
            when(memoryRepository.selectExpiredForArchive(any(LocalDateTime.class)))
                    .thenReturn(List.of(record(1L, 100L, MemoryTypeVO.EVENT, "过期A", LocalDateTime.now()),
                            record(2L, 100L, MemoryTypeVO.EVENT, "过期B", LocalDateTime.now())));

            List<GovernanceDecision> decisions = computeService.computeExpired();

            assertEquals(1, decisions.size());
            GovernanceDecision d = decisions.get(0);
            assertEquals("ARCHIVE", d.action());
            assertNull(d.mergedIntoId());
            assertEquals(2, d.items().size());
            assertEquals(MemoryStatus.ARCHIVED.getCode(), d.items().get(0).afterStatus());
            assertEquals(MemoryStatus.ACTIVE.getCode(), d.items().get(0).beforeStatus());
            assertNoPersistence();
        }

        @Test
        @DisplayName("事实一致性：留最早行，其余产出 DISPUTE 决策，不落库")
        void consistencyDisputesAllButEarliest() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
            MemoryRecordEntity earliest = MemoryRecordEntity.builder()
                    .id(1L).userId(100L).type(MemoryTypeVO.FACT).content("A")
                    .subject("user").predicate("city").value("北京")
                    .status(MemoryStatus.ACTIVE).createTime(base).build();
            MemoryRecordEntity later = MemoryRecordEntity.builder()
                    .id(2L).userId(100L).type(MemoryTypeVO.FACT).content("B")
                    .subject("user").predicate("city").value("上海")
                    .status(MemoryStatus.ACTIVE).createTime(base.plusDays(1)).build();
            when(memoryRepository.selectActiveForConsistencyScan()).thenReturn(List.of(later, earliest));

            List<GovernanceDecision> decisions = computeService.computeConsistency();

            assertEquals(1, decisions.size());
            GovernanceDecision d = decisions.get(0);
            assertEquals("DISPUTE", d.action());
            // 只冲突「非最早」的那一条
            assertEquals(1, d.items().size());
            assertEquals(2L, d.items().get(0).memoryId());
            assertEquals(MemoryStatus.DISPUTED.getCode(), d.items().get(0).afterStatus());
            assertNoPersistence();
        }

        @Test
        @DisplayName("事实一致性：同 predicate 同 value 不产生决策")
        void consistencySkipsWhenValuesAgree() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
            MemoryRecordEntity a = MemoryRecordEntity.builder()
                    .id(1L).userId(100L).type(MemoryTypeVO.FACT).subject("user").predicate("city")
                    .value("北京").status(MemoryStatus.ACTIVE).createTime(base).build();
            MemoryRecordEntity b = MemoryRecordEntity.builder()
                    .id(2L).userId(100L).type(MemoryTypeVO.FACT).subject("user").predicate("city")
                    .value("北京").status(MemoryStatus.ACTIVE).createTime(base.plusDays(1)).build();
            when(memoryRepository.selectActiveForConsistencyScan()).thenReturn(List.of(a, b));

            assertTrue(computeService.computeConsistency().isEmpty());
            assertNoPersistence();
        }

        @Test
        @DisplayName("幻觉抽检：evidence 不支撑才产出 QUARANTINE 决策")
        void hallucinationQuarantinesUnsupported() {
            MemoryRecordEntity supported = record(1L, 100L, MemoryTypeVO.FACT, "有证据", LocalDateTime.now());
            MemoryRecordEntity unsupported = record(2L, 100L, MemoryTypeVO.FACT, "无证据", LocalDateTime.now());
            when(memoryRepository.selectSampleForHallucinationCheck(anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(supported, unsupported));
            when(memoryExtractor.verifyEvidence("有证据", null)).thenReturn(true);
            when(memoryExtractor.verifyEvidence("无证据", null)).thenReturn(false);

            List<GovernanceDecision> decisions = computeService.computeHallucination();

            assertEquals(1, decisions.size());
            assertEquals("QUARANTINE", decisions.get(0).action());
            assertEquals(1, decisions.get(0).items().size());
            assertEquals(2L, decisions.get(0).items().get(0).memoryId());
            assertEquals(MemoryStatus.QUARANTINED.getCode(), decisions.get(0).items().get(0).afterStatus());
            assertNoPersistence();
        }

        @Test
        @DisplayName("重复聚类：代表行取最早，其余产出 MERGE 决策且 mergedIntoId=代表行")
        void duplicatesMergeNonRepresentativeRows() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
            MemoryRecordEntity a = record(1L, 100L, MemoryTypeVO.FACT, "内容A", base);
            MemoryRecordEntity b = record(2L, 100L, MemoryTypeVO.FACT, "内容B", base.plusDays(1));
            when(memoryRepository.selectActiveForDuplicateScan()).thenReturn(List.of(a, b));
            when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f});
            when(vectorStore.search(anyLong(), any(float[].class), anyInt()))
                    .thenReturn(List.of(new ScoredMemory(2L, "内容B", 0.95)));

            List<GovernanceDecision> decisions = computeService.computeDuplicates();

            assertEquals(1, decisions.size());
            GovernanceDecision d = decisions.get(0);
            assertEquals("MERGE", d.action());
            assertEquals(1L, d.mergedIntoId(), "代表行应为创建时间最早的 id=1");
            assertEquals(1, d.items().size());
            assertEquals(2L, d.items().get(0).memoryId());
            assertEquals(MemoryStatus.MERGE_PENDING.getCode(), d.items().get(0).afterStatus());
            assertNoPersistence();
        }

        @Test
        @DisplayName("四个 compute 方法在全空输入下均不落库")
        void allComputeMethodsAreReadOnlyOnEmptyInput() {
            when(memoryRepository.selectActiveForDuplicateScan()).thenReturn(List.of());
            when(memoryRepository.selectActiveForConsistencyScan()).thenReturn(List.of());
            when(memoryRepository.selectExpiredForArchive(any(LocalDateTime.class))).thenReturn(List.of());
            when(memoryRepository.selectSampleForHallucinationCheck(anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of());

            assertTrue(computeService.computeDuplicates().isEmpty());
            assertTrue(computeService.computeConsistency().isEmpty());
            assertTrue(computeService.computeExpired().isEmpty());
            assertTrue(computeService.computeHallucination().isEmpty());

            verifyNoInteractions(vectorStore, memoryExtractor);
            assertNoPersistence();
        }

        @Test
        @DisplayName("样本导出：返回真实总数并带上限截断标记")
        void samplesReportTrueTotal() {
            when(memoryRepository.selectActiveForDuplicateScan())
                    .thenReturn(List.of(record(1L, 100L, MemoryTypeVO.FACT, "x", LocalDateTime.now())));
            when(memoryRepository.selectActiveForConsistencyScan()).thenReturn(List.of());
            when(memoryRepository.selectExpiredForArchive(any(LocalDateTime.class))).thenReturn(List.of());
            when(memoryRepository.selectSampleForHallucinationCheck(anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of());

            MemoryGovernanceComputeService.GovernanceSamples samples = computeService.samples();

            assertEquals(1, samples.duplicates().total());
            assertEquals(1, samples.duplicates().items().size());
            assertEquals(0, samples.consistency().total());
            assertNoPersistence();
        }
    }

    @Nested
    @DisplayName("apply 层（落库）")
    @ExtendWith(MockitoExtension.class)
    class Apply {

        @Mock
        private IMemoryRepository memoryRepository;
        @Mock
        private IMemoryGovernanceUndoPort undoPort;

        private MemoryGovernanceApplyService applyService;

        @BeforeEach
        void setUp() throws Exception {
            applyService = new MemoryGovernanceApplyService();
            setField("memoryRepository", memoryRepository);
            setField("undoPort", undoPort);
        }

        private void setField(String name, Object value) throws Exception {
            var f = MemoryGovernanceApplyService.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(applyService, value);
        }

        @Test
        @DisplayName("落库：逐条改 status 并写撤销记录（含 mergedIntoId）")
        void applyUpdatesStatusAndRecordsUndo() {
            GovernanceDecision decision = new GovernanceDecision("MERGE", 1L, "重复簇",
                    List.of(new GovernanceDecision.Item(2L, MemoryStatus.ACTIVE.getCode(),
                            MemoryStatus.MERGE_PENDING.getCode())));

            int affected = applyService.apply(List.of(decision));

            assertEquals(1, affected);
            verify(memoryRepository).updateStatus(2L, MemoryStatus.MERGE_PENDING);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<IMemoryGovernanceUndoPort.UndoItem>> captor = ArgumentCaptor.forClass(List.class);
            verify(undoPort).recordUndo(eq("MERGE"), captor.capture(), eq(1L));
            assertEquals(1, captor.getValue().size());
            assertEquals(2L, captor.getValue().get(0).memoryId());
            assertEquals(MemoryStatus.ACTIVE.getCode(), captor.getValue().get(0).beforeStatus());
        }

        @Test
        @DisplayName("空决策列表不产生任何写操作")
        void applyEmptyIsNoop() {
            assertEquals(0, applyService.apply(List.of()));
            assertEquals(0, applyService.apply(null));
            verifyNoInteractions(memoryRepository, undoPort);
        }

        @Test
        @DisplayName("items 为空的决策被跳过，不写撤销记录")
        void applySkipsEmptyItems() {
            GovernanceDecision empty = new GovernanceDecision("ARCHIVE", null, "空", List.of());

            assertEquals(0, applyService.apply(List.of(empty)));
            verifyNoInteractions(memoryRepository, undoPort);
        }
    }
}
