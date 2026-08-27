package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.NormalizedMemoryQueryVO;
import cn.sutone.ai.domain.agent.service.memory.MemoryQueryNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryQueryNormalizer 单元测试")
class MemoryQueryNormalizerTest {

    private final MemoryQueryNormalizer normalizer = new MemoryQueryNormalizer();

    @Test
    @DisplayName("生成大纲且正文为空时仍应生成有效语义查询")
    void shouldBuildSemanticQueryWhenBodyIsEmpty() {
        MemoryRetrieveQueryVO raw = MemoryRetrieveQueryVO.builder()
                .taskType("GENERATE_OUTLINE")
                .title("AgentWrite 记忆系统设计")
                .summary("围绕长期记忆与混合检索展开")
                .customInstruction("偏面试表达")
                .contentMd("")
                .build();

        NormalizedMemoryQueryVO normalized = normalizer.normalize(raw);

        assertEquals("OUTLINE", normalized.getQueryMode());
        assertTrue(normalized.getSemanticQuery().contains("文章标题：AgentWrite 记忆系统设计"));
        assertTrue(normalized.getSemanticQuery().contains("用户额外指令：偏面试表达"));
        assertFalse(normalized.getSemanticQuery().isBlank());
    }

    @Test
    @DisplayName("润色选中文本时应优先使用 selectedText")
    void shouldPreferSelectedTextForPolishSelection() {
        MemoryRetrieveQueryVO raw = MemoryRetrieveQueryVO.builder()
                .taskType("POLISH_TEXT")
                .title("混合检索方案")
                .summary("介绍 semantic 和 lexical 双通道")
                .contentMd("这是全文内容，不应该作为首选")
                .selectedText("这是一段需要润色的混合检索介绍")
                .customInstruction("更偏面试表达")
                .build();

        NormalizedMemoryQueryVO normalized = normalizer.normalize(raw);

        assertEquals("POLISH_SELECTION", normalized.getQueryMode());
        assertTrue(normalized.getSemanticQuery().contains("待处理文本：这是一段需要润色的混合检索介绍"));
        assertTrue(normalized.getLexicalQuery().contains("混合检索"));
    }

    @Test
    @DisplayName("词法查询应短于原始长文本并生成稳定摘要键")
    void shouldBuildCompactLexicalQueryAndStableDigest() {
        MemoryRetrieveQueryVO raw = MemoryRetrieveQueryVO.builder()
                .taskType("GENERATE_BODY")
                .title("记忆系统混合检索设计")
                .summary("基于 Qdrant、BM25、Reranker 构建长期记忆检索")
                .contentMd("请基于当前草稿上下文，详细说明记忆系统中的混合检索、BM25 与 Reranker 设计实现，并突出工程权衡。")
                .customInstruction("偏面试表达，突出亮点")
                .build();

        NormalizedMemoryQueryVO normalized1 = normalizer.normalize(raw);
        NormalizedMemoryQueryVO normalized2 = normalizer.normalize(raw);

        assertFalse(normalized1.getLexicalQuery().isBlank());
        assertTrue(normalized1.getLexicalQuery().length() <= 80);
        assertNotEquals(raw.getContentMd(), normalized1.getLexicalQuery());
        assertEquals(normalized1.getCacheKeyDigest(), normalized2.getCacheKeyDigest());
    }

    @Test
    @DisplayName("格式约束变化时应进入 canonicalText 并影响缓存摘要")
    void shouldIncludeFormatInstructionInCanonicalDigest() {
        MemoryRetrieveQueryVO raw1 = MemoryRetrieveQueryVO.builder()
                .taskType("GENERATE_BODY")
                .title("AgentWrite 项目介绍")
                .summary("突出记忆系统亮点")
                .contentMd("请生成一段项目介绍")
                .formatInstruction("使用分点小标题")
                .build();
        MemoryRetrieveQueryVO raw2 = MemoryRetrieveQueryVO.builder()
                .taskType("GENERATE_BODY")
                .title("AgentWrite 项目介绍")
                .summary("突出记忆系统亮点")
                .contentMd("请生成一段项目介绍")
                .formatInstruction("使用 STAR 结构")
                .build();

        NormalizedMemoryQueryVO normalized1 = normalizer.normalize(raw1);
        NormalizedMemoryQueryVO normalized2 = normalizer.normalize(raw2);

        assertTrue(normalized1.getCanonicalText().contains("formatInstruction=使用分点小标题"));
        assertTrue(normalized2.getCanonicalText().contains("formatInstruction=使用 STAR 结构"));
        assertNotEquals(normalized1.getCacheKeyDigest(), normalized2.getCacheKeyDigest());
    }

    @Test
    @DisplayName("仅有任务类型时不应生成可检索 query")
    void shouldReturnBlankQueryWhenOnlyTaskTypeExists() {
        MemoryRetrieveQueryVO raw = MemoryRetrieveQueryVO.builder()
                .taskType("GENERATE_OUTLINE")
                .build();

        NormalizedMemoryQueryVO normalized = normalizer.normalize(raw);

        assertEquals("", normalized.getSemanticQuery());
        assertEquals("", normalized.getLexicalQuery());
    }

    @Test
    @DisplayName("选中文本模式下全文变化不应影响缓存摘要")
    void shouldIgnoreContentMdInDigestWhenSelectedTextPresent() {
        MemoryRetrieveQueryVO raw1 = MemoryRetrieveQueryVO.builder()
                .taskType("POLISH_TEXT")
                .selectedText("请润色这段混合检索介绍")
                .contentMd("全文版本 A")
                .customInstruction("偏面试表达")
                .build();
        MemoryRetrieveQueryVO raw2 = MemoryRetrieveQueryVO.builder()
                .taskType("POLISH_TEXT")
                .selectedText("请润色这段混合检索介绍")
                .contentMd("全文版本 B，其他内容发生变化")
                .customInstruction("偏面试表达")
                .build();

        NormalizedMemoryQueryVO normalized1 = normalizer.normalize(raw1);
        NormalizedMemoryQueryVO normalized2 = normalizer.normalize(raw2);

        assertFalse(normalized1.getCanonicalText().contains("contentSnippet="));
        assertFalse(normalized2.getCanonicalText().contains("contentSnippet="));
        assertEquals(normalized1.getCacheKeyDigest(), normalized2.getCacheKeyDigest());
    }
}
