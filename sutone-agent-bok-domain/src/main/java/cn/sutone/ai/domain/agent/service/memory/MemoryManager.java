package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆管理器 — V3 Pipeline 编排门面
 * 对外暴露记忆系统的核心 API：add / search / CRUD
 */
@Slf4j
@Service
public class MemoryManager {

    @Value("${memory.inject.enabled:true}")
    private boolean injectEnabled;

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private IMemoryVectorStore vectorStore;

    @Resource
    private IMemoryEmbeddingClient embeddingClient;

    @Resource
    private MemoryExtractor memoryExtractor;

    @Resource
    private MemoryRetriever memoryRetriever;

    /**
     * 异步写入记忆 — V3 完整 Pipeline（8 阶段）
     * 触发时机：用户保存文章后
     */
    @Async("memoryExecutor")
    public void addAsync(Long userId, Long agentId, String sessionId,
                         List<Map<String, String>> messages) {
        try {
            this.add(userId, agentId, sessionId, messages);
        } catch (Exception e) {
            log.error("记忆抽取失败 userId={} sessionId={}: {}", userId, sessionId, e.getMessage(), e);
        }
    }

    /** V3 Pipeline 主流程 */
    private void add(Long userId, Long agentId, String sessionId,
                     List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            log.info("记忆抽取跳过: 无消息内容");
            return;
        }

        log.info("记忆抽取开始: userId={}, sessionId={}, msgCount={}", userId, sessionId, messages.size());

        // Phase 0: context collection — get last 15 from chat_message (for LLM context)
        List<String> historyMessages = memoryRepository.getLastMessages(sessionId, 15);

        log.info("记忆抽取开始: userId={}, sessionId={}, msgCount={}",
                userId, sessionId, messages.size());

        // Phase 1: existing memory retrieval — embed all messages → vector search top-10
        String combinedText = messages.stream()
                .map(m -> m.getOrDefault("content", ""))
                .collect(Collectors.joining("\n"));
        log.info("Phase 1: 开始 embedding, textLen={}", combinedText.length());
        float[] queryEmbedding = embeddingClient.embed(combinedText);

        List<MemoryRecordEntity> existingMemories;
        if (queryEmbedding.length > 0) {
            List<ScoredMemory> existingScored = vectorStore.search(userId, queryEmbedding, 10);
            existingMemories = existingScored.stream()
                    .map(s -> memoryRepository.queryById(s.id()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } else {
            // embedding 不可用时，用 BM25 检索已有记忆
            List<MemoryRecordEntity> keywordResults = memoryRepository.fulltextSearch(userId, combinedText, 10);
            existingMemories = keywordResults != null ? keywordResults : Collections.emptyList();
        }

        // Phase 2: LLM 抽取（完整对话历史由前端传入）
        log.info("Phase 2: 开始 LLM 抽取, existingMemories={}, messages={}", existingMemories.size(), messages.size());
        List<MemoryCandidate> candidates = memoryExtractor.extract(existingMemories, messages, historyMessages);
        log.info("Phase 2: LLM 抽取完成, candidates={}", candidates.size());
        if (candidates.isEmpty()) return;

        // Phase 3: 批量 embedding
        List<String> texts = candidates.stream().map(MemoryCandidate::content).toList();
        List<float[]> embeddings = embeddingClient.embedBatch(texts);
        boolean hasEmbeddings = embeddings.stream().anyMatch(e -> e.length > 0);

        // Phase 4: Hash 去重  将第 1 步检索到的topK的前10就转换为set
        Set<String> existingHashes = existingMemories.stream()
                .map(MemoryRecordEntity::getContentHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> batchHashes = new HashSet<>();
        List<MemoryRecordEntity> toProcess = new ArrayList<>();

        for (int i = 0; i < candidates.size(); i++) {
            String text = texts.get(i);
            String hash = DigestUtils.md5Hex(text);
            // 判断是否重复 1. 已存在记忆 2. 批量处理中已存在
            if (existingHashes.contains(hash) || batchHashes.contains(hash)) {
                log.debug("跳过重复记忆(hash): {}", text.length() > 30 ? text.substring(0, 30) : text);
                continue;
            }
            batchHashes.add(hash);
            MemoryRecordEntity record = MemoryRecordEntity.create(
                    memoryRepository.nextId(), userId, candidates.get(i).type(), text, hash, sessionId);
            toProcess.add(record);
        }

        if (toProcess.isEmpty()) return;

        // Phase 5: UPDATE 判定 — 使用向量搜索 top-1 替代逐条 getVector
        List<MemoryRecordEntity> toInsert = new ArrayList<>();
        for (int i = 0; i < toProcess.size(); i++) {
            MemoryRecordEntity record = toProcess.get(i);
            float[] embedding = embeddings.size() > i ? embeddings.get(i) : new float[0];

            if (hasEmbeddings && embedding.length > 0) {
                Long updateTargetId = memoryExtractor.findUpdateTarget(embedding, userId);
                if (updateTargetId != null) {
                    MemoryRecordEntity existing = memoryRepository.queryById(updateTargetId);
                    String oldContent = existing != null ? existing.getContent() : null;
                    String newHash = DigestUtils.md5Hex(record.getContent());
                    memoryRepository.updateContent(updateTargetId, record.getContent(), newHash, null);
                    vectorStore.update(updateTargetId, embedding, record.getContent());
                    memoryRepository.insertHistory(updateTargetId, oldContent, record.getContent(),
                            "UPDATE", sessionId);
                    // 清除画像缓存
                    evictProfileCache(userId);
                    log.debug("记忆 UPDATE: id={}", updateTargetId);
                    continue;
                }
            }
            toInsert.add(record);
        }

        // Phase 6: 词形还原
        for (MemoryRecordEntity record : toInsert) {
            record.setContentTokenized(record.getContent());
        }

        // Phase 7: 批量持久化 — MySQL(vector_status=PENDING) + Qdrant + 标记SYNCED
        log.info("Phase 7: 开始持久化, toInsert={}", toInsert.size());
        for (int i = 0; i < toInsert.size(); i++) {
            MemoryRecordEntity record = toInsert.get(i);
            try {
                // 优先存储 MySQL
                memoryRepository.insert(record);
                if (hasEmbeddings && embeddings.size() > i && embeddings.get(i).length > 0) {
                    try {
                        vectorStore.insert(record.getId(), userId, embeddings.get(i),
                                record.getContent(), record.getContentHash());
                        memoryRepository.updateVectorStatus(record.getId(), "SYNCED");
                    } catch (Exception e) {
                        log.warn("Qdrant 写入失败 id={}, 标记 PENDING: {}", record.getId(), e.getMessage());
                        memoryRepository.updateVectorStatus(record.getId(), "PENDING");
                    }
                }
                memoryRepository.insertHistory(record.getId(), null, record.getContent(),
                        "ADD", sessionId);
                // 清除缓存
                evictProfileCache(userId);
            } catch (Exception e) {
                log.debug("记忆写入跳过: hash={} error={}", record.getContentHash(), e.getMessage());
            }
        }
        log.info("记忆抽取完成: userId={}, sessionId={}, inserted={}", userId, sessionId, toInsert.size());
    }

    /** 混合检索 */
    public List<MemoryRetriever.MemoryItem> search(Long userId, String query, int topK) {
        return memoryRetriever.search(userId, query, topK);
    }

    /** 为 Agent prompt 格式化记忆上下文 */
    public String retrieveContext(Long userId, String queryContext, int topK) {
        if (!injectEnabled) {
            return "";
        }
        if (queryContext == null || queryContext.isBlank()) {
            return "";
        }
        return memoryRetriever.retrieveFormattedContext(userId, queryContext, topK);
    }

    /** 为 Agent prompt 格式化记忆上下文（结构化查询） */
    public String retrieveContext(Long userId, MemoryRetrieveQueryVO query, int topK) {
        if (!injectEnabled) {
            return "";
        }
        if (query == null) {
            return "";
        }
        return memoryRetriever.retrieveFormattedContext(userId, query, topK);
    }

    /** 单条记忆详情 */
    public MemoryRecordEntity get(Long memoryId) {
        return memoryRepository.queryById(memoryId);
    }

    /** 绕过 LLM 抽取直接写入（评测/种子数据用） */
    public void addDirect(Long userId, MemoryTypeVO type, String content) {
        String hash = DigestUtils.md5Hex(content);
        MemoryRecordEntity record = MemoryRecordEntity.create(
                memoryRepository.nextId(), userId, type.getCode(), content, hash, "eval-seed");
        record.setContentTokenized(content);
        memoryRepository.insert(record);
        try {
            float[] emb = embeddingClient.embed(content);
            if (emb.length > 0) {
                vectorStore.insert(record.getId(), userId, emb, content, hash);
                memoryRepository.updateVectorStatus(record.getId(), "SYNCED");
            }
        } catch (Exception e) {
            log.warn("addDirect vector insert failed id={}", record.getId());
        }
    }

    /** 逻辑删除记忆 */
    public void delete(Long memoryId) {
        MemoryRecordEntity record = memoryRepository.queryById(memoryId);
        memoryRepository.deleteById(memoryId);
        vectorStore.delete(memoryId);
        if (record != null) {
            evictProfileCache(record.getUserId());
        }
    }

    /** 画像缓存清除（P2 正式接 Redis 后生效） */
    private void evictProfileCache(Long userId) {
        // P2 实现：redisTemplate.delete("memory:user:" + userId + ":profile")
    }

    /** 补偿任务：每 30s 同步 PENDING 向量到 Qdrant */
    @Scheduled(fixedDelay = 30_000)
    public void syncPendingVectors() {
        List<MemoryRecordEntity> pending = memoryRepository.selectPendingVectors();
        if (pending.isEmpty()) return;
        log.info("补偿任务: 发现 {} 条 PENDING 向量待同步", pending.size());
        for (MemoryRecordEntity p : pending) {
            try {
                float[] emb = embeddingClient.embed(p.getContent());
                if (emb.length > 0) {
                    vectorStore.insert(p.getId(), p.getUserId(), emb, p.getContent(), p.getContentHash());
                    memoryRepository.updateVectorStatus(p.getId(), "SYNCED");
                }
            } catch (Exception e) {
                log.warn("补偿同步失败 id={}, retry={}: {}", p.getId(), p.getAccessCount(), e.getMessage());
                if (p.getAccessCount() != null && p.getAccessCount() >= 3) {
                    memoryRepository.updateVectorStatus(p.getId(), "FAILED");
                }
            }
        }
    }

    /** 全量迁移：将 MySQL 中所有活跃记忆同步到 Qdrant */
    public Map<String, Object> migrateAll(int batchSize, int rateLimit) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<MemoryRecordEntity> all = memoryRepository.selectAllActive();
        int total = all.size();
        int migrated = 0;
        int failed = 0;
        int skipped = 0;
        log.info("全量迁移: total={} records", total);
        for (int i = 0; i < all.size(); i += batchSize) {
            int end = Math.min(i + batchSize, all.size());
            List<MemoryRecordEntity> batch = all.subList(i, end);
            for (MemoryRecordEntity r : batch) {
                try {
                    float[] emb = embeddingClient.embed(r.getContent());
                    if (emb.length > 0) {
                        vectorStore.insert(r.getId(), r.getUserId(), emb, r.getContent(), r.getContentHash());
                        memoryRepository.updateVectorStatus(r.getId(), "SYNCED");
                        migrated++;
                        log.debug("迁移成功: id={}", r.getId());
                    } else {
                        skipped++;
                        log.warn("迁移跳过(embedding返回空): id={}, content={}", r.getId(),
                                r.getContent() != null ? r.getContent().substring(0, Math.min(30, r.getContent().length())) : "null");
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("迁移失败: id={}, error={}", r.getId(), e.getMessage());
                }
            }
            if (end < all.size()) {
                try { Thread.sleep(1000L / rateLimit * batchSize); } catch (InterruptedException ignored) {}
            }
        }
        result.put("status", "DONE");
        result.put("total", total);
        result.put("migrated", migrated);
        result.put("failed", failed);
        result.put("skipped", skipped);
        log.info("全量迁移完成: total={} migrated={} failed={} skipped={}", total, migrated, failed, skipped);
        return result;
    }

    /** 分页记忆列表 */
    public List<MemoryRecordEntity> list(Long userId, int page, int pageSize) {
        int offset = (page - 1) * pageSize;
        return memoryRepository.queryByUserId(userId, offset, pageSize);
    }

    /** 记忆总数 */
    public int count(Long userId) {
        return memoryRepository.countByUserId(userId);
    }
}
