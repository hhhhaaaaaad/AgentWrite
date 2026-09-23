package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.infrastructure.dao.IMemoryRecordDao;
import cn.sutone.ai.infrastructure.dao.po.MemoryRecordPO;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储实现
 * 使用 ConcurrentHashMap 存储 embedding，支持 cosine 相似度搜索
 * 启动时通过 warm-up 从 MySQL 加载已有记忆
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "memory.vector-store", havingValue = "memory", matchIfMissing = true)
public class SimpleMemoryVectorStore implements IMemoryVectorStore {

    private final ConcurrentHashMap<Long, VectorEntry> store = new ConcurrentHashMap<>();

    @Resource
    private IMemoryRecordDao memoryRecordDao;

    @Resource
    private IMemoryEmbeddingClient embeddingClient;

    /**
     * 启动时从 MySQL 加载所有活跃记忆，批量 embed 后写入内存
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        try {
            List<MemoryRecordPO> all = memoryRecordDao.selectAllActive();
            if (all == null || all.isEmpty()) {
                log.info("记忆向量库 warm-up: 无记忆需加载");
                return;
            }
            for (List<MemoryRecordPO> batch : Lists.partition(all, 50)) {
                List<String> texts = batch.stream().map(MemoryRecordPO::getContent).toList();
                List<float[]> embeddings = embeddingClient.embedBatch(texts);
                for (int i = 0; i < batch.size(); i++) {
                    MemoryRecordPO po = batch.get(i);
                    float[] embedding = embeddings.get(i);
                    if (embedding.length > 0) {
                        store.put(po.getId(), new VectorEntry(po.getUserId(), embedding, po.getContent(), po.getContentHash()));
                    }
                }
            }
            log.info("记忆向量库 warm-up 完成，加载 {} 条记忆", store.size());
        } catch (Exception e) {
            log.error("记忆向量库 warm-up 失败: {}", e.getMessage(), e);
        }
    }

    /** 幂等 upsert（新增与更新统一入口） */
    @Override
    public void upsert(Long memoryId, Long userId, float[] embedding, String content, String contentHash) {
        store.put(memoryId, new VectorEntry(userId, embedding, content, contentHash));
    }

    /** 语义搜索：返回 cosine 相似度最高的 topK 条记忆 */
    @Override
    public List<ScoredMemory> search(Long userId, float[] queryEmbedding, int topK) {
        PriorityQueue<ScoredMemory> pq = new PriorityQueue<>(topK, Comparator.comparingDouble(ScoredMemory::score));

        for (Map.Entry<Long, VectorEntry> entry : store.entrySet()) {
            VectorEntry ve = entry.getValue();
            if (!ve.userId().equals(userId)) continue;

            double score = cosine(queryEmbedding, ve.embedding());
            if (pq.size() < topK) {
                pq.offer(new ScoredMemory(entry.getKey(), ve.content(), score, null, null, ve.contentHash()));
            } else if (score > pq.peek().score()) {
                pq.poll();
                pq.offer(new ScoredMemory(entry.getKey(), ve.content(), score, null, null, ve.contentHash()));
            }
        }

        List<ScoredMemory> result = new ArrayList<>(pq);
        result.sort((a, b) -> Double.compare(b.score(), a.score()));
        return result;
    }

    @Override
    public void delete(Long memoryId) {
        store.remove(memoryId);
    }

    @Override
    public float[] getVector(Long memoryId) {
        VectorEntry entry = store.get(memoryId);
        return entry != null ? entry.embedding() : null;
    }

    public void loadEntry(Long memoryId, Long userId, float[] embedding, String content, String contentHash) {
        store.put(memoryId, new VectorEntry(userId, embedding, content, contentHash));
    }

    public int size() {
        return store.size();
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
    }

    private record VectorEntry(Long userId, float[] embedding, String content, String contentHash) {
    }
}
