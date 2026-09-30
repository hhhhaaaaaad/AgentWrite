package cn.sutone.ai.domain.agent.adapter.repository;

import cn.sutone.ai.domain.agent.model.valobj.MemorySearchOptions;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;

import java.util.List;

public interface IMemoryVectorStore {

    /** 幂等 upsert（新增与更新统一入口，Qdrant PUT 幂等） */
    void upsert(Long memoryId, Long userId, float[] embedding, String content, String contentHash);

    List<ScoredMemory> search(Long userId, float[] queryEmbedding, int topK);

    /** 带选项检索（exact / hnsw_ef 透传，Simple 实现忽略选项做暴力 cosine） */
    List<ScoredMemory> search(Long userId, float[] queryEmbedding, int topK, MemorySearchOptions options);

    void delete(Long memoryId);

    /** 评测 reset：按 userId 删除全部向量（Simple 遍历删匹配项，Qdrant 用 filter 删除） */
    void removeByUserId(Long userId);

    float[] getVector(Long memoryId);
}
