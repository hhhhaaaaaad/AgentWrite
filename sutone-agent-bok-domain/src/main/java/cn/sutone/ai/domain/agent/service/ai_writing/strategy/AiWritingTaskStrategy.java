package cn.sutone.ai.domain.agent.service.ai_writing.strategy;

import cn.sutone.ai.domain.agent.model.valobj.MarkdownPolicyVO;

/**
 * AI 写作任务执行策略。
 */
public record AiWritingTaskStrategy(
        boolean useReviewer,
        MarkdownPolicyVO markdownPolicy,
        boolean enableIllustration
) {
}
