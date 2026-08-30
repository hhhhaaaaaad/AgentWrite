package cn.sutone.ai.api.dto.aiwriting.intent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 写作意图预检请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrecheckAiTaskRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long draftId;
    private String taskType;
    /**
     * 提示参数：customInstruction / selectedText / formatInstruction 均在此 Map 中。
     * 其中 customInstruction、formatInstruction 参与 promptHash，selectedText 不参与。
     */
    private Map<String, Object> promptParams;
    private Boolean enableIllustration;
}
