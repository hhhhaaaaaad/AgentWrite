package cn.sutone.ai.domain.agent.adapter.repository;

import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;

import java.util.List;

public interface IMemoryVectorStore {

    /** 幂等 upsert（新增与更新统一入口，Qdrant PUT 幂等） */
    void upsert(Long memoryId, Long userId, float[] embedding, String content, String contentHash);

    List<ScoredMemory> search(Long userId, float[] queryEmbedding, int topK);

    void delete(Long memoryId);

    float[] getVector(Long memoryId);
}
