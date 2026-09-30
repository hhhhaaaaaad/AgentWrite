package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.EmbeddedMemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.service.memory.MemoryExtractor;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.memory.MemoryPersistService;
import cn.sutone.ai.domain.agent.service.memory.MemoryRetriever;
import cn.sutone.ai.domain.agent.service.memory.circuit.MemoryCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MemoryManager 单元测试")
@ExtendWith(MockitoExtension.class)
class MemoryManagerTest {

    @Mock
    private MemoryRetriever memoryRetriever;

    @Mock
    private IMemoryRepository memoryRepository;

    @Mock
    private IMemoryVectorStore vectorStore;

    @Mock
    private IMemoryEmbeddingClient embeddingClient;

    @Mock
    private IMemoryMetricsPort metrics;

    @Mock
    private MemoryExtractor memoryExtractor;

    @Mock
    private MemoryPersistService memoryPersistService;

    @Mock
    private MemoryCircuitBreaker memoryCircuitBreaker;

    private MemoryManager memoryManager;

    @BeforeEach
    void setUp() throws Exception {
        memoryManager = new MemoryManager();

        setField("memoryRetriever", memoryRetriever);
        setField("memoryRepository", memoryRepository);
        setField("vectorStore", vectorStore);
        setField("embeddingClient", embeddingClient);
        setField("memoryExtractor", memoryExtractor);
        setField("memoryPersistService", memoryPersistService);
        setField("metrics", metrics);
        setField("memoryCircuitBreaker", memoryCircuitBreaker);
        lenient().when(memoryCircuitBreaker.isDegraded()).thenReturn(false);
    }

    private void setField(String name, Object value) throws Exception {
        var f = MemoryManager.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(memoryManager, value);
    }

    private void invokeAdd(Long userId, Long agentId, String sessionId,
                           List<Map<String, String>> messages) throws Exception {
        Method m = MemoryManager.class.getDeclaredMethod("add",
                Long.class, Long.class, String.class, Long.class, List.class, String.class);
        m.setAccessible(true);
        m.invoke(memoryManager, userId, agentId, sessionId, null, messages, "trace-test");
    }

    @Test
    @DisplayName("旧字符串入口遇到空查询时应直接返回空字符串")
    void shouldShortCircuitBlankLegacyQuery() {
        assertEquals("", memoryManager.retrieveContext(1L, "", 5));
        assertEquals("", memoryManager.retrieveContext(1L, "   ", 5));
        assertEquals("", memoryManager.retrieveContext(1L, (String) null, 5));

        verify(memoryRetriever, never()).retrieveFormattedContext(anyLong(), anyString(), anyInt());
        verify(memoryRetriever, never()).retrieveFormattedContext(anyLong(), any(MemoryRetrieveQueryVO.class), anyInt());
    }

    @Test
    @DisplayName("旧字符串入口非空时应走 legacy 字符串检索链路")
    void shouldUseLegacyStringRetrieverForNonBlankQuery() {
        when(memoryRetriever.retrieveFormattedContext(1L, "Java 记忆系统", 5)).thenReturn("- 用户擅长 Java");

        String result = memoryManager.retrieveContext(1L, "Java 记忆系统", 5);

        assertEquals("- 用户擅长 Java", result);
        verify(memoryRetriever).retrieveFormattedContext(1L, "Java 记忆系统", 5);
        verify(memoryRetriever, never()).retrieveFormattedContext(anyLong(), any(MemoryRetrieveQueryVO.class), anyInt());
    }

    @Test
    @DisplayName("结构化入口遇到空查询对象时应直接返回空字符串")
    void shouldShortCircuitNullStructuredQuery() {
        assertEquals("", memoryManager.retrieveContext(1L, (MemoryRetrieveQueryVO) null, 5));

        verify(memoryRetriever, never()).retrieveFormattedContext(anyLong(), any(MemoryRetrieveQueryVO.class), anyInt());
    }

    @Test
    @DisplayName("P0-1: hash 去重后，幸存候选的文本与向量在 decideOperation 中仍绑定一致")
    void shouldBindTextAndVectorAfterDedup() throws Exception {
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.1f});
        when(vectorStore.search(anyLong(), any(float[].class), anyInt())).thenReturn(Collections.emptyList());

        MemoryCandidate c1 = new MemoryCandidate("唯一文本A", "fact", "user");
        MemoryCandidate c2 = new MemoryCandidate("唯一文本A", "fact", "user"); // 重复
        MemoryCandidate c3 = new MemoryCandidate("唯一文本B", "fact", "user");
        when(memoryExtractor.extract(anyList(), anyList(), anyList()))
                .thenReturn(List.of(c1, c2, c3));
        when(memoryExtractor.decideOperation(any(EmbeddedMemoryCandidate.class), anyLong()))
                .thenReturn(new MemoryExtractor.OperationDecision("ADD", null));

        // embedBatch 返回与 content 绑定的向量：首位为 content.hashCode()
        when(embeddingClient.embedBatch(anyList()))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<String> texts = inv.getArgument(0);
                    List<float[]> out = new ArrayList<>();
                    for (String t : texts) {
                        out.add(new float[]{t.hashCode()});
                    }
                    return out;
                });

        invokeAdd(1L, 1L, "s1", List.of(Map.of("content", "hello")));

        ArgumentCaptor<EmbeddedMemoryCandidate> ecCaptor = ArgumentCaptor.forClass(EmbeddedMemoryCandidate.class);
        verify(memoryExtractor, times(2)).decideOperation(ecCaptor.capture(), eq(1L));
        List<EmbeddedMemoryCandidate> captured = ecCaptor.getAllValues();
        assertEquals(List.of("唯一文本A", "唯一文本B"),
                captured.stream().map(EmbeddedMemoryCandidate::content).toList());
        for (EmbeddedMemoryCandidate ec : captured) {
            assertEquals((float) ec.content().hashCode(), ec.embedding()[0], 0.001f);
        }
        // 持久化委托给 MemoryPersistService（向量异步同步，不再直接 upsert）
        verify(memoryPersistService).persistSurvivors(eq(1L), eq("s1"), anyString(), anyList(), anyList(), anyList());
    }

    @Test
    @DisplayName("AW-3: addDirect 撞 uk_user_hash 唯一索引转 existed，复用已有 id，不写向量")
    void shouldReturnExistedOnDuplicateHash() {
        when(memoryRepository.insert(any(MemoryRecordEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_user_hash"));
        MemoryRecordEntity existing = MemoryRecordEntity.create(
                42L, 1L, "fact", "用户偏好 Java", "hash", "eval-seed");
        when(memoryRepository.selectByUserIdAndHash(eq(1L), anyString())).thenReturn(existing);

        MemoryManager.AddDirectResult result = memoryManager.addDirect(1L, MemoryTypeVO.FACT, "用户偏好 Java");

        assertFalse(result.inserted());
        assertEquals(42L, result.id());
        verify(vectorStore, never()).upsert(anyLong(), anyLong(), any(float[].class), anyString(), anyString());
        verify(embeddingClient, never()).embed(anyString());
    }

    @Test
    @DisplayName("P0-3: addDirect Qdrant 写失败时标记 PENDING 而非 SYNCED")
    void shouldMarkPendingWhenDirectUpsertFails() {
        when(memoryRepository.insert(any(MemoryRecordEntity.class))).thenReturn(10L);
        when(embeddingClient.embed(anyString())).thenReturn(new float[]{0.5f});
        doThrow(new RuntimeException("qdrant down")).when(vectorStore)
                .upsert(anyLong(), anyLong(), any(float[].class), anyString(), anyString());

        memoryManager.addDirect(1L, MemoryTypeVO.FACT, "用户偏好 Java");

        verify(memoryRepository).updateVectorStatus(10L, "PENDING");
        verify(memoryRepository, never()).updateVectorStatus(10L, "SYNCED");
    }
}
