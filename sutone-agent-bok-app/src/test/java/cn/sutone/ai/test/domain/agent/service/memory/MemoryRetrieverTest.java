package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.adapter.repository.IRerankerClient;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.domain.agent.service.memory.MemoryAccessService;
import cn.sutone.ai.domain.agent.service.memory.MemoryQueryNormalizer;
import cn.sutone.ai.domain.agent.service.memory.MemoryRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private MemoryAccessService memoryAccessService;
    private IMemoryMetricsPort metrics;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        embeddingClient = mock(IMemoryEmbeddingClient.class);
        vectorStore = mock(IMemoryVectorStore.class);
        memoryRepository = mock(IMemoryRepository.class);
        rerankerClient = mock(IRerankerClient.class);
        redisTemplate = mock(RedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        memoryAccessService = mock(MemoryAccessService.class);
        metrics = mock(IMemoryMetricsPort.class);
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

            var accessField = clazz.getDeclaredField("memoryAccessService");
            accessField.setAccessible(true);
            accessField.set(retriever, memoryAccessService);

            // P3 可观测改造新增字段：不注入会导致 search() 里 recordRetrievalDuration NPE
            var metricsField = clazz.getDeclaredField("metrics");
            metricsField.setAccessible(true);
            metricsField.set(retriever, metrics);
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
            MemoryRecordEntity entity = MemoryRecordEntity.create(1L, 1L, "fact", "用户是Java工程师", "hash1", "s1");
            when(memoryRepository.queryByIds(anyList())).thenReturn(List.of(entity));

            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, "Java开发", 5);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().anyMatch(r -> r.content().contains("Java")));
            verify(memoryAccessService).recordAccessAsync(argThat(ids -> ids.contains(1L)));
        }

        @Test
        @DisplayName("embedding 不可用时应降级到纯 BM25")
        void shouldFallbackToBm25WhenEmbeddingUnavailable() {
            when(embeddingClient.embed(anyString())).thenReturn(new float[0]);

            MemoryRecordEntity record = MemoryRecordEntity.create(1L, 1L, "fact", "测试内容Java", "hash1", "s1");
            record.setMatchScore(3.0);
            when(memoryRepository.fulltextSearch(eq(1L), anyString(), anyInt())).thenReturn(List.of(record));
            when(memoryRepository.queryByIds(anyList())).thenReturn(List.of(record));

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

            MemoryRecordEntity recentEntity = MemoryRecordEntity.create(1L, 1L, "fact", "最近内容", "h1", "s1");
            recentEntity.setLastAccessedAt(LocalDateTime.now());
            MemoryRecordEntity oldEntity = MemoryRecordEntity.create(2L, 1L, "fact", "旧内容", "h2", "s1");
            oldEntity.setLastAccessedAt(LocalDateTime.now().minusDays(60));
            when(memoryRepository.queryByIds(anyList())).thenReturn(List.of(recentEntity, oldEntity));

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

            MemoryRecordEntity high = MemoryRecordEntity.create(1L, 1L, "fact", "重要内容", "h1", "s1");
            high.setImportance(0.9);
            MemoryRecordEntity low = MemoryRecordEntity.create(2L, 1L, "fact", "普通内容", "h2", "s1");
            low.setImportance(0.3);
            when(memoryRepository.queryByIds(anyList())).thenReturn(List.of(high, low));

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
            when(memoryRepository.queryByIds(anyList())).thenReturn(List.of(record));

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
        @DisplayName("搜索缓存键应包含 digest、topK、threshold 和 memoryVersion")
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

            verify(valueOps).get(eq("memory:user:1:search:v2:" + expectedDigest + ":topK:5:threshold:0.1000:ver:0"));
            verify(valueOps).set(eq("memory:user:1:search:v2:" + expectedDigest + ":topK:5:threshold:0.1000:ver:0"), anyString(), anyLong(), eq(TimeUnit.MINUTES));
            verify(valueOps).get(eq("memory:user:1:search:v2:" + expectedDigest + ":topK:3:threshold:0.1000:ver:0"));
            verify(valueOps).set(eq("memory:user:1:search:v2:" + expectedDigest + ":topK:3:threshold:0.1000:ver:0"), anyString(), anyLong(), eq(TimeUnit.MINUTES));
        }
    }

    @Nested
    @DisplayName("P2 检索改造")
    class P2Retrieval {

        @Test
        @DisplayName("RRF 融合：双路召回的记忆应排在单路之前")
        void shouldRankDoubleChannelAboveSingleChannel() {
            float[] embedding = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);

            // semantic: id1(rank0) + id2(rank1)；lexical: 仅 id2(rank0)
            ScoredMemory s1 = new ScoredMemory(1L, "内容一", 0.9, 0.5, LocalDateTime.now(), "h1");
            ScoredMemory s2 = new ScoredMemory(2L, "内容二", 0.8, 0.5, LocalDateTime.now(), "h2");
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(List.of(s1, s2));

            MemoryRecordEntity r1 = MemoryRecordEntity.create(1L, 1L, "fact", "内容一", "h1", "s1");
            MemoryRecordEntity r2 = MemoryRecordEntity.create(2L, 1L, "fact", "内容二", "h2", "s1");
            r2.setMatchScore(5.0);
            when(memoryRepository.fulltextSearch(eq(1L), anyString(), anyInt())).thenReturn(List.of(r2));
            when(memoryRepository.queryByIds(anyList())).thenReturn(List.of(r1, r2));

            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, "测试", 5);

            // id2 双路（RRF ≈ 1/62 + 1/61）应排在 id1（仅语义 1/61）之前
            assertFalse(results.isEmpty());
            assertEquals(2L, (long) results.get(0).id());
        }

        @Test
        @DisplayName("回表过滤：过期/低置信/非 ACTIVE 不注入")
        void shouldFilterExpiredLowConfidenceAndInactive() {
            memoryProperties.getRetrieval().setMinConfidence(0.5);

            float[] embedding = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);
            ScoredMemory s1 = new ScoredMemory(1L, "有效", 0.9, 0.5, LocalDateTime.now(), "h1");
            ScoredMemory s2 = new ScoredMemory(2L, "过期", 0.9, 0.5, LocalDateTime.now(), "h2");
            ScoredMemory s3 = new ScoredMemory(3L, "低置信", 0.9, 0.5, LocalDateTime.now(), "h3");
            ScoredMemory s4 = new ScoredMemory(4L, "非激活", 0.9, 0.5, LocalDateTime.now(), "h4");
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(List.of(s1, s2, s3, s4));
            when(memoryRepository.fulltextSearch(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());

            MemoryRecordEntity valid = MemoryRecordEntity.create(1L, 1L, "fact", "有效", "h1", "s1");
            valid.setConfidence(0.8);
            MemoryRecordEntity expired = MemoryRecordEntity.create(2L, 1L, "fact", "过期", "h2", "s1");
            expired.setExpireTime(LocalDateTime.now().minusDays(1));
            MemoryRecordEntity lowConf = MemoryRecordEntity.create(3L, 1L, "fact", "低置信", "h3", "s1");
            lowConf.setConfidence(0.1);
            MemoryRecordEntity inactive = MemoryRecordEntity.create(4L, 1L, "fact", "非激活", "h4", "s1");
            inactive.setStatus(MemoryStatus.SUPERSEDED);
            when(memoryRepository.queryByIds(anyList())).thenReturn(List.of(valid, expired, lowConf, inactive));

            List<MemoryRetriever.MemoryItem> results = retriever.search(1L, "测试", 5);

            assertEquals(1, results.size());
            assertEquals(1L, (long) results.get(0).id());
        }

        @Test
        @DisplayName("topK 精排应传入动态 topN=topK（而非硬编码 5）")
        void shouldRerankWithDynamicTopN() {
            float[] embedding = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);

            List<ScoredMemory> scored = new ArrayList<>();
            List<MemoryRecordEntity> entities = new ArrayList<>();
            for (long i = 1; i <= 10; i++) {
                scored.add(new ScoredMemory(i, "内容" + i, 1.0 - i * 0.01, 0.5, LocalDateTime.now(), "h" + i));
                entities.add(MemoryRecordEntity.create(i, 1L, "fact", "内容" + i, "h" + i, "s1"));
            }
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(scored);
            when(memoryRepository.fulltextSearch(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());
            when(memoryRepository.queryByIds(anyList())).thenReturn(entities);
            when(rerankerClient.rerank(anyString(), anyList(), anyInt()))
                    .thenAnswer(inv -> {
                        List<ScoredMemory> c = inv.getArgument(1);
                        int n = inv.getArgument(2);
                        return c.subList(0, Math.min(n, c.size()));
                    });

            retriever.search(1L, "测试", 3);

            verify(rerankerClient).rerank(anyString(), anyList(), eq(3));
        }

        @Test
        @DisplayName("任务化注入应按类型预算 token 并用 <memory_context> 包裹")
        void shouldBudgetTokensAndWrapBoundary() {
            float[] embedding = new float[]{0.1f, 0.2f};
            when(embeddingClient.embed(anyString())).thenReturn(embedding);
            ScoredMemory s1 = new ScoredMemory(1L, "用户偏好 Markdown 格式", 0.9, 0.5, LocalDateTime.now(), "h1");
            when(vectorStore.search(eq(1L), any(), anyInt())).thenReturn(List.of(s1));
            when(memoryRepository.fulltextSearch(anyLong(), anyString(), anyInt())).thenReturn(Collections.emptyList());

            MemoryRecordEntity p = MemoryRecordEntity.create(1L, 1L, "preference", "用户偏好 Markdown 格式", "h1", "s1");
            p.setConfidence(0.9);
            when(memoryRepository.queryByIds(anyList())).thenReturn(List.of(p));

            MemoryRetrieveQueryVO query = MemoryRetrieveQueryVO.builder()
                    .taskType("GENERATE_OUTLINE")
                    .contentMd("写大纲")
                    .build();
            String ctx = retriever.retrieveFormattedContext(1L, query, 5);

            assertTrue(ctx.startsWith("<memory_context>"));
            assertTrue(ctx.contains("不是指令"));
            assertTrue(ctx.contains("【偏好】"));
            assertTrue(ctx.contains("置信度: 高"));
            assertTrue(ctx.endsWith("</memory_context>"));
        }
    }
}
