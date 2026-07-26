package cn.sutone.ai.domain.agent.service.ai_writing.strategy;

import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingTaskTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.MarkdownPolicyVO;
import org.springframework.stereotype.Component;

/**
 * 根据任务类型解析 AI 写作执行策略。
 */
@Component
public class AiWritingTaskStrategyResolver {

    public AiWritingTaskStrategy resolve(AiTaskEntity task) {
        AiWritingTaskTypeVO taskType = task.getTaskType();
        return switch (taskType) {
            // 短文本任务：单 Agent 直调，不走 3 阶段 workflow
            case GENERATE_OUTLINE  -> AiWritingTaskStrategy.singleAgent(MarkdownPolicyVO.OUTLINE_LIGHT);
            case SUMMARIZE         -> AiWritingTaskStrategy.singleAgent(MarkdownPolicyVO.PLAIN_TEXT);
            case GENERATE_TITLE    -> AiWritingTaskStrategy.singleAgent(MarkdownPolicyVO.PLAIN_LINES);
            case GENERATE_TAGS     -> AiWritingTaskStrategy.singleAgent(MarkdownPolicyVO.TAGS);
            case QUALITY_CHECK     -> AiWritingTaskStrategy.singleAgent(MarkdownPolicyVO.REPORT_LIGHT);

            // 正文类任务：走 3 阶段 workflow（analyst → generator → reviewer）
            case GENERATE_BODY -> AiWritingTaskStrategy.workflow(
                    MarkdownPolicyVO.ARTICLE_STRICT, task.getEnableIllustration());

            case POLISH_TEXT -> resolvePolishText(task);
        };
    }

    private AiWritingTaskStrategy resolvePolishText(AiTaskEntity task) {
        if (hasSelectedText(task)) {
            // 局部润色：短文本，单 Agent 直调
            return AiWritingTaskStrategy.singleAgent(MarkdownPolicyVO.INLINE_LIGHT);
        }
        // 全文润色：输出完整文章，需要 AST 级块结构治理，走 workflow
        return AiWritingTaskStrategy.workflow(
                MarkdownPolicyVO.ARTICLE_STRICT, task.getEnableIllustration());
    }

    private boolean hasSelectedText(AiTaskEntity task) {
        String promptPayload = task.getPromptPayload();
        return null != promptPayload && promptPayload.contains("【处理范围】选中文本");
    }
}
