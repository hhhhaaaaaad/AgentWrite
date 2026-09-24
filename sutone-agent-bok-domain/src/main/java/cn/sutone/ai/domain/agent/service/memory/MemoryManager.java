package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.exception.MemoryAccessDeniedException;
import cn.sutone.ai.domain.agent.model.valobj.EmbeddedMemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.service.memory.circuit.MemoryCircuitBreaker;
import cn.sutone.ai.domain.agent.service.memory.trace.MemoryTraceId;
import cn.sutone.ai.domain.content.adapter.repository.IArticleRepository;
import cn.sutone.ai.domain.content.model.entity.ArticleEntity;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 记忆管理器 — V3 Pipeline 编排门面
 * 对外暴露记忆系统的核心 API：add / search / CRUD
 */
@Slf4j
@Service
public class MemoryManager {

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

    @Resource
    private MemoryPersistService memoryPersistService;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private IMemoryMetricsPort metrics;

    @Resource
    private MemoryCircuitBreaker memoryCircuitBreaker;

    @Resource
    private IArticleRepository articleRepository;

    /**
     * 异步写入记忆 — V3 完整 Pipeline（8 阶段）
     * 触发时机：用户保存文章后
     */
    @Async("memoryExecutor")
    public void addAsync(Long userId, Long agentId, String sessionId,
                         List<Map<String, String>> messages) {
        addAsync(userId, agentId, sessionId, null, messages);
    }

    /**
     * 异步写入记忆（带来源文章）— 落库时快照来源文章标题与摘要，注入时建立跨文章关联
     *
     * @param articleId 来源文章 id，为空则不快照来源（3 字段保持 null）
     */
    @Async("memoryExecutor")
    public void addAsync(Long userId, Long agentId, String sessionId, Long articleId,
                         List<Map<String, String>> messages) {
        String traceId = MemoryTraceId.next();  // P0 埋点：本线程 MDC 关联 trace_id
        try {
            this.add(userId, agentId, sessionId, articleId, messages, traceId);
        } catch (Exception e) {
            log.error("记忆抽取失败 userId={} sessionId={}: {}", userId, sessionId, e.getMessage(), e);
        } finally {
            MemoryTraceId.clear();
        }
    }

    /** V3 Pipeline 主流程（计时包装，端到端抽取存储耗时记 pipeline.duration） */
    private void add(Long userId, Long agentId, String sessionId, Long articleId,
                     List<Map<String, String>> messages, String traceId) {
        long start = System.nanoTime();
        try {
            doAdd(userId, agentId, sessionId, articleId, messages, traceId);
        } finally {
            metrics.recordPipelineDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private void doAdd(Long userId, Long agentId, String sessionId, Long articleId,
                       List<Map<String, String>> messages, String traceId) {
        if (messages == null || messages.isEmpty()) {
            log.info("记忆抽取跳过: 无消息内容");
            return;
        }

        log.info("记忆抽取开始: userId={}, sessionId={}, msgCount={}", userId, sessionId, messages.size());

        // Phase 0.5: 来源文章快照（articleId 非空时查询一次，标题 + 截取原文前 100 字作摘要）
        String sourceArticleTitle = null;
        String sourceArticleSummary = null;
        if (articleId != null) {
            ArticleEntity article = resolveArticle(articleId);
            if (article != null) {
                sourceArticleTitle = article.getTitle();
                sourceArticleSummary = truncateSummary(article.getContentMd(), 100);
            }
        }

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
            Set<Long> ids = existingScored.stream()
                    .map(ScoredMemory::id)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            existingMemories = existingScored.stream()
                    .map(s -> memoryRepository.queryById(s.id()))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
            // P1-5: 关键词补召回（精确版本号/类名/缩写），ID 去重后合并
            List<MemoryRecordEntity> keywordResults = memoryRepository.fulltextSearch(userId, combinedText, 10);
            for (MemoryRecordEntity k : (keywordResults != null ? keywordResults : Collections.<MemoryRecordEntity>emptyList())) {
                if (ids.add(k.getId())) {
                    existingMemories.add(k);
                }
            }
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

        // Phase 3+4: 先算 hash 去重，再对存活候选 embed（候选/hash/向量绑定，禁止下标错位）
        Set<String> existingHashes = existingMemories.stream()
                .map(MemoryRecordEntity::getContentHash)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> batchHashes = new HashSet<>();
        List<MemoryCandidate> survivors = new ArrayList<>();
        Map<MemoryCandidate, String> hashByCandidate = new LinkedHashMap<>();
        for (MemoryCandidate c : candidates) {
            String hash = DigestUtils.md5Hex(c.content());
            if (existingHashes.contains(hash) || !batchHashes.add(hash)) {
                log.debug("跳过重复记忆(hash): {}", c.content().length() > 30 ? c.content().substring(0, 30) : c.content());
                continue;
            }
            survivors.add(c);
            hashByCandidate.put(c, hash);
        }

        if (survivors.isEmpty()) return;

        List<float[]> embs = embeddingClient.embedBatch(survivors.stream().map(MemoryCandidate::content).toList());
        List<EmbeddedMemoryCandidate> embedded = new ArrayList<>(survivors.size());
        for (int i = 0; i < survivors.size(); i++) {
            MemoryCandidate c = survivors.get(i);
            float[] e = i < embs.size() ? embs.get(i) : new float[0];
            embedded.add(new EmbeddedMemoryCandidate(c, hashByCandidate.get(c), e));
        }

        // Phase 5: 身份化 UPDATE 判定（subject+predicate 优先，余弦回退），产出 ADD / UPDATE / DISPUTED 三路
        List<MemoryRecordEntity> toInsert = new ArrayList<>();
        List<MemoryPersistService.UpdatePlan> updates = new ArrayList<>();
        List<MemoryRecordEntity> toDispute = new ArrayList<>();
        for (EmbeddedMemoryCandidate ec : embedded) {
            MemoryExtractor.OperationDecision decision = memoryExtractor.decideOperation(ec, userId);
            switch (decision.action()) {
                case "NOOP" -> log.debug("记忆幂等跳过(同 subject+predicate+value): {}", ec.content());
                case "UPDATE" -> {
                    MemoryRecordEntity target = decision.target();
                    MemoryRecordEntity newRecord = buildRecord(ec, userId, sessionId, traceId, articleId, sourceArticleTitle, sourceArticleSummary);
                    newRecord.setOperation("UPDATE");
                    newRecord.setVersion(target.getVersion() != null ? target.getVersion() + 1 : 2);
                    newRecord.setValidFrom(LocalDateTime.now());
                    updates.add(new MemoryPersistService.UpdatePlan(target.getId(), target.getContent(), newRecord));
                }
                case "DELETE" -> {
                    MemoryRecordEntity target = decision.target();
                    updates.add(new MemoryPersistService.UpdatePlan(target.getId(), target.getContent(), null));
                }
                case "DISPUTED" -> {
                    MemoryRecordEntity target = decision.target();
                    MemoryRecordEntity disputed = buildRecord(ec, userId, sessionId, traceId, articleId, sourceArticleTitle, sourceArticleSummary);
                    disputed.setStatus(MemoryStatus.DISPUTED);
                    disputed.setOperation("UPDATE");
                    disputed.setVersion(target.getVersion() != null ? target.getVersion() + 1 : 2);
                    disputed.setValidFrom(LocalDateTime.now());
                    toDispute.add(disputed);
                }
                default -> toInsert.add(buildRecord(ec, userId, sessionId, traceId, articleId, sourceArticleTitle, sourceArticleSummary));
            }
        }

        // Phase 7: 事务内批量持久化（MySQL 权威 + vector_status=PENDING，向量由 MemoryVectorSyncJob 异步同步）
        memoryPersistService.persistSurvivors(userId, sessionId, traceId, toInsert, updates, toDispute);
        // persistSurvivors 是 @Transactional 边界，返回即事务已提交；此处 bump 处于「提交后」，Redis 失败不回滚 MySQL
        bumpMemoryVersion(userId);
        log.info("记忆抽取完成: userId={}, sessionId={}, traceId={}, insert={}, update={}, disputed={}",
                userId, sessionId, traceId, toInsert.size(), updates.size(), toDispute.size());
    }

    /** 从候选构建带结构化字段的记忆实体（status=ACTIVE、version=1、valid_from=now，由 create 设置） */
    private MemoryRecordEntity buildRecord(EmbeddedMemoryCandidate ec, Long userId, String sessionId, String traceId,
                                           Long articleId, String sourceArticleTitle, String sourceArticleSummary) {
        MemoryRecordEntity record = MemoryRecordEntity.create(null, userId, ec.type(), ec.content(), ec.contentHash(), sessionId);
        record.setContentTokenized(ec.content());
        record.setSubject(PredicateNormalizer.normalize(ec.candidate().subject()));
        record.setPredicate(PredicateNormalizer.normalize(ec.candidate().predicate()));
        record.setValue(ec.candidate().value());
        record.setAttributedTo(ec.candidate().attributedTo());
        record.setConfidence(ec.candidate().confidence());
        record.setEvidence(ec.candidate().evidence());
        record.setTraceId(traceId);
        record.setSourceArticleId(articleId);
        record.setSourceArticleTitle(sourceArticleTitle);
        record.setSourceArticleSummary(sourceArticleSummary);
        return record;
    }

    /** 混合检索 */
    public List<MemoryRetriever.MemoryItem> search(Long userId, String query, int topK) {
        return memoryRetriever.search(userId, query, topK);
    }

    /** 为 Agent prompt 格式化记忆上下文 */
    public String retrieveContext(Long userId, String queryContext, int topK) {
        if (memoryCircuitBreaker.isDegraded()) {
            return "";
        }
        if (queryContext == null || queryContext.isBlank()) {
            return "";
        }
        return memoryRetriever.retrieveFormattedContext(userId, queryContext, topK);
    }

    /** 为 Agent prompt 格式化记忆上下文（结构化查询） */
    public String retrieveContext(Long userId, MemoryRetrieveQueryVO query, int topK) {
        if (memoryCircuitBreaker.isDegraded()) {
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
                null, userId, type.getCode(), content, hash, "eval-seed");
        record.setContentTokenized(content);
        Long id = memoryRepository.insert(record);
        try {
            float[] emb = embeddingClient.embed(content);
            if (emb.length > 0) {
                vectorStore.upsert(id, userId, emb, content, hash);
                memoryRepository.updateVectorStatus(id, "SYNCED");
            }
        } catch (Exception e) {
            log.warn("addDirect vector upsert failed id={}, 标记 PENDING: {}", id, e.getMessage());
            memoryRepository.updateVectorStatus(id, "PENDING");
        }
        bumpMemoryVersion(userId);
    }

    /** 逻辑删除记忆 */
    public void delete(Long memoryId) {
        MemoryRecordEntity record = memoryRepository.queryById(memoryId);
        memoryRepository.deleteById(memoryId);
        vectorStore.delete(memoryId);
        if (record != null) {
            bumpMemoryVersion(record.getUserId());
        }
    }

    /** 带用户归属校验的逻辑删除（IDOR 防护，消除 Controller 先查后删的 TOCTOU 窗口） */
    public void delete(Long userId, Long memoryId) {
        MemoryRecordEntity record = memoryRepository.queryById(memoryId);
        if (record == null || !userId.equals(record.getUserId())) {
            throw new MemoryAccessDeniedException("无权删除记忆: memoryId=" + memoryId);
        }
        memoryRepository.deleteById(memoryId);
        vectorStore.delete(memoryId);
        bumpMemoryVersion(userId);
    }

    /**
     * 递增用户记忆版本（P2-5 缓存失效）。
     *
     * <p>搜索/画像缓存 key 拼接此版本，写入后 INCR 即令旧缓存 key 失配自然失效。
     * Redis 异常仅影响缓存失效，不回滚已落库数据，故内部吞异常。</p>
     */
    private void bumpMemoryVersion(Long userId) {
        try {
            redisTemplate.opsForValue().increment("memory:user:" + userId + ":version");
        } catch (Exception e) {
            log.warn("memoryVersion INCR 失败 userId={}: {}", userId, e.getMessage());
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
                        vectorStore.upsert(r.getId(), r.getUserId(), emb, r.getContent(), r.getContentHash());
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

    /** 查询来源文章（articleId 为空或查询失败返回 null，不影响记忆落库） */
    private ArticleEntity resolveArticle(Long articleId) {
        if (articleId == null) {
            return null;
        }
        try {
            return articleRepository.queryArticleById(articleId);
        } catch (Exception e) {
            log.warn("查询来源文章失败 articleId={}: {}", articleId, e.getMessage());
            return null;
        }
    }

    /** 截取原文前 {@code maxLen} 字（去换行）作为来源一句话摘要 */
    private String truncateSummary(String contentMd, int maxLen) {
        if (contentMd == null || contentMd.isBlank()) {
            return null;
        }
        String normalized = contentMd.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() > maxLen ? normalized.substring(0, maxLen) : normalized;
    }
}
