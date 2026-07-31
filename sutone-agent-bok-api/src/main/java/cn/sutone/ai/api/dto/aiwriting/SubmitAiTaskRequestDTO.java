package cn.sutone.ai.api.dto.aiwriting;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 提交 AI 写作任务请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmitAiTaskRequestDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long draftId;
    private String taskType;
    //customInstruction	用户自定义的额外指令	用户在 UI 输入框中写的补充要求
    //selectedText	用户在编辑器中选中的文本片段	局部润色时，只对选中部分操作
    //formatInstruction	格式硬约束	前端根据场景预设的格式规则
    private Map<String, Object> promptParams;
    private Boolean enableIllustration;
}
