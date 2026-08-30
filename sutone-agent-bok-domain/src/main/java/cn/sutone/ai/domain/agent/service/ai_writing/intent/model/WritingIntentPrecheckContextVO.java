package cn.sutone.ai.domain.agent.service.ai_writing.intent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预检上下文（任务上下文感知，而非单句分类）
 *
 * <p>同一句话在不同上下文下语义不同，因此预检必须携带草稿正文、任务类型、选中文本等信息。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WritingIntentPrecheckContextVO {

    private Long userId;
    private Long draftId;
    private String taskType;
    private Boolean enableIllustration;

    private String draftTitle;
    private String draftSummary;
    private String draftContent;
    private Integer draftContentLength;

    private String selectedText;
    private Integer selectedTextLength;

    private String customInstruction;
    private String formatInstruction;
}
