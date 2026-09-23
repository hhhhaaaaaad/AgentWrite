package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.EmbeddedMemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryCandidate;
import cn.sutone.ai.domain.agent.service.memory.MemoryExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@DisplayName("MemoryExtractor.decideOperation 身份化 UPDATE 判定")
@ExtendWith(MockitoExtension.class)
class MemoryExtractorDecisionTest {

    @Mock
    private IMemoryEmbeddingClient embeddingClient;

    @Mock
    private IMemoryVectorStore vectorStore;

    @Mock
    private IMemoryRepository memoryRepository;

    private MemoryExtractor extractor;

    @BeforeEach
    void setUp() throws Exception {
        extractor = new MemoryExtractor();
        setField("embeddingClient", embeddingClient);
        setField("vectorStore", vectorStore);
        setField("memoryRepository", memoryRepository);
    }

    private void setField(String name, Object value) throws Exception {
        var f = MemoryExtractor.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(extractor, value);
    }

    private EmbeddedMemoryCandidate ec(String subject, String predicate, String value) {
        MemoryCandidate c = new MemoryCandidate("内容", "fact", "user",
                "ADD", null, subject, predicate, value, "证据", 0.9);
        return new EmbeddedMemoryCandidate(c, "hash", new float[]{0.1f});
    }

    private MemoryRecordEntity existing(long id, String subject, String predicate, String value) {
        MemoryRecordEntity e = MemoryRecordEntity.create(id, 1L, "fact", "旧内容", "oldhash", "s");
        e.setSubject(subject);
        e.setPredicate(predicate);
        e.setValue(value);
        return e;
    }

    @Test
    @DisplayName("同 (user, subject, predicate) + 同 value → NOOP（幂等）")
    void shouldNoopWhenSameValue() {
        when(memoryRepository.selectActiveByUserSubjectPredicate(1L, "user", "tech-stack"))
                .thenReturn(existing(10L, "user", "tech-stack", "java 17"));

        MemoryExtractor.OperationDecision d = extractor.decideOperation(
                ec("user", "tech_stack", "Java 17"), 1L);

        assertEquals("NOOP", d.action());
    }

    @Test
    @DisplayName("同 predicate 异 value → DISPUTED（不强制 SUPERSEDE，保留多版本）")
    void shouldDisputeWhenDifferentValue() {
        when(memoryRepository.selectActiveByUserSubjectPredicate(1L, "user", "tech-stack"))
                .thenReturn(existing(10L, "user", "tech-stack", "java 17"));

        MemoryExtractor.OperationDecision d = extractor.decideOperation(
                ec("user", "tech_stack", "python"), 1L);

        assertEquals("DISPUTED", d.action());
        assertEquals(10L, d.target().getId());
    }

    @Test
    @DisplayName("同 predicate 但旧 value 缺失 → UPDATE（版本化）")
    void shouldUpdateWhenValueMissing() {
        when(memoryRepository.selectActiveByUserSubjectPredicate(1L, "user", "tech-stack"))
                .thenReturn(existing(10L, "user", "tech-stack", null));

        MemoryExtractor.OperationDecision d = extractor.decideOperation(
                ec("user", "tech_stack", "java 21"), 1L);

        assertEquals("UPDATE", d.action());
    }

    @Test
    @DisplayName("subject+predicate 无命中 → ADD（身份查询未命中，余弦回退也无目标）")
    void shouldAddWhenNoIdentityMatch() {
        when(memoryRepository.selectActiveByUserSubjectPredicate(1L, "user", "tech-stack"))
                .thenReturn(null);
        when(vectorStore.search(anyLong(), any(float[].class), anyInt()))
                .thenReturn(java.util.Collections.emptyList());

        MemoryExtractor.OperationDecision d = extractor.decideOperation(
                ec("user", "tech_stack", "java 17"), 1L);

        assertEquals("ADD", d.action());
    }
}
