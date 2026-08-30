package cn.sutone.ai.api.dto.aiwriting.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 写作意图预检响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrecheckAiTaskResponseDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** PASS | BLOCK | CONFIRM_REQUIRED */
    private String decision;

    /** WRITE_ARTICLE | CHAT | DRAW_DIAGRAM | UNKNOWN */
    private String intent;

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
