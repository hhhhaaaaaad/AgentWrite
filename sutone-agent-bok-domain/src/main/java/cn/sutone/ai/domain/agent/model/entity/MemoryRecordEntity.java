package cn.sutone.ai.domain.agent.model.entity;

import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecordEntity {

    private Long id;
    private Long userId;
    private MemoryTypeVO type;
    private String content;
    private String contentHash;
    private String contentTokenized;
    private String sourceSessionId;
    private Double importance;
    private Integer accessCount;
    private LocalDateTime lastAccessedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Double matchScore;
    private String vectorStatus;
    private Integer retryCount;

    /** 来源 user/agent/system */
    private String attributedTo;
    /** 抽取置信度 0-1 */
    private Double confidence;
    /** 记忆有效期 */
    private LocalDateTime expireTime;
    /** 主体，如 user */
    private String subject;
    /** 稳定属性，如 tech_stack / preferred_style */
    private String predicate;
    /** 属性值 */
    private String value;
    /** 证据片段 */
    private String evidence;
    /** memory_trace_id */
    private String traceId;
    /** 落库时的操作（ADD/UPDATE） */
    private String operation;
    /** 版本号，默认 1 */
    private Integer version;
    /** 生命周期状态（P2 治理用，P1 默认 ACTIVE） */
    private MemoryStatus status;
    /** 生效时间 */
    private LocalDateTime validFrom;
    /** 失效时间 */
    private LocalDateTime validTo;
    /** 向量同步下次重试时间 */
    private LocalDateTime nextRetryAt;
    /** 向量同步最后错误 */
    private String lastError;
    /** 来源文章 id（该记忆从哪篇文章抽取） */
    private Long sourceArticleId;
    /** 来源文章标题（快照） */
    private String sourceArticleTitle;
    /** 来源文章一句话摘要（截取原文前 100 字，快照） */
    private String sourceArticleSummary;

    /**
     * P1-6 默认 TTL（单位：天，&lt;=0 表示永久）。
     * 说明：按产品可调，后续可迁移为 {@code memory.ttl.*} 配置项。
     */
    private static final long FACT_TTL_DAYS = -1;         // fact：永久（无 TTL）
    private static final long PREFERENCE_TTL_DAYS = 180;  // preference：衰减期
    private static final long KNOWLEDGE_TTL_DAYS = 365;   // knowledge：版本复审期
    private static final long EVENT_TTL_DAYS = 365;       // event：降权保留期

    public static MemoryRecordEntity create(Long id, Long userId, String type, String content,
                                            String contentHash, String sessionId) {
        MemoryTypeVO typeVO = MemoryTypeVO.fromCode(type);
        return MemoryRecordEntity.builder()
                .id(id)
                .userId(userId)
                .type(typeVO)
                .content(content)
                .contentHash(contentHash)
                .sourceSessionId(sessionId)
                .importance(0.5)
                .accessCount(0)
                .operation("ADD")
                .version(1)
                .status(MemoryStatus.ACTIVE)
                .validFrom(LocalDateTime.now())
                .expireTime(defaultExpireTime(typeVO))
                .build();
    }

    /**
     * 按类型绑定默认有效期（P1-6）：fact 永久、preference 衰减、knowledge 版本复审、event 降权保留。
     *
     * @param type 记忆类型，null 时返回 null（永久）
     * @return 到期时间，永久返回 {@code null}
     */
    public static LocalDateTime defaultExpireTime(MemoryTypeVO type) {
        if (type == null) {
            return null;
        }
        long days;
        switch (type) {
            case PREFERENCE:
                days = PREFERENCE_TTL_DAYS;
                break;
            case KNOWLEDGE:
                days = KNOWLEDGE_TTL_DAYS;
                break;
            case EVENT:
                days = EVENT_TTL_DAYS;
                break;
            case FACT:
            default:
                days = FACT_TTL_DAYS;
                break;
        }
        return days <= 0 ? null : LocalDateTime.now().plusDays(days);
    }
}
