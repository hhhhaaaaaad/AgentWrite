package cn.sutone.ai.domain.agent.model.valobj;

/**
 * AI 写作 Markdown 后处理策略。
 */
public enum MarkdownPolicyVO {

    /** 不进行任何 Markdown 后处理，原样返回模型输出。 */
    NONE,

    /** 纯文本策略，移除 Markdown 围栏、标题和列表标记，适用于摘要。 */
    PLAIN_TEXT,

    /** 纯文本多行策略，清理格式标记并最多保留五个非空行，适用于候选标题。 */
    PLAIN_LINES,

    /** 标签策略，按常见分隔符拆分、去重并最多保留五个标签。 */
    TAGS,

    /** 行内轻量策略，仅移除对话式前缀和全文 Markdown 围栏，并执行基础格式清理。 */
    INLINE_LIGHT,

    /** 大纲轻量策略，修复常见 Markdown 畸形，但不执行 CommonMark AST 重渲染。 */
    OUTLINE_LIGHT,

    /** 文章轻量策略，修复常见 Markdown 畸形，但不执行 CommonMark AST 重渲染。 */
    ARTICLE_LIGHT,

    /** 文章严格策略，通过 CommonMark AST 解析、结构修正和重渲染生成规范 Markdown。 */
    ARTICLE_STRICT,

    /** 报告轻量策略，修复常见 Markdown 畸形，但不执行 CommonMark AST 重渲染。 */
    REPORT_LIGHT
}
