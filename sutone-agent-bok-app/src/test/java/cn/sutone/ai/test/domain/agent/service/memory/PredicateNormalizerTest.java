package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.service.memory.PredicateNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("PredicateNormalizer 单元测试")
class PredicateNormalizerTest {

    @Test
    @DisplayName("中英同义映射：技术栈 / tech_stack / techStack 归一为同一键")
    void shouldNormalizeTechStackSynonyms() {
        assertEquals("tech-stack", PredicateNormalizer.normalize("技术栈"));
        assertEquals("tech-stack", PredicateNormalizer.normalize("tech_stack"));
        assertEquals("tech-stack", PredicateNormalizer.normalize("techStack"));
        assertEquals("tech-stack", PredicateNormalizer.normalize("techstack"));
    }

    @Test
    @DisplayName("写作偏好同义映射")
    void shouldNormalizePreferredStyleSynonyms() {
        assertEquals("preferred-style", PredicateNormalizer.normalize("写作偏好"));
        assertEquals("preferred-style", PredicateNormalizer.normalize("preferred_style"));
        assertEquals("preferred-style", PredicateNormalizer.normalize("preferredStyle"));
    }

    @Test
    @DisplayName("空格 / 下划线 / 大小写归一化")
    void shouldNormalizeWhitespaceAndCase() {
        assertEquals("tech-stack", PredicateNormalizer.normalize("  Tech_Stack  "));
        assertEquals("role", PredicateNormalizer.normalize("ROLE"));
    }

    @Test
    @DisplayName("无映射谓词做 camelCase 拆分 + 小写 + 连字符")
    void shouldSplitCamelCaseForUnknownPredicate() {
        assertEquals("favorite-food", PredicateNormalizer.normalize("favoriteFood"));
    }

    @Test
    @DisplayName("空值返回 null")
    void shouldReturnNullForBlank() {
        assertNull(PredicateNormalizer.normalize(null));
        assertNull(PredicateNormalizer.normalize("   "));
    }

    @Test
    @DisplayName("value 归一化：小写 + 折叠空白，用于同 predicate 异 value 判等")
    void shouldNormalizeValue() {
        assertEquals("java 17", PredicateNormalizer.normalizeValue(" Java  17 "));
        assertEquals(PredicateNormalizer.normalizeValue("Java 17"),
                PredicateNormalizer.normalizeValue("java  17"));
    }
}
