package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IRerankerClient;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BGE-Reranker 客户端
 * 调用硅基流动 Rerank API 做 Cross-Encoder 精排，带熔断降级
 */
@Slf4j
@Component
public class RerankerClient implements IRerankerClient {

    @Resource
    private MemoryProperties memoryProperties;

    private RestTemplate rest;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile long circuitOpenUntil = 0;

    /** P2-4: 构造带 connect/read 超时的 RestTemplate（读配置 memory.reranker.timeout） */
    @PostConstruct
    public void init() {
        int timeout = memoryProperties.getReranker().getTimeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.rest = new RestTemplate(factory);
    }

    /**
     * 精排：粗排 top-K → Cross-Encoder → 精排 top-N
     * 连续 3 次失败后熔断 30s，返回粗排结果
     */
    public List<ScoredMemory> rerank(String query, List<ScoredMemory> candidates, int topN) {
        if (candidates == null || candidates.size() <= topN) {
            return candidates;
        }
        if (!memoryProperties.getReranker().isEnabled()) {
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        // 熔断检查
        if (System.currentTimeMillis() < circuitOpenUntil) {
            log.warn("Reranker circuit open, returning coarse top-{}", topN);
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }

        try {
            MemoryProperties.Reranker config = memoryProperties.getReranker();
            String url = config.getBaseUrl() + "/rerank";

            List<String> documents = candidates.stream()
                    .map(ScoredMemory::content)
                    .toList();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", topN);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(config.getApiKey());

            ResponseEntity<String> resp = rest.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(body, headers), String.class);

            JSONObject json = JSON.parseObject(resp.getBody());
            JSONArray results = json.getJSONArray("results");
            if (results == null || results.isEmpty()) {
                return candidates.subList(0, Math.min(topN, candidates.size()));
            }

            List<ScoredMemory> reranked = new ArrayList<>();
            double maxRerankScore = 0;
            for (int i = 0; i < results.size(); i++) {
                JSONObject r = results.getJSONObject(i);
                int idx = r.getIntValue("index");
                double rerankScore = r.getDoubleValue("relevance_score");
                maxRerankScore = Math.max(maxRerankScore, rerankScore);

                if (idx < candidates.size()) {
                    ScoredMemory original = candidates.get(idx);
                    double combinedScore = 0.6 * rerankScore + 0.4 * original.score();
                    reranked.add(new ScoredMemory(original.id(), original.content(),
                            combinedScore, original.importance(), original.lastAccessedAt(), original.contentHash()));
                }
            }
            reranked.sort((a, b) -> Double.compare(b.score(), a.score()));
            failureCount.set(0);
            log.debug("Reranker 精排完成: {}→{}", candidates.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            int fails = failureCount.incrementAndGet();
            log.warn("Reranker 调用失败 ({}): {}", fails, e.getMessage());
            if (fails >= 3) {
                circuitOpenUntil = System.currentTimeMillis() + 30_000;
                log.error("Reranker 熔断 30s");
            }
            return candidates.subList(0, Math.min(topN, candidates.size()));
        }
    }
}
