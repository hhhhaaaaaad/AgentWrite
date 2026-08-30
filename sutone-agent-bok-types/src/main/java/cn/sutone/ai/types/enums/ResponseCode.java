package cn.sutone.ai.types.enums;

import lombok.Getter;

@Getter
public enum ResponseCode {

    SUCCESS("0000", "成功"),
    UN_ERROR("0001", "未知失败"),
    ILLEGAL_PARAMETER("0002", "非法参数"),
    NOT_FOUND_METHOD("0003", "不存在的方法"),
    UNAUTHORIZED("0004", "未登录或认证信息已过期"),

    E0001("E0001", "智能体ID不存在"),
    E0002("E0002", "智能体MCP配置不在可加载范围"),

    /** AI 写作意图预检相关 */
    PRECHECK_REQUIRED("0005", "请先完成写作意图预检"),
    PRECHECK_TOKEN_INVALID("0006", "确认信息已过期或不匹配，请重新提交"),
    PRECHECK_RATE_LIMIT("0007", "AI 请求过于频繁，请稍后再试");

    private String code;
    private String info;

    ResponseCode(String code, String info) {
        this.code = code;
        this.info = info;
    }
}
