package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.domain.agent.service.memory.MemoryExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("MemoryExtractor 单元测试")
class MemoryExtractorTest {

    private MemoryExtractor extractor;
    private IMemoryEmbeddingClient embeddingClient;
    private IMemoryVectorStore vectorStore;
    private MemoryProperties memoryProperties;
    private AiAgentAutoConfigProperties agentProps;
    private IMemoryMetricsPort metrics;

    @BeforeEach
    void setUp() {
        embeddingClient = mock(IMemoryEmbeddingClient.class);
        vectorStore = mock(IMemoryVectorStore.class);
        memoryProperties = new MemoryProperties();
        agentProps = mock(AiAgentAutoConfigProperties.class);
        metrics = mock(IMemoryMetricsPort.class);

        extractor = new MemoryExtractor();
        injectFields();
    }

    private void injectFields() {
        try {
            var clazz = MemoryExtractor.class;

            var ecField = clazz.getDeclaredField("embeddingClient");
            ecField.setAccessible(true);
            ecField.set(extractor, embeddingClient);

            var vsField = clazz.getDeclaredField("vectorStore");
            vsField.setAccessible(true);
            vsField.set(extractor, vectorStore);

            var mpField = clazz.getDeclaredField("memoryProperties");
            mpField.setAccessible(true);
            mpField.set(extractor, memoryProperties);

            var apField = clazz.getDeclaredField("aiAgentAutoConfigProperties");
            apField.setAccessible(true);
            apField.set(extractor, agentProps);

            // P3 可观测改造新增字段：不注入会导致 parseResponse 里 NPE 被 catch 吞掉，解析结果恒为空
            var metricsField = clazz.getDeclaredField("metrics");
            metricsField.setAccessible(true);
            metricsField.set(extractor, metrics);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("JSON 解析 (parseResponse)")
    class JsonParsing {

        @Test
        @DisplayName("正常 JSON 解析应返回记忆列表")
        void shouldParseNormalJson() {
            // We test extract indirectly through its parsing behavior
            // Since extract() calls LLM which we can't mock easily, test the parseResponse via reflection
            try {
                var parseMethod = MemoryExtractor.class.getDeclaredMethod("parseResponse", String.class);
                parseMethod.setAccessible(true);

                String json = """
                    {
                      "memory": [
                        {"text": "用户是Java工程师", "type": "fact", "attributed_to": "user"},
                        {"text": "偏好表格对比", "type": "preference", "attributed_to": "agent"}
                      ]
                    }""";

                @SuppressWarnings("unchecked")
                List<MemoryCandidate> results = (List<MemoryCandidate>) parseMethod.invoke(extractor, json);
                assertEquals(2, results.size());
                assertEquals("用户是Java工程师", results.get(0).content());
                assertEquals("fact", results.get(0).type());
                assertEquals("preference", results.get(1).type());
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("Markdown code fence 包裹的 JSON 应正确解析")
        void shouldParseMarkdownWrappedJson() {
            try {
                var parseMethod = MemoryExtractor.class.getDeclaredMethod("parseResponse", String.class);
                parseMethod.setAccessible(true);

                String json = """
                    ```json
                    {
                      "memory": [
                        {"text": "使用Redis做缓存", "type": "fact", "attributed_to": "user"}
                      ]
                    }
                    ```""";

                @SuppressWarnings("unchecked")
                List<MemoryCandidate> results = (List<MemoryCandidate>) parseMethod.invoke(extractor, json);
                assertEquals(1, results.size());
                assertEquals("使用Redis做缓存", results.get(0).content());
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("超长内容应被过滤")
        void shouldFilterOverlyLongContent() {
            try {
                var parseMethod = MemoryExtractor.class.getDeclaredMethod("parseResponse", String.class);
                parseMethod.setAccessible(true);

                String longText = "A".repeat(501); // exceeds default 500 limit
                String json = "{\"memory\": [{\"text\": \"" + longText + "\", \"type\": \"fact\", \"attributed_to\": \"user\"}]}";

                @SuppressWarnings("unchecked")
                List<MemoryCandidate> results = (List<MemoryCandidate>) parseMethod.invoke(extractor, json);
                assertTrue(results.isEmpty(), "超长内容应被过滤");
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("无效 JSON 应返回空列表")
        void shouldReturnEmptyForMalformedJson() {
            try {
                var parseMethod = MemoryExtractor.class.getDeclaredMethod("parseResponse", String.class);
                parseMethod.setAccessible(true);

                @SuppressWarnings("unchecked")
                List<MemoryCandidate> results = (List<MemoryCandidate>) parseMethod.invoke(extractor, "这不是JSON");
                assertTrue(results.isEmpty());
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("无效 type 应被过滤")
        void shouldFilterInvalidType() {
            try {
                var parseMethod = MemoryExtractor.class.getDeclaredMethod("parseResponse", String.class);
                parseMethod.setAccessible(true);

                String json = "{\"memory\": ["
                        + "{\"text\": \"valid\", \"type\": \"fact\", \"attributed_to\": \"user\"},"
                        + "{\"text\": \"invalid type\", \"type\": \"wrong_type\", \"attributed_to\": \"agent\"}"
                        + "]}";

                @SuppressWarnings("unchecked")
                List<MemoryCandidate> results = (List<MemoryCandidate>) parseMethod.invoke(extractor, json);
                assertEquals(1, results.size());
                assertEquals("valid", results.get(0).content());
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("解析新结构化字段（operation/target_memory_id/subject/predicate/value/evidence/confidence）")
        void shouldParseStructuredFields() {
            try {
                var parseMethod = MemoryExtractor.class.getDeclaredMethod("parseResponse", String.class);
                parseMethod.setAccessible(true);

                String json = """
                    {
                      "memory": [
                        {"text":"技术栈 Java 17","type":"fact","attributed_to":"user",
                         "operation":"UPDATE","target_memory_id":42,
                         "subject":"user","predicate":"tech_stack","value":"Java 17",
                         "evidence":"我们项目用 Java 17","confidence":0.9}
                      ]
                    }""";

                @SuppressWarnings("unchecked")
                List<MemoryCandidate> results = (List<MemoryCandidate>) parseMethod.invoke(extractor, json);
                assertEquals(1, results.size());
                MemoryCandidate c = results.get(0);
                assertEquals("UPDATE", c.operation());
                assertEquals(42L, c.targetMemoryId());
                assertEquals("user", c.subject());
                assertEquals("tech_stack", c.predicate());
                assertEquals("Java 17", c.value());
                assertEquals("我们项目用 Java 17", c.evidence());
                assertEquals(0.9, c.confidence(), 0.001);
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("operation 缺省为 ADD，target_memory_id=0 归一为 null")
        void shouldDefaultOperationAndNormalizeZeroTarget() {
            try {
                var parseMethod = MemoryExtractor.class.getDeclaredMethod("parseResponse", String.class);
                parseMethod.setAccessible(true);

                String json = """
                    {
                      "memory": [
                        {"text":"技术栈 Java 17","type":"fact","attributed_to":"user","target_memory_id":0}
                      ]
                    }""";

                @SuppressWarnings("unchecked")
                List<MemoryCandidate> results = (List<MemoryCandidate>) parseMethod.invoke(extractor, json);
                assertEquals(1, results.size());
                assertEquals("ADD", results.get(0).operation());
                assertNull(results.get(0).targetMemoryId());
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("confidence 缺省时按 evidence 有无推断（有 0.8 / 无 0.3）")
        void shouldInferConfidenceByEvidence() {
            try {
                var parseMethod = MemoryExtractor.class.getDeclaredMethod("parseResponse", String.class);
                parseMethod.setAccessible(true);

                String json = """
                    {
                      "memory": [
                        {"text":"带证据","type":"fact","attributed_to":"user","evidence":"原文片段"},
                        {"text":"无证据","type":"preference","attributed_to":"user"}
                      ]
                    }""";

                @SuppressWarnings("unchecked")
                List<MemoryCandidate> results = (List<MemoryCandidate>) parseMethod.invoke(extractor, json);
                assertEquals(2, results.size());
                assertEquals(0.8, results.get(0).confidence(), 0.001);
                assertEquals(0.3, results.get(1).confidence(), 0.001);
            } catch (Exception e) {
                fail("Reflection failed: " + e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("UPDATE 判定 (findUpdateTarget)")
    class UpdateDetection {

        @Test
        @DisplayName("向量相似度 > 0.9 应返回 UPDATE 目标 ID")
        void shouldReturnUpdateTargetWhenHighlySimilar() {
            float[] candidate = new float[]{0.9f, 0.1f, 0.1f};
            ScoredMemory similar = new ScoredMemory(42L, "existing", 0.95, null, null, null);
            when(vectorStore.search(eq(1L), any(), eq(1))).thenReturn(List.of(similar));

            Long targetId = extractor.findUpdateTarget(candidate, 1L);

            assertEquals(42L, targetId);
        }

        @Test
        @DisplayName("向量相似度 <= 0.9 应返回 null（需新增）")
        void shouldReturnNullWhenNotSimilarEnough() {
            float[] candidate = new float[]{1.0f, 0.0f, 0.0f};
            ScoredMemory dissimilar = new ScoredMemory(42L, "existing", 0.5, null, null, null);
            when(vectorStore.search(eq(1L), any(), eq(1))).thenReturn(List.of(dissimilar));

            Long targetId = extractor.findUpdateTarget(candidate, 1L);

            assertNull(targetId);
        }

        @Test
        @DisplayName("无已有记忆时应返回 null")
        void shouldReturnNullWhenNoExistingMemories() {
            float[] candidate = new float[]{0.9f, 0.1f};
            when(vectorStore.search(eq(1L), any(), eq(1))).thenReturn(Collections.emptyList());

            Long targetId = extractor.findUpdateTarget(candidate, 1L);

            assertNull(targetId);
        }
    }
}
