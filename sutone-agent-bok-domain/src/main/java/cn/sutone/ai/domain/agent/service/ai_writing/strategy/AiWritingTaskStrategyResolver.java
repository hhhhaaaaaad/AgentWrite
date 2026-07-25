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
            case GENERATE_OUTLINE -> strategy(false, MarkdownPolicyVO.OUTLINE_LIGHT, false);
            case GENERATE_BODY -> strategy(true, MarkdownPolicyVO.ARTICLE_STRICT, task.getEnableIllustration());
            case POLISH_TEXT -> resolvePolishText(task);
            case SUMMARIZE -> strategy(false, MarkdownPolicyVO.PLAIN_TEXT, false);
            case GENERATE_TITLE -> strategy(false, MarkdownPolicyVO.PLAIN_LINES, false);
            case GENERATE_TAGS -> strategy(false, MarkdownPolicyVO.TAGS, false);
            case QUALITY_CHECK -> strategy(false, MarkdownPolicyVO.REPORT_LIGHT, false);
        };
    }

    private AiWritingTaskStrategy resolvePolishText(AiTaskEntity task) {
        if (hasSelectedText(task)) {
            return strategy(false, MarkdownPolicyVO.INLINE_LIGHT, false);
        }
        // 全文润色输出的是完整文章，需要 AST 级块结构治理
        return strategy(true, MarkdownPolicyVO.ARTICLE_STRICT, task.getEnableIllustration());
    }

    private boolean hasSelectedText(AiTaskEntity task) {
        String promptPayload = task.getPromptPayload();
        return null != promptPayload && promptPayload.contains("【处理范围】选中文本");
    }

    private AiWritingTaskStrategy strategy(boolean useReviewer, MarkdownPolicyVO markdownPolicy, Boolean enableIllustration) {
        return new AiWritingTaskStrategy(useReviewer, markdownPolicy, Boolean.TRUE.equals(enableIllustration));
    }
}
