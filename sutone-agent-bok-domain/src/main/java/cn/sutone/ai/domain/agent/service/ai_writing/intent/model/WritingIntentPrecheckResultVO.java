package cn.sutone.ai.domain.agent.service.ai_writing.intent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预检结果（领域层）
 *
 * <p>PASS / CONFIRM_REQUIRED 时会附带 precheckToken；BLOCK 时 precheckToken 为 null。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingIntentPrecheckResultVO {

    private WritingIntentDecisionVO decision;
    private WritingIntentTypeVO intent;
    private Double confidence;
    private String reason;

    /** CONTINUE_WRITING | SWITCH_TO_CHAT | SWITCH_TO_DRAWIO | ASK_CONFIRM */
    private String suggestedAction;

    /** 预检凭证，PASS / CONFIRM_REQUIRED 时非空 */
    private String precheckToken;
    /** PASS | CONFIRM */
    private String tokenType;
    private Integer tokenExpireSeconds;
}
