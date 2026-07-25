package cn.sutone.ai.domain.agent.model.valobj;

/**
 * AI 写作 Markdown 后处理策略。
 */
public enum MarkdownPolicyVO {

    NONE,
    PLAIN_TEXT,
    PLAIN_LINES,
    TAGS,
    INLINE_LIGHT,
    OUTLINE_LIGHT,
    ARTICLE_LIGHT,
    ARTICLE_STRICT,
    REPORT_LIGHT
}
