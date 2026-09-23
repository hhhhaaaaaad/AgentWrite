package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.model.valobj.MemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryCandidate / MemoryTypeVO / ScoredMemory 单元测试")
class MemoryTypeAndCandidateTest {

    @Nested
    @DisplayName("MemoryTypeVO")
    class MemoryTypeVOTests {

        @Test
        @DisplayName("fromCode 合法值返回正确枚举")
        void shouldParseValidCodes() {
            assertEquals(MemoryTypeVO.FACT, MemoryTypeVO.fromCode("fact"));
            assertEquals(MemoryTypeVO.PREFERENCE, MemoryTypeVO.fromCode("preference"));
            assertEquals(MemoryTypeVO.KNOWLEDGE, MemoryTypeVO.fromCode("knowledge"));
            assertEquals(MemoryTypeVO.EVENT, MemoryTypeVO.fromCode("event"));
        }

        @Test
        @DisplayName("isValid 正确判断合法/非法值")
        void shouldValidateCorrectly() {
            assertTrue(MemoryTypeVO.isValid("fact"));
            assertTrue(MemoryTypeVO.isValid("preference"));
            assertTrue(MemoryTypeVO.isValid("knowledge"));
            assertTrue(MemoryTypeVO.isValid("event"));
            assertFalse(MemoryTypeVO.isValid("invalid"));
            assertFalse(MemoryTypeVO.isValid(""));
            assertFalse(MemoryTypeVO.isValid(null));
        }

        @Test
        @DisplayName("fromCode 非法值返回默认 FACT")
        void shouldReturnFactForInvalidCode() {
            assertEquals(MemoryTypeVO.FACT, MemoryTypeVO.fromCode("nonexistent"));
        }
    }

    @Nested
    @DisplayName("MemoryCandidate")
    class MemoryCandidateTests {

        @Test
        @DisplayName("创建候选记忆，字段正确")
        void shouldCreateCandidate() {
            MemoryCandidate c = new MemoryCandidate("用户偏好Java", "preference", "user");

            assertEquals("用户偏好Java", c.content());
            assertEquals("preference", c.type());
            assertEquals("user", c.attributedTo());
        }

        @Test
        @DisplayName("attributedTo 为空时正常")
        void shouldAllowNullAttribution() {
            MemoryCandidate c = new MemoryCandidate("test", "fact", null);
            assertNull(c.attributedTo());
        }

        @Test
        @DisplayName("旧三参构造缺省 operation=ADD，其余字段为 null")
        void shouldDefaultOperationAndNullStructuredFields() {
            MemoryCandidate c = new MemoryCandidate("用户偏好Java", "preference", "user");

            assertEquals("ADD", c.operation());
            assertNull(c.targetMemoryId());
            assertNull(c.subject());
            assertNull(c.predicate());
            assertNull(c.value());
            assertNull(c.evidence());
            assertNull(c.confidence());
        }

        @Test
        @DisplayName("全参构造结构化字段正确")
        void shouldSetAllStructuredFields() {
            MemoryCandidate c = new MemoryCandidate(
                    "技术栈 Java 17", "fact", "user", "UPDATE", 42L,
                    "user", "tech_stack", "Java 17", "原文证据", 0.9);

            assertEquals("技术栈 Java 17", c.content());
            assertEquals("fact", c.type());
            assertEquals("user", c.attributedTo());
            assertEquals("UPDATE", c.operation());
            assertEquals(42L, c.targetMemoryId());
            assertEquals("user", c.subject());
            assertEquals("tech_stack", c.predicate());
            assertEquals("Java 17", c.value());
            assertEquals("原文证据", c.evidence());
            assertEquals(0.9, c.confidence(), 0.001);
        }
    }
}
