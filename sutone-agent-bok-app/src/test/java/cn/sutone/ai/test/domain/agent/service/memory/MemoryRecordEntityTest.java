package cn.sutone.ai.test.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryRecordEntity 单元测试")
class MemoryRecordEntityTest {

    @Nested
    @DisplayName("create 工厂方法")
    class Create {

        @Test
        @DisplayName("创建记忆时设置默认 importance=0.5 和 accessCount=0")
        void shouldSetDefaults() {
            MemoryRecordEntity record = MemoryRecordEntity.create(1L, 100L, "fact", "用户偏好Java", "md5hash123", "session1");

            assertEquals(1L, record.getId());
            assertEquals(100L, record.getUserId());
            assertEquals(MemoryTypeVO.FACT, record.getType());
            assertEquals("用户偏好Java", record.getContent());
            assertEquals("md5hash123", record.getContentHash());
            assertEquals("session1", record.getSourceSessionId());
            assertEquals(0.5, record.getImportance(), 0.001);
            assertEquals(0, record.getAccessCount());
        }

        @Test
        @DisplayName("不同类型记忆均可正确创建")
        void shouldHandleAllTypes() {
            String[] types = {"fact", "preference", "knowledge", "event"};
            for (String type : types) {
                MemoryRecordEntity record = MemoryRecordEntity.create(1L, 1L, type, type + "内容", "h", "s");
                assertEquals(MemoryTypeVO.fromCode(type), record.getType());
            }
        }
    }

    @Nested
    @DisplayName("Builder")
    class Builder {

        @Test
        @DisplayName("Builder 可设置所有字段")
        void shouldSetAllFieldsViaBuilder() {
            MemoryRecordEntity record = MemoryRecordEntity.builder()
                    .id(42L)
                    .userId(100L)
                    .type(MemoryTypeVO.PREFERENCE)
                    .content("偏好内容")
                    .contentHash("hash")
                    .contentTokenized("tokenized")
                    .sourceSessionId("s1")
                    .importance(0.8)
                    .accessCount(5)
                    .build();

            assertEquals(42L, record.getId());
            assertEquals(0.8, record.getImportance(), 0.001);
            assertEquals(5, record.getAccessCount());
        }

        @Test
        @DisplayName("Builder 可设置结构化与生命周期新字段")
        void shouldSetStructuredFieldsViaBuilder() {
            LocalDateTime expire = LocalDateTime.of(2026, 1, 1, 0, 0);
            MemoryRecordEntity record = MemoryRecordEntity.builder()
                    .id(1L)
                    .userId(100L)
                    .type(MemoryTypeVO.FACT)
                    .attributedTo("user")
                    .confidence(0.9)
                    .expireTime(expire)
                    .subject("user")
                    .predicate("tech_stack")
                    .value("Java 17")
                    .evidence("原文")
                    .traceId("mem-123")
                    .operation("ADD")
                    .version(1)
                    .status(MemoryStatus.ACTIVE)
                    .validFrom(expire)
                    .validTo(expire)
                    .nextRetryAt(expire)
                    .lastError("err")
                    .build();

            assertEquals("user", record.getAttributedTo());
            assertEquals(0.9, record.getConfidence(), 0.001);
            assertEquals(expire, record.getExpireTime());
            assertEquals("user", record.getSubject());
            assertEquals("tech_stack", record.getPredicate());
            assertEquals("Java 17", record.getValue());
            assertEquals("原文", record.getEvidence());
            assertEquals("mem-123", record.getTraceId());
            assertEquals("ADD", record.getOperation());
            assertEquals(1, record.getVersion());
            assertEquals(MemoryStatus.ACTIVE, record.getStatus());
            assertEquals(expire, record.getValidFrom());
            assertEquals(expire, record.getValidTo());
            assertEquals(expire, record.getNextRetryAt());
            assertEquals("err", record.getLastError());
        }
    }
}
