package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.exception.MemoryVectorStoreException;
import cn.sutone.ai.domain.agent.model.valobj.MemorySearchOptions;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.infrastructure.metrics.MemoryMetrics;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Qdrant 向量数据库实现（REST API）
 * HNSW 索引、持久化、用户隔离过滤
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "memory.vector-store", havingValue = "qdrant")
public class QdrantVectorStore implements IMemoryVectorStore {

    @Resource
    private MemoryProperties memoryProperties;

    @Resource
    private IMemoryEmbeddingClient embeddingClient;

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private MemoryMetrics memoryMetrics;

    private final RestTemplate rest = new RestTemplate();

    private String baseUrl;
    private String collectionName;

    @PostConstruct
    public void init() {
        this.baseUrl = memoryProperties.getQdrant().getUrl();
        this.collectionName = memoryProperties.getQdrant().getCollection();

        // 维度探测：避免硬编码
        int vectorSize;
        if (memoryProperties.getQdrant().getVectorSize() != null) {
            vectorSize = memoryProperties.getQdrant().getVectorSize();
        } else {
            float[] probe = embeddingClient.embed("dimension probe");
            vectorSize = probe.length;
        }
        log.info("Qdrant 初始化: url={}, collection={}, dim={}", baseUrl, collectionName, vectorSize);

        // 连接重试：Qdrant 容器可能启动慢于 app
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            try {
                createCollectionIfNotExists(vectorSize);
                log.info("Qdrant Collection 就绪");
                break;
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    log.error("Qdrant 连接失败，已重试 {} 次", maxRetries, e);
                    throw new RuntimeException("Qdrant init failed", e);
                }
                log.warn("Qdrant 连接重试 {}/{}", i + 1, maxRetries);
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void createCollectionIfNotExists(int vectorSize) {
        String url = baseUrl + "/collections/" + collectionName;
        // GET 检查是否已存在（不抛异常说明存在）
        try {
            rest.getForEntity(url, String.class);
            return;
        } catch (Exception ignored) {}

        Map<String, Object> body = Map.of(
                "vectors", Map.of("size", vectorSize, "distance", "Cosine")
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    @Override
    public void upsert(Long memoryId, Long userId, float[] embedding, String content, String contentHash) {
        try {
            String url = pointWriteUrl("/points");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user_id", userId);
            payload.put("content", content);
            payload.put("content_hash", contentHash);

            // P2-1: payload 作为「召回加速缓存」，补充权威元数据（MySQL 仍是权威，此处仅加速粗排 recency/importance）
            MemoryRecordEntity meta = memoryRepository.queryById(memoryId);
            if (meta != null) {
                putIfNotNull(payload, "type", meta.getType() != null ? meta.getType().getCode() : null);
                putIfNotNull(payload, "importance", meta.getImportance());
                putIfNotNull(payload, "last_accessed_at", meta.getLastAccessedAt() != null ? meta.getLastAccessedAt().toString() : null);
                putIfNotNull(payload, "expire_time", meta.getExpireTime() != null ? meta.getExpireTime().toString() : null);
                putIfNotNull(payload, "attributed_to", meta.getAttributedTo());
                putIfNotNull(payload, "subject", meta.getSubject());
                putIfNotNull(payload, "predicate", meta.getPredicate());
                putIfNotNull(payload, "value", meta.getValue());
                putIfNotNull(payload, "confidence", meta.getConfidence());
                putIfNotNull(payload, "version", meta.getVersion());
            }

            Map<String, Object> point = Map.of(
                    "id", memoryId,
                    "vector", (Object) embedding,
                    "payload", payload
            );
            Map<String, Object> body = Map.of("points", List.of(point));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp =
                    rest.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
            requireApplied("upsert", resp.getBody());
        } catch (Exception e) {
            log.error("Qdrant upsert failed id={}: {}", memoryId, e.getMessage());
            memoryMetrics.incrementQdrantFailure("upsert");
            throw new MemoryVectorStoreException("qdrant upsert failed id=" + memoryId, e);
        }
    }

    /** 仅写入非 null 值，避免向 Qdrant payload 传入 null 字段 */
    private static void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    /** 解析 ISO-8601 时间字符串，失败返回 null */
    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<ScoredMemory> search(Long userId, float[] queryEmbedding, int topK) {
        return search(userId, queryEmbedding, topK, MemorySearchOptions.defaults());
    }

    @Override
    public List<ScoredMemory> search(Long userId, float[] queryEmbedding, int topK, MemorySearchOptions options) {
        try {
            String url = baseUrl + "/collections/" + collectionName + "/points/search";
            Map<String, Object> mustFilter = Map.of(
                    "key", "user_id",
                    "match", Map.of("value", userId)
            );
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryEmbedding);
            body.put("limit", topK);
            body.put("with_payload", true);
            body.put("filter", Map.of("must", List.of(mustFilter)));
            if (options != null && options.hasParams()) {
                Map<String, Object> searchParams = new LinkedHashMap<>();
                if (options.exact() != null) {
                    searchParams.put("exact", options.exact());
                }
                if (options.hnswEf() != null) {
                    searchParams.put("hnsw_ef", options.hnswEf());
                }
                body.put("search_params", searchParams);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

            JSONObject json = JSON.parseObject(resp.getBody());
            JSONArray results = json.getJSONArray("result");
            if (results == null || results.isEmpty()) return Collections.emptyList();

            List<ScoredMemory> list = new ArrayList<>();
            for (int i = 0; i < results.size(); i++) {
                JSONObject r = results.getJSONObject(i);
                long id = r.getLongValue("id");
                double score = r.getDoubleValue("score");
                JSONObject payload = r.getJSONObject("payload");
                String content = payload != null ? payload.getString("content") : "";
                String contentHash = payload != null ? payload.getString("content_hash") : "";
                // P2-1: 回填 importance / lastAccessedAt（此前硬编码 null，导致 recency/importance 粗排失效）
                Double importance = payload != null ? payload.getDouble("importance") : null;
                LocalDateTime lastAccessedAt = parseDateTime(payload != null ? payload.getString("last_accessed_at") : null);
                list.add(new ScoredMemory(id, content, score, importance, lastAccessedAt, contentHash));
            }
            return list;
        } catch (Exception e) {
            log.error("Qdrant search failed userId={}: {}", userId, e.getMessage());
            memoryMetrics.incrementQdrantFailure("search");
            return Collections.emptyList();
        }
    }

    @Override
    public void delete(Long memoryId) {
        try {
            String url = pointWriteUrl("/points/delete");
            Map<String, Object> body = Map.of("points", List.of(memoryId));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp =
                    rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            requireApplied("delete", resp.getBody());
        } catch (Exception e) {
            log.error("Qdrant delete failed id={}: {}", memoryId, e.getMessage());
            memoryMetrics.incrementQdrantFailure("delete");
            throw new MemoryVectorStoreException("qdrant delete failed id=" + memoryId, e);
        }
    }

    @Override
    public void removeByUserId(Long userId) {
        try {
            String url = pointWriteUrl("/points/delete");
            Map<String, Object> mustFilter = Map.of(
                    "key", "user_id",
                    "match", Map.of("value", userId)
            );
            Map<String, Object> body = Map.of("filter", Map.of("must", List.of(mustFilter)));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp =
                    rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            requireApplied("removeByUserId", resp.getBody());
        } catch (Exception e) {
            log.error("Qdrant removeByUserId failed userId={}: {}", userId, e.getMessage());
            memoryMetrics.incrementQdrantFailure("delete_by_user");
            throw new MemoryVectorStoreException("qdrant removeByUserId failed userId=" + userId, e);
        }
    }

    /**
     * 点写操作的 URL。{@code memory.qdrant.wait-for-indexing=true} 时追加 {@code ?wait=true}。
     *
     * <p><b>不缓存这个开关</b>：每次从 {@code memoryProperties} 现读。本类的
     * {@code baseUrl}/{@code collectionName} 是在 {@code init()} 里取一次的（改配置要重启），
     * 但那是部署期常量；这个开关是行为开关，不重复那个坑——同一个项目里已经有过一次
     * 「构造期读一次导致无法按需改变」的教训（{@code MemoryEmbeddingClient.maxContentLength}）。</p>
     */
    private String pointWriteUrl(String path) {
        String url = baseUrl + "/collections/" + collectionName + path;
        return memoryProperties.getQdrant().isWaitForIndexing() ? url + "?wait=true" : url;
    }

    /**
     * 校验写操作**真的被应用**了，而不只是被接受。
     *
     * <p>这三个写方法原先都把响应体丢掉（{@code rest.exchange(..., String.class)} 不看返回值），
     * 于是「已接受」与「已应用」在代码里完全同形——**「写入没生效」这件事从来没有机会被观测到**。
     * Qdrant 的响应里本来就有答案（{@code result.status}），只是没人读。</p>
     *
     * <p>仅在开启 {@code wait-for-indexing} 时校验：未开启时 {@code status} 本就返回
     * {@code acknowledged}，那是**预期行为**不是异常，不该报错。</p>
     *
     * <p>实测（Qdrant 1.19.1）：{@code ?wait=true} → {@code status: completed}；
     * 不带该参数或参数名写错 → {@code acknowledged}，两者耗时可差 55 倍。所以「HTTP 200」
     * 什么都证明不了，{@code status} 才是证据。</p>
     */
    private void requireApplied(String op, String responseBody) {
        if (!memoryProperties.getQdrant().isWaitForIndexing()) {
            return;
        }
        String status;
        try {
            JSONObject result = JSON.parseObject(responseBody).getJSONObject("result");
            status = result != null ? result.getString("status") : null;
        } catch (Exception e) {
            log.warn("Qdrant {} 响应无法解析，不能确认写入是否已应用: {}", op, responseBody);
            return;
        }
        if (!"completed".equals(status)) {
            throw new MemoryVectorStoreException(
                    "qdrant " + op + " 返回 status=" + status + "（开启 wait-for-indexing 时期望 completed）"
                            + "——写入未被确认应用，此刻检索可能看不到它");
        }
    }

    @Override
    @Deprecated
    public float[] getVector(Long memoryId) {
        try {
            String url = baseUrl + "/collections/" + collectionName + "/points/" + memoryId;
            ResponseEntity<String> resp = rest.getForEntity(url, String.class);
            JSONObject json = JSON.parseObject(resp.getBody());
            JSONObject result = json.getJSONObject("result");
            if (result == null) return null;
            JSONArray vector = result.getJSONArray("vector");
            if (vector == null) return null;
            float[] f = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                f[i] = vector.getFloatValue(i);
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
