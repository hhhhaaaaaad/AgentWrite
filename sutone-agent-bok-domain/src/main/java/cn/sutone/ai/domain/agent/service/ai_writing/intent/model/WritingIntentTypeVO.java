package cn.sutone.ai.domain.agent.service.ai_writing.intent.model;

import lombok.Getter;

/**
 * 写作意图类型（模型二判输出协议中的 intent 字段）
 */
@Getter
public enum WritingIntentTypeVO {

    WRITE_ARTICLE("WRITE_ARTICLE", "文章写作"),
    CHAT("CHAT", "闲聊"),
    DRAW_DIAGRAM("DRAW_DIAGRAM", "画图"),
    UNKNOWN("UNKNOWN", "未知");

    private final String code;
    private final String desc;

    WritingIntentTypeVO(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static WritingIntentTypeVO fromCode(String code) {
        if (null == code || code.isBlank()) {
            return UNKNOWN;
        }
        for (WritingIntentTypeVO value : values()) {
            if (value.getCode().equalsIgnoreCase(code)) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
