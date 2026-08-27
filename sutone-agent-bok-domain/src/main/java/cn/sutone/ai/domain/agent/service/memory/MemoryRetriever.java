package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.adapter.repository.IRerankerClient;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.NormalizedMemoryQueryVO;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 记忆检索器 — 完整混合检索 pipeline
 * 语义搜索 + BM25 关键词搜索 + 时间衰减 + 重要性加权 = 融合评分
 */
@Slf4j
@Component
public class MemoryRetriever {

    private static final double DEFAULT_THRESHOLD = 0.1;
    private static final int DEFAULT_OVER_FETCH_FACTOR = 6;  // 扩大粗排以喂给 Reranker
    private static final int RERANK_TOP_N = 5;
    private static final double BM25_MERGE_THRESHOLD = 0.5;

    @Resource
    private IMemoryEmbeddingClient embeddingClient;

    @Resource
    private IMemoryVectorStore vectorStore;

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private IRerankerClient rerankerClient;

    @Resource
    private MemoryProperties memoryProperties;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private MemoryQueryNormalizer memoryQueryNormalizer;

    /**
     * 混合检索：语义 + BM25 融合
     *
     * @return 按融合分数降序排列的记忆列表
     */
    public List<MemoryItem> search(Long userId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        MemoryRetrieveQueryVO queryVO = MemoryRetrieveQueryVO.builder()
                .taskType("LEGACY")
                .contentMd(query)
                .build();
        return search(userId, queryVO, topK, DEFAULT_THRESHOLD);
    }

    public List<MemoryItem> search(Long userId, String query, int topK, double threshold) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        MemoryRetrieveQueryVO queryVO = MemoryRetrieveQueryVO.builder()
                .taskType("LEGACY")
                .contentMd(query)
                .build();
        return search(userId, queryVO, topK, threshold);
    }

    public List<MemoryItem> search(Long userId, MemoryRetrieveQueryVO query, int topK) {
        return search(userId, query, topK, DEFAULT_THRESHOLD);
    }

    public List<MemoryItem> search(Long userId, MemoryRetrieveQueryVO query, int topK, double threshold) {
        NormalizedMemoryQueryVO normalized = normalizer().normalize(query);
        String semanticQuery = normalized.getSemanticQuery();
        String lexicalQuery = normalized.getLexicalQuery();
        if ((semanticQuery == null || semanticQuery.isBlank()) && (lexicalQuery == null || lexicalQuery.isBlank())) {
            return Collections.emptyList();
        }

        // Step 0: 搜索缓存
        String thresholdToken = String.format(Locale.ROOT, "%.4f", threshold);
        String searchCacheKey = "memory:user:" + userId + ":search:v2:" + normalized.getCacheKeyDigest()
                + ":topK:" + topK + ":threshold:" + thresholdToken;
        try {
            String cached = redisTemplate.opsForValue().get(searchCacheKey);
            if (cached != null) {
                return deserializeItems(cached);
            }
        } catch (Exception e) {
            log.debug("Redis search cache read failed, proceeding without cache");
        }

        // Step 1: embed 查询（可能返回空数组，表示 embedding 不可用）
        float[] queryEmbedding = semanticQuery == null || semanticQuery.isBlank()
                ? new float[0]
                : embeddingClient.embed(semanticQuery);
        boolean hasEmbedding = queryEmbedding.length > 0;

        List<ScoredMemory> semanticResults = Collections.emptyList();
        if (hasEmbedding) {
            int overFetch = Math.max(topK * DEFAULT_OVER_FETCH_FACTOR, 60);
            semanticResults = vectorStore.search(userId, queryEmbedding, overFetch);
        }

        // Step 2: BM25 关键词搜索 + 画像缓存注入
        int overFetch = Math.max(topK * DEFAULT_OVER_FETCH_FACTOR, 60);
        Map<Long, Double> bm25Scores = executeBm25Search(userId, lexicalQuery, overFetch);

        List<ScoredMemory> profileResults = loadProfileCache(userId);

        // Step 3: 评分融合
        boolean hasBm25 = !bm25Scores.isEmpty();
        double maxPossible = hasEmbedding ? (1.0 + (hasBm25 ? 1.0 : 0.0) + 0.3 + 0.2) : 1.0;

        List<MemoryItem> scored = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();

        // 画像记忆排在最前（但给出适当的分数）
        for (ScoredMemory r : profileResults) {
            if (r.content() == null || !seenIds.add(r.id())) continue;
            scored.add(new MemoryItem(r.id(), r.content(), 0.85, r.importance()));
        }

        for (ScoredMemory r : semanticResults) {
            if (r.content() == null || !seenIds.add(r.id())) continue;
            double semanticScore = r.score();
            if (semanticScore < threshold) continue;

            double bm25Score = bm25Scores.getOrDefault(r.id(), 0.0);
            double recencyBoost = computeRecencyBoost(r.lastAccessedAt());
            double importanceBoost = (r.importance() != null ? r.importance() : 0.5) * 0.2;

            double combined = (semanticScore + bm25Score + recencyBoost + importanceBoost) / maxPossible;
            combined = Math.min(combined, 1.0);
            scored.add(new MemoryItem(r.id(), r.content(), combined, r.importance()));
        }

        if (hasBm25) {
            for (Map.Entry<Long, Double> entry : bm25Scores.entrySet()) {
                if (!seenIds.add(entry.getKey())) continue;
                if (!hasEmbedding && entry.getValue() < BM25_MERGE_THRESHOLD) continue;

                MemoryRecordEntity record = memoryRepository.queryById(entry.getKey());
                if (record != null && record.getContent() != null) {
                    double combined = hasEmbedding
                            ? (0.0 + entry.getValue() + 0.0 + 0.0) / maxPossible
                            : entry.getValue();
                    combined = Math.min(combined, 1.0);
                    scored.add(new MemoryItem(record.getId(), record.getContent(), combined, record.getImportance()));
                }
            }
        }

        // Step 4: Reranker 精排
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<MemoryItem> results;
        if (scored.size() > RERANK_TOP_N) {
            List<ScoredMemory> toRerank = scored.stream()
                    .map(item -> new ScoredMemory(item.id(), item.content(), item.score(), item.importance(), null, null))
                    .toList();
            List<ScoredMemory> reranked = rerankerClient.rerank(semanticQuery, toRerank, RERANK_TOP_N);
            results = reranked.stream()
                    .map(r -> new MemoryItem(r.id(), r.content(), r.score(), r.importance()))
                    .collect(Collectors.toList());
        } else {
            results = scored;
        }

        // Step 5: 截取 topK + 异步更新 + 写缓存
        results = results.subList(0, Math.min(topK, results.size()));
        List<Long> hitIds = results.stream().map(MemoryItem::id).toList();
        if (!hitIds.isEmpty()) {
            updateAccessAsync(hitIds);
        }
        try {
            redisTemplate.opsForValue().set(searchCacheKey, serializeItems(results),
                    memoryProperties.getCache().getSearchTtlMinutes(), java.util.concurrent.TimeUnit.MINUTES);
        } catch (Exception e) {
            log.debug("Redis search cache write failed");
        }

        return results;
    }

    /**
     * 为 Agent prompt 格式化记忆上下文（注入用）
     * 使用草稿内容作为查询，搜索最相关的用户记忆
     */
    public String retrieveFormattedContext(Long userId, String queryContext, int topK) {
        if (queryContext == null || queryContext.isBlank()) {
            return "";
        }
        MemoryRetrieveQueryVO queryVO = MemoryRetrieveQueryVO.builder()
                .taskType("LEGACY")
                .contentMd(queryContext)
                .build();
        return retrieveFormattedContext(userId, queryVO, topK);
    }

    public String retrieveFormattedContext(Long userId, MemoryRetrieveQueryVO query, int topK) {
        List<MemoryItem> memories = search(userId, query, topK);
        if (memories.isEmpty()) {
            return "";
        }
        return memories.stream()
                .map(m -> "- " + m.content())
                .collect(Collectors.joining("\n"));
    }

    /** BM25 关键词搜索 + sigmoid 归一化 */
    private Map<Long, Double> executeBm25Search(Long userId, String query, int limit) {
        try {
            List<MemoryRecordEntity> keywordResults = memoryRepository.fulltextSearch(userId, query, limit);
            if (keywordResults == null || keywordResults.isEmpty()) {
                return Collections.emptyMap();
            }
            // 找到最大原始 FULLTEXT 分数用于归一化
            double maxRawScore = keywordResults.stream()
                    .filter(r -> r.getMatchScore() != null && r.getMatchScore() > 0)
                    .mapToDouble(MemoryRecordEntity::getMatchScore)
                    .max().orElse(1.0);
            if (maxRawScore <= 0) maxRawScore = 1.0;

            Map<Long, Double> bm25Scores = new LinkedHashMap<>();
            for (MemoryRecordEntity r : keywordResults) {
                double rawScore = r.getMatchScore() != null ? r.getMatchScore() : 0.0;
                double normalized = sigmoidNormalize(rawScore, maxRawScore);
                bm25Scores.put(r.getId(), normalized);
            }
            return bm25Scores;
        } catch (Exception e) {
            log.warn("BM25 搜索异常: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** BM25 分数 sigmoid 归一化到 [0, 1] */
    private double sigmoidNormalize(double rawScore, double maxScore) {
        if (maxScore <= 0) return 0.0;
        double midpoint = maxScore * 0.5;
        double steepness = 0.6;
        return 1.0 / (1.0 + Math.exp(-steepness * (rawScore - midpoint)));
    }

    /** 时间衰减：最近访问的记忆获得更高 boost */
    private double computeRecencyBoost(LocalDateTime lastAccessedAt) {
        if (lastAccessedAt == null) return 0.0;
        long daysSince = ChronoUnit.DAYS.between(lastAccessedAt, LocalDateTime.now());
        return 0.3 * Math.exp(-0.05 * daysSince);
    }

    /** 更新检索命中记忆的 access_count、last_accessed_at 和动态重要性 */
    private void updateAccessAsync(List<Long> hitIds) {
        try {
            memoryRepository.batchUpdateAccessInfo(hitIds);
            for (Long id : hitIds) {
                updateImportanceAsync(id);
            }
        } catch (Exception e) {
            log.warn("更新记忆 access_info 失败: {}", e.getMessage());
        }
    }

    /** 动态重要性评分 */
    private void updateImportanceAsync(Long memoryId) {
        try {
            MemoryRecordEntity record = memoryRepository.queryById(memoryId);
            if (record == null) return;
            MemoryProperties.Importance config = memoryProperties.getImportance();
            double freqScore = Math.log(record.getAccessCount() + 1 + 1) / Math.log(100);
            double recencyScore;
            if (record.getLastAccessedAt() != null) {
                long days = ChronoUnit.DAYS.between(record.getLastAccessedAt(), LocalDateTime.now());
                recencyScore = Math.exp(-days / 30.0);
            } else {
                recencyScore = 0;
            }
            double importance = config.getBaseWeight() * 0.5
                    + config.getFrequencyWeight() * freqScore
                    + config.getRecencyWeight() * recencyScore;
            importance = Math.max(config.getMin(), Math.min(config.getMax(), importance));
            // 更新到 DB（非关键路径，忽略异常）
            memoryRepository.updateImportance(memoryId, importance);
        } catch (Exception e) {
            log.debug("动态重要性更新跳过 id={}: {}", memoryId, e.getMessage());
        }
    }

    /** 加载画像缓存 */
    private List<ScoredMemory> loadProfileCache(Long userId) {
        try {
            String key = "memory:user:" + userId + ":profile";
            String cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                return deserializeScoredMemories(cached);
            }
            // 缓存未命中：从 DB 加载，用 importance 或 access_count 降级
            int maxItems = memoryProperties.getCache().getProfileMaxItems();
            List<MemoryRecordEntity> hot = memoryRepository.queryByUserId(userId, 0, maxItems);
            if (hot.isEmpty()) return Collections.emptyList();
            List<ScoredMemory> result = hot.stream()
                    .map(r -> new ScoredMemory(r.getId(), r.getContent(), 0.85, r.getImportance(),
                            r.getLastAccessedAt(), r.getContentHash()))
                    .toList();
            try {
                redisTemplate.opsForValue().set(key, serializeScoredMemories(result),
                        memoryProperties.getCache().getProfileTtlMinutes(), java.util.concurrent.TimeUnit.MINUTES);
            } catch (Exception ignored) {}
            return result;
        } catch (Exception e) {
            log.debug("加载画像缓存失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String serializeItems(List<MemoryItem> items) {
        return com.alibaba.fastjson.JSON.toJSONString(items);
    }

    private List<MemoryItem> deserializeItems(String json) {
        return com.alibaba.fastjson.JSON.parseArray(json, MemoryItem.class);
    }

    private String serializeScoredMemories(List<ScoredMemory> items) {
        return com.alibaba.fastjson.JSON.toJSONString(items);
    }

    private List<ScoredMemory> deserializeScoredMemories(String json) {
        return com.alibaba.fastjson.JSON.parseArray(json, ScoredMemory.class);
    }

    /** 对外暴露的记忆检索结果 */
    public record MemoryItem(Long id, String content, double score, Double importance) {
    }

    private MemoryQueryNormalizer normalizer() {
        if (memoryQueryNormalizer == null) {
            memoryQueryNormalizer = new MemoryQueryNormalizer();
        }
        return memoryQueryNormalizer;
    }
}
