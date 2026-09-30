package cn.sutone.ai.domain.agent.model.valobj;

/**
 * 向量检索选项（评测 exact / HNSW 透传）。
 *
 * <p>{@code exact=null} 走默认近似 HNSW，{@code true} 走精确暴力检索；
 * {@code hnswEf} 为 HNSW ef 参数（越大越准越慢）。两者均 null 时与普通检索等价。</p>
 */
public record MemorySearchOptions(Boolean exact, Integer hnswEf) {

    public static MemorySearchOptions defaults() {
        return new MemorySearchOptions(null, null);
    }

    public boolean hasParams() {
        return exact != null || hnswEf != null;
    }
}
