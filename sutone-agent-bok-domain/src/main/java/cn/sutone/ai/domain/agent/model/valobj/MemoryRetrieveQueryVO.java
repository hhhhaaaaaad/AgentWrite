package cn.sutone.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 记忆检索原始查询上下文
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemoryRetrieveQueryVO {

    private String taskType;
    private String title;
    private String summary;
    private String contentMd;
    private String selectedText;
    private String customInstruction;
    private String formatInstruction;
}
