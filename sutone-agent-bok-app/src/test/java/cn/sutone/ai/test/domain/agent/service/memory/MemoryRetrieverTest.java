package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.adapter.repository.IRerankerClient;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.domain.agent.service.memory.MemoryQueryNormalizer;
import cn.sutone.ai.domain.agent.service.memory.MemoryRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("MemoryRetriever 单元测试")
class MemoryRetrieverTest {

    private MemoryRetriever retriever;
    private IMemoryEmbeddingClient embeddingClient;
    private IMemoryVectorStore vectorStore;
    private IMemoryRepository memoryRepository;
    private IRerankerClient rerankerClient;
    private MemoryProperties memoryProperties;
    private RedisTemplate<String, String> redisTemplate;
    private ValueOperations<String, String> valueOps;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        embeddingClient = mock(IMemoryEmbeddingClient.class);
        vectorStore = mock(IMemoryVectorStore.class);
        memoryRepository = mock(IMemoryRepository.class);
        rerankerClient = mock(IRerankerClient.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        memoryProperties = new MemoryProperties();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        // Field injection via reflection
        injectFields();
    }

    private void injectFields() {
        try {
            retriever = new MemoryRetriever();
            var clazz = MemoryRetriever.class;

            var embField = clazz.getDeclaredField("embeddingClient");
            embField.setAccessible(true);
            embField.set(retriever, embeddingClient);

            var vsField = clazz.getDeclaredField("vectorStore");
            vsField.setAccessible(true);
            vsField.set(retriever, vectorStore);

            var repoField = clazz.getDeclaredField("memoryRepository");
            repoField.setAccessible(true);
            repoField.set(retriever, memoryRepository);

            var rerankerField = clazz.getDeclaredField("rerankerClient");
            rerankerField.setAccessible(true);
            rerankerField.set(retriever, rerankerClient);

            var propsField = clazz.getDeclaredField("memoryProperties");
            propsField.setAccessible(true);
            propsField.set(retriever, memoryProperties);

            var redisField = clazz.getDeclaredField("redisTemplate");
            redisField.setAccessible(true);
            redisField.set(retriever, redisTemplate);

            var normalizerField = clazz.getDeclaredField("memoryQueryNormalizer");
            normalizerField.setAccessible(true);
            normalizerField.set(retriever, new MemoryQueryNormalizer());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("语义搜索")
    class SemanticSearch {

        @Test
        @DisplayName("embedding 可用时应返回语义搜索结果")
        void shouldReturnSemanticResults() {
            float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);

            ScoredMemory sm = new ScoredMemory(1L, "用户是Java工程师", 0.85, 0.5, LocalDateTime.now(), "hash1");
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(List.of(sm));
            when(memoryRepository.fulltextSearch(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());
            // Reranker will be called but just returns the first N
            when(rerankerClient.rerank(anyString(), anyList(), anyInt()))
                    .thenAnswer(inv -> ((List<ScoredMemory>) inv.getArgument(1)).subList(0, Math.min(5, ((List<?>) inv.getArgument(1)).size())));

            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, "Java开发", 5);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().anyMatch(r -> r.content().contains("Java")));
        }

        @Test
        @DisplayName("embedding 不可用时应降级到纯 BM25")
        void shouldFallbackToBm25WhenEmbeddingUnavailable() {
            when(embeddingClient.embed(anyString())).thenReturn(new float[0]);

            MemoryRecordEntity record = MemoryRecordEntity.create(1L, 1L, "fact", "测试内容Java", "hash1", "s1");
            record.setMatchScore(3.0);
            when(memoryRepository.fulltextSearch(eq(1L), anyString(), anyInt())).thenReturn(List.of(record));
            when(memoryRepository.queryById(eq(1L))).thenReturn(record);

            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, "Java", 5);

            assertFalse(results.isEmpty());
            verify(vectorStore, never()).search(anyLong(), any(), anyInt());
        }

        @Test
        @DisplayName("空查询应返回空列表")
        void shouldReturnEmptyForBlankQuery() {
            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, "", 5);
            assertTrue(results.isEmpty());

            results = retriever.search(1L, (String) null, 5);
            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("评分融合")
    class ScoringFusion {

        @Test
        @DisplayName("最近访问的记忆应获得更高的 recency boost")
        void shouldBoostRecentMemories() {
            float[] embedding = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);

            ScoredMemory recent = new ScoredMemory(1L, "最近内容", 0.5, 0.5, LocalDateTime.now(), "h1");
            ScoredMemory old = new ScoredMemory(2L, "旧内容", 0.5, 0.5, LocalDateTime.now().minusDays(60), "h2");
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(List.of(recent, old));
            when(memoryRepository.fulltextSearch(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());
            when(rerankerClient.rerank(anyString(), anyList(), anyInt()))
                    .thenAnswer(inv -> ((List<ScoredMemory>) inv.getArgument(1)).subList(0, Math.min(5, ((List<?>) inv.getArgument(1)).size())));

            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, "测试", 5);

            assertFalse(results.isEmpty());
            // 最近记忆应排在前面（分数更高）
            var first = results.get(0);
            assertEquals(1L, (long) first.id());
        }

        @Test
        @DisplayName("重要性高的记忆分数应更高")
        void shouldBoostHighImportanceMemories() {
            float[] embedding = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);

            ScoredMemory highImp = new ScoredMemory(1L, "重要内容", 0.5, 0.9, LocalDateTime.now(), "h1");
            ScoredMemory lowImp = new ScoredMemory(2L, "普通内容", 0.5, 0.3, LocalDateTime.now(), "h2");
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(List.of(highImp, lowImp));
            when(memoryRepository.fulltextSearch(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());
            when(rerankerClient.rerank(anyString(), anyList(), anyInt()))
                    .thenAnswer(inv -> ((List<ScoredMemory>) inv.getArgument(1)).subList(0, Math.min(5, ((List<?>) inv.getArgument(1)).size())));

            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, "测试", 5);

            assertFalse(results.isEmpty());
            assertEquals(1L, (long) results.get(0).id());
        }
    }

    @Nested
    @DisplayName("结构化查询")
    class StructuredQuery {

        @Test
        @DisplayName("应分别使用 semanticQuery 和 lexicalQuery")
        void shouldUseSemanticAndLexicalQuerySeparately() {
            float[] embedding = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(Collections.emptyList());

            MemoryRecordEntity record = MemoryRecordEntity.create(1L, 1L, "fact", "用户偏好面试表达", "hash1", "s1");
            record.setMatchScore(2.0);
            when(memoryRepository.fulltextSearch(eq(1L), anyString(), anyInt())).thenReturn(List.of(record));
            when(memoryRepository.queryById(eq(1L))).thenReturn(record);

            MemoryRetrieveQueryVO query = MemoryRetrieveQueryVO.builder()
                    .taskType("GENERATE_OUTLINE")
                    .title("记忆系统混合检索设计")
                    .summary("围绕 Qdrant、BM25、Reranker 展开")
                    .customInstruction("偏面试表达")
                    .build();

            retriever.search(1L, query, 5);

            verify(embeddingClient).embed(argThat(s -> s != null && s.contains("任务模式")));
            verify(memoryRepository).fulltextSearch(eq(1L), argThat(s -> s != null && s.contains("面试表达")), anyInt());
        }

        @Test
        @DisplayName("仅有任务类型的结构化查询应直接返回空结果")
        void shouldReturnEmptyForTaskTypeOnlyStructuredQuery() {
            MemoryRetrieveQueryVO query = MemoryRetrieveQueryVO.builder()
                    .taskType("GENERATE_BODY")
                    .build();

            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, query, 5);

            assertTrue(results.isEmpty());
            verify(embeddingClient, never()).embed(anyString());
            verify(memoryRepository, never()).fulltextSearch(anyLong(), anyString(), anyInt());
            verify(valueOps, never()).get(anyString());
        }

        @Test
        @DisplayName("搜索缓存键应包含 digest 和 topK")
        void shouldUseDigestAndTopKBasedSearchCacheKey() {
            float[] embedding = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(Collections.emptyList());
            when(memoryRepository.fulltextSearch(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());

            MemoryRetrieveQueryVO query = MemoryRetrieveQueryVO.builder()
                    .taskType("GENERATE_BODY")
                    .title("AgentWrite 记忆系统")
                    .summary("强调混合检索与记忆注入")
                    .formatInstruction("使用 STAR 结构")
                    .build();

            String expectedDigest = new MemoryQueryNormalizer().normalize(query).getCacheKeyDigest();

            retriever.search(1L, query, 5);
            retriever.search(1L, query, 3);

            verify(valueOps).get(eq("memory:user:1:search:v2:" + expectedDigest + ":topK:5:threshold:0.1000"));
            verify(valueOps).set(eq("memory:user:1:search:v2:" + expectedDigest + ":topK:5:threshold:0.1000"), anyString(), anyLong(), eq(TimeUnit.MINUTES));
            verify(valueOps).get(eq("memory:user:1:search:v2:" + expectedDigest + ":topK:3:threshold:0.1000"));
            verify(valueOps).set(eq("memory:user:1:search:v2:" + expectedDigest + ":topK:3:threshold:0.1000"), anyString(), anyLong(), eq(TimeUnit.MINUTES));
        }
    }
}
