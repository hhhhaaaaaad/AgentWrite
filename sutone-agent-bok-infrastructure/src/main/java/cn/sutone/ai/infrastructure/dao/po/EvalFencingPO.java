package cn.sutone.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 评测 fencing 权威态持久化对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalFencingPO {

    private Long evalUserId;
    private Long fencingVersion;
    private String activeRunId;
    private LocalDateTime updatedAt;
}
