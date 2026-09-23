package cn.sutone.ai.domain.agent.model.valobj;

/**
 * 候选 + hash + embedding 的不可拆分绑定。
 * 去重后文本与向量天然对齐，禁止通过不同列表的相同下标关联文本与向量。
 */
public record EmbeddedMemoryCandidate(
        MemoryCandidate candidate,
        String contentHash,
        float[] embedding) {

    public String content() {
        return candidate.content();
    }

    public String type() {
        return candidate.type();
    }
}
