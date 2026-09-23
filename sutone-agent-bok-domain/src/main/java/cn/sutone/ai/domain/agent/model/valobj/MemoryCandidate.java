package cn.sutone.ai.domain.agent.model.valobj;

/**
 * 记忆抽取候选（结构化抽取契约）。
 *
 * <p>承载 LLM 抽取出的单条记忆，字段含义：</p>
 * <ul>
 *   <li>{@code content}：面向模型的中文描述</li>
 *   <li>{@code type}：fact / preference / knowledge / event</li>
 *   <li>{@code attributedTo}：user / agent / system</li>
 *   <li>{@code operation}：ADD / UPDATE / DELETE / NOOP（缺省 ADD）</li>
 *   <li>{@code targetMemoryId}：UPDATE / DELETE 指向的旧记忆 id</li>
 *   <li>{@code subject} / {@code predicate} / {@code value}：稳定三元组</li>
 *   <li>{@code evidence}：支撑该记忆的原文片段</li>
 *   <li>{@code confidence}：0-1 抽取置信度</li>
 * </ul>
 */
public record MemoryCandidate(
        String content,
        String type,
        String attributedTo,
        String operation,
        Long targetMemoryId,
        String subject,
        String predicate,
        String value,
        String evidence,
        Double confidence
) {
    /** 兼容旧调用（P0 阶段与测试）：缺省 operation=ADD，其余字段为 null */
    public MemoryCandidate(String content, String type, String attributedTo) {
        this(content, type, attributedTo, "ADD", null, null, null, null, null, null);
    }
}
