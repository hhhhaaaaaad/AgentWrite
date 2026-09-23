package cn.sutone.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRecordPO {

    private Long id;
    private Long userId;
    private String type;
    private String content;
    private String contentHash;
    private String contentTokenized;
    private String sourceSessionId;
    private Double importance;
    private Integer accessCount;
    private LocalDateTime lastAccessedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDeleted;
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
    /** 生命周期状态码（MemoryStatus code） */
    private String status;
    /** 生效时间 */
    private LocalDateTime validFrom;
    /** 失效时间 */
    private LocalDateTime validTo;
    /** 向量同步下次重试时间 */
    private LocalDateTime nextRetryAt;
    /** 向量同步最后错误 */
    private String lastError;
}
