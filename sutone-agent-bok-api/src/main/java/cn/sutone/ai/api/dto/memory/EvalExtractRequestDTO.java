package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 评测抽取请求（/api/v1/eval/extract）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalExtractRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;

    /** 对话消息，[{role: user/assistant, content: ...}] */
    private List<Map<String, String>> messages;
}
