package cn.sutone.ai.domain.agent.service.ai_writing.intent.model;

import lombok.Getter;

/**
 * 意图守卫决策结果
 *
 * <p>{@code UNCERTAIN} 仅规则层内部使用，不会作为最终决策返回给前端；
 * 规则层命中 UNCERTAIN 后进入模型二判，最终收敛为 PASS / BLOCK / CONFIRM_REQUIRED。</p>
 */
@Getter
public enum WritingIntentDecisionVO {

    PASS("PASS", "允许进入写作链路"),
    BLOCK("BLOCK", "明确不适合进入写作链路"),
    CONFIRM_REQUIRED("CONFIRM_REQUIRED", "边界不清，需用户确认"),
    UNCERTAIN("UNCERTAIN", "规则层内部：无法确定，需模型二判");

    private final String code;
    private final String desc;

    WritingIntentDecisionVO(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static WritingIntentDecisionVO fromCode(String code) {
        if (null == code || code.isBlank()) {
            return null;
        }
        for (WritingIntentDecisionVO value : values()) {
            if (value.getCode().equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
