package cn.sutone.ai.domain.agent.service.ai_writing.strategy;

import cn.sutone.ai.domain.agent.model.valobj.MarkdownPolicyVO;

/**
 * AI 写作任务执行策略。
 *
 * @param agentId           要调用的 Agent ID
 * @param useWorkflow       是否使用多阶段 workflow（true=3阶段, false=单Agent直调）
 * @param markdownPolicy    Markdown 后处理策略
 * @param enableIllustration 是否启用配图（仅 useWorkflow=true 时有效）
 */
public record AiWritingTaskStrategy(
        String agentId,
        boolean useWorkflow,
        MarkdownPolicyVO markdownPolicy,
        boolean enableIllustration
) {

    public static final String WORKFLOW_AGENT_ID = "300002";
    public static final String SINGLE_AGENT_ID = "300005";

    /**
     * 创建 workflow 模式策略（用于 GENERATE_BODY、POLISH_TEXT-全文）。
     */
    public static AiWritingTaskStrategy workflow(MarkdownPolicyVO markdownPolicy, boolean enableIllustration) {
        return new AiWritingTaskStrategy(WORKFLOW_AGENT_ID, true, markdownPolicy, enableIllustration);
    }

    /**
     * 创建单 Agent 模式策略（用于 GENERATE_OUTLINE、SUMMARIZE 等短文本任务）。
     */
    public static AiWritingTaskStrategy singleAgent(MarkdownPolicyVO markdownPolicy) {
        return new AiWritingTaskStrategy(SINGLE_AGENT_ID, false, markdownPolicy, false);
    }
}
