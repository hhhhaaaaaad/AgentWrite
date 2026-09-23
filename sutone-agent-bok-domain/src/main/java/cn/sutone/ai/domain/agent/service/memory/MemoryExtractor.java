package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.EmbeddedMemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryCandidate;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.ScoredMemory;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 记忆抽取器
 * 负责调用 LLM 从对话中提取结构化记忆，并判断是否需要 UPDATE 已有记忆
 */
@Slf4j
@Component
public class MemoryExtractor {

    @Resource
    private IMemoryEmbeddingClient embeddingClient;

    @Resource
    private IMemoryVectorStore vectorStore;

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private IMemoryMetricsPort metrics;

    /** 余弦回退阈值（可配 memory.update.similarity-threshold） */
    @Value("${memory.update.similarity-threshold:0.9}")
    private double similarityThreshold = 0.9;

    @Resource
    private cn.sutone.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private MemoryProperties memoryProperties;

    private String systemPrompt;
    private String userPromptTemplate;
    private OpenAiApi chatOpenAiApi;
    private String chatModel;

    private static final Pattern JSON_PATTERN = Pattern.compile("\\{[\\s\\S]*\\}");
    private static final int MAX_LLM_RETRIES = 3;
    private static final long LLM_RETRY_BACKOFF_MS = 2000;

    /** prompt 模板中 System 与 User 两段的分隔标记 */
    private static final String SYSTEM_MARKER = "=====SYSTEM=====";
    private static final String USER_MARKER = "=====USER=====";

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/memory-extraction.txt");
            String promptTemplate = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int systemStart = promptTemplate.indexOf(SYSTEM_MARKER);
            int userStart = promptTemplate.indexOf(USER_MARKER);
            if (systemStart >= 0 && userStart > systemStart) {
                this.systemPrompt = promptTemplate.substring(systemStart + SYSTEM_MARKER.length(), userStart).trim();
                this.userPromptTemplate = promptTemplate.substring(userStart + USER_MARKER.length()).trim();
            } else {
                // 兼容无标记的旧模板：整体作为 User，System 置空
                this.systemPrompt = "";
                this.userPromptTemplate = promptTemplate;
            }
            log.info("记忆抽取 prompt 模板加载完成");
        } catch (IOException e) {
            log.error("加载记忆抽取 prompt 模板失败", e);
            this.systemPrompt = "";
            this.userPromptTemplate = "";
        }

        // 构建独立的 chat OpenAiApi（用 DeepSeek 配置，不用 embedding 的硅基流动）
        try {
            var writingConfig = aiAgentAutoConfigProperties.getTables().get("writingAgent");
            var apiConfig = writingConfig.getModule().getAiApi();
            this.chatOpenAiApi = OpenAiApi.builder()
                    .baseUrl(apiConfig.getBaseUrl())
                    .apiKey(apiConfig.getApiKey())
                    .completionsPath(org.apache.commons.lang3.StringUtils.isNotBlank(apiConfig.getCompletionsPath())
                            ? apiConfig.getCompletionsPath() : "v1/chat/completions")
                    .build();
            this.chatModel = writingConfig.getModule().getChatModel().getModel();
            log.info("MemoryExtractor LLM 初始化完成, model={}", chatModel);
        } catch (Exception e) {
            log.error("MemoryExtractor LLM 初始化失败", e);
        }
    }

    /**
     * 从对话中抽取候选记忆
     *
     * @param existingMemories 已有记忆（传入 LLM 做第一层去重）
     * @param newMessages      新对话消息
     * @param lastMessages     最近上下文（帮助理解代词指代）
     * @return 候选记忆列表
     */
    public List<MemoryCandidate> extract(List<MemoryRecordEntity> existingMemories,
                                         List<Map<String, String>> newMessages,
                                         List<String> lastMessages) {
        // 构建 prompt
        String existingMemoriesStr = existingMemories.stream()
                .map(m -> "{\"id\":\"%d\",\"text\":\"%s\"}".formatted(m.getId(), m.getContent()))
                .collect(Collectors.joining(", ", "[", "]"));

        String lastMessagesStr = lastMessages != null && !lastMessages.isEmpty()
                ? String.join("\n", lastMessages)
                : "（无）";

        String newMessagesStr = newMessages.stream()
                .map(m -> "[%s]: %s".formatted(m.get("role"), m.get("content")))
                .collect(Collectors.joining("\n"));

        String userPrompt = userPromptTemplate
                .replace("{existing_memories}", existingMemoriesStr)
                .replace("{last_messages}", lastMessagesStr)
                .replace("{new_messages}", newMessagesStr);

        // 调用 LLM（System 铁律 + User 数据，拆两条消息）
        String llmResponse = callLlm(systemPrompt, userPrompt);
        if (llmResponse == null || llmResponse.isBlank()) {
            log.info("MemoryExtractor LLM 返回空");
            return Collections.emptyList();
        }
        log.info("MemoryExtractor LLM 原始响应 (前200字符): {}", llmResponse.substring(0, Math.min(200, llmResponse.length())));

        // 防御性 JSON 解析
        return parseResponse(llmResponse);
    }

    /**
     * 判断候选记忆是否应该 UPDATE 而非 ADD（cosine > 0.9）
     * V2: 使用向量搜索 top-1 替代逐条 getVector，Qdrant 下避免 N 次 HTTP 请求
     *
     * @return 应该被更新的已有记忆 ID，null 表示应该新增
     */
    public Long findUpdateTarget(float[] candidateEmbedding, Long userId) {
        List<ScoredMemory> results = vectorStore.search(userId, candidateEmbedding, 1);
        if (results.isEmpty()) return null;
        if (results.get(0).score() > similarityThreshold) {
            return results.get(0).id();
        }
        return null;
    }

    /**
     * 身份化 UPDATE 判定（P1-4），三级决策，替代纯余弦 0.9：
     * <ol>
     *   <li>LLM 显式给 targetMemoryId（UPDATE/DELETE）→ 校验归属 + subject/predicate 一致；</li>
     *   <li>subject+predicate 非空 → 用 (user_id, subject, predicate, status='ACTIVE') 精确查；</li>
     *   <li>回退余弦 findUpdateTarget（阈值可配）。</li>
     * </ol>
     *
     * @param ec     候选（含 candidate + embedding）
     * @param userId 用户 id
     * @return 操作决策，action ∈ {ADD, UPDATE, DELETE, DISPUTED, NOOP}
     */
    public OperationDecision decideOperation(EmbeddedMemoryCandidate ec, Long userId) {
        MemoryCandidate c = ec.candidate();

        // 1) LLM 显式给定目标（校验归属 + subject/predicate 一致）
        if (("UPDATE".equals(c.operation()) || "DELETE".equals(c.operation())) && c.targetMemoryId() != null) {
            MemoryRecordEntity target = memoryRepository.queryById(c.targetMemoryId());
            if (target != null && userId.equals(target.getUserId()) && subjectPredicateMatch(c, target)) {
                return valuePolicy(c, target);
            }
            // 归属 / 身份不符：落到下一级判定
        }

        // 2) subject+predicate 精确身份查询
        String subject = PredicateNormalizer.normalize(c.subject());
        String predicate = PredicateNormalizer.normalize(c.predicate());
        if (subject != null && predicate != null) {
            MemoryRecordEntity existing = memoryRepository.selectActiveByUserSubjectPredicate(userId, subject, predicate);
            if (existing != null) {
                return valuePolicy(c, existing);
            }
        }

        // 3) 回退余弦
        if (ec.embedding().length > 0) {
            Long targetId = findUpdateTarget(ec.embedding(), userId);
            if (targetId != null) {
                MemoryRecordEntity target = memoryRepository.queryById(targetId);
                if (target != null && MemoryStatus.ACTIVE.equals(target.getStatus())) {
                    return new OperationDecision("UPDATE", target);
                }
            }
        }

        return new OperationDecision("ADD", null);
    }

    /**
     * value 变更策略（P1-4 备注）：幂等 NOOP / 版本化 UPDATE / DISPUTED 多版本。
     *
     * <p>同 (user, subject, predicate) 命中时比较新旧 value：归一化后相同 → NOOP；
     * 任一 value 缺失（无法判断冲突）→ 身份一致走版本化 UPDATE；归一化后不同 →
     * 保守走 DISPUTED 多版本，不强制 SUPERSEDE，避免静默覆盖「同 predicate 异 value」的差异
     * （演进 vs 矛盾的语义判定留给 P2 事实一致性巡检 + 会话确认裁决）。</p>
     */
    private OperationDecision valuePolicy(MemoryCandidate c, MemoryRecordEntity target) {
        if ("DELETE".equals(c.operation())) {
            return new OperationDecision("DELETE", target);
        }
        String oldValue = target.getValue();
        String newValue = c.value();
        if (oldValue == null || newValue == null) {
            return new OperationDecision("UPDATE", target);
        }
        if (PredicateNormalizer.normalizeValue(oldValue).equals(PredicateNormalizer.normalizeValue(newValue))) {
            return new OperationDecision("NOOP", target);
        }
        return new OperationDecision("DISPUTED", target);
    }

    /** LLM 给定目标时，若候选与目标都带 subject/predicate，则归一化后必须一致 */
    private boolean subjectPredicateMatch(MemoryCandidate c, MemoryRecordEntity target) {
        String cSubject = PredicateNormalizer.normalize(c.subject());
        String cPredicate = PredicateNormalizer.normalize(c.predicate());
        if (cSubject != null && cPredicate != null
                && target.getSubject() != null && target.getPredicate() != null) {
            return cSubject.equals(PredicateNormalizer.normalize(target.getSubject()))
                    && cPredicate.equals(PredicateNormalizer.normalize(target.getPredicate()));
        }
        return true;
    }

    /** 操作决策结果：action ∈ {ADD, UPDATE, DELETE, DISPUTED, NOOP} */
    public record OperationDecision(String action, MemoryRecordEntity target) {
    }

    /**
     * 幻觉抽检的证据校验：判断 evidence 原文片段是否支撑该记忆断言。
     *
     * <p>供治理任务 {@code MemoryGovernanceJob.spotCheckHallucination} 调用——抽样 confidence 0.8-0.9
     * 的记忆，回溯其 evidence 判断是否被原文支撑，不支撑者为疑似幻觉，交由治理任务软隔离。</p>
     *
     * <p>保守策略：LLM 不可用 / 解析失败 / 输出「不确定」均放行（返回 true），
     * 仅在明确输出「不支持」时判疑似幻觉（返回 false），避免误隔离。</p>
     *
     * @param content  记忆断言
     * @param evidence 抽取时的证据原文片段
     * @return true = 支撑或无法判定（放行）；false = 证据不支撑（疑似幻觉）
     */
    public boolean verifyEvidence(String content, String evidence) {
        if (chatOpenAiApi == null) {
            return true; // LLM 不可用：保守放行，不误隔离
        }
        if (evidence == null || evidence.isBlank()) {
            return false; // 无证据却落在 confidence 0.8-0.9 灰色地带 → 疑似幻觉
        }
        String system = "你是记忆证据校验器。判断给出的「证据」是否支撑「记忆」这一断言，只输出一个词：支持 或 不支持 或 不确定。";
        String user = "记忆：" + content + "\n证据：" + evidence;
        String resp = callLlm(system, user);
        if (resp == null || resp.isBlank()) {
            return true; // LLM 失败：保守放行
        }
        return !resp.contains("不支持"); // 仅明确「不支持」判疑似幻觉，其余保守放行
    }

    /** 调用 LLM chat completions（带重试），System 铁律 + User 数据拆两条消息 */
    private String callLlm(String systemPrompt, String userPrompt) {
        if (chatOpenAiApi == null) {
            log.warn("MemoryExtractor chatOpenAiApi 未初始化");
            return null;
        }
        for (int attempt = 0; attempt < MAX_LLM_RETRIES; attempt++) {
            try {
                List<OpenAiApi.ChatCompletionMessage> messages = new ArrayList<>();
                if (systemPrompt != null && !systemPrompt.isBlank()) {
                    messages.add(new OpenAiApi.ChatCompletionMessage(systemPrompt, OpenAiApi.ChatCompletionMessage.Role.SYSTEM));
                }
                messages.add(new OpenAiApi.ChatCompletionMessage(userPrompt, OpenAiApi.ChatCompletionMessage.Role.USER));
                var request = new OpenAiApi.ChatCompletionRequest(
                        messages, chatModel, 0.3, false
                );
                ResponseEntity<OpenAiApi.ChatCompletion> response = chatOpenAiApi.chatCompletionEntity(request);
                if (response != null && response.getBody() != null
                        && response.getBody().choices() != null && !response.getBody().choices().isEmpty()) {
                    return response.getBody().choices().get(0).message().content();
                }
                return null;
            } catch (Exception e) {
                if (attempt < MAX_LLM_RETRIES - 1) {
                    log.warn("记忆抽取 LLM 重试 {}/{}: {}", attempt + 1, MAX_LLM_RETRIES, e.getMessage());
                    try { Thread.sleep(LLM_RETRY_BACKOFF_MS); } catch (InterruptedException ignored) {}
                } else {
                    log.error("记忆抽取 LLM 调用失败，已达最大重试次数: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    /** 防御性 JSON 解析：处理 code fence、多余文字等 */
    private List<MemoryCandidate> parseResponse(String llmResponse) {
        try {
            // 去除 markdown code fence
            String cleaned = llmResponse.replaceAll("```json\\s*", "").replaceAll("```\\s*", "");

            // 正则提取最外层 JSON 对象
            Matcher matcher = JSON_PATTERN.matcher(cleaned);
            if (!matcher.find()) return Collections.emptyList();
            String jsonStr = matcher.group();

            // 解析
            JSONObject obj = JSON.parseObject(jsonStr);
            JSONArray memories = obj.getJSONArray("memory");
            if (memories == null || memories.isEmpty()) return Collections.emptyList();

            // 逐条验证
            List<MemoryCandidate> result = new ArrayList<>();
            for (int i = 0; i < memories.size(); i++) {
                JSONObject m = memories.getJSONObject(i);
                String text = m.getString("text");
                String type = m.getString("type");
                String attributedTo = m.getString("attributed_to");
                String operation = m.getString("operation");
                Long targetMemoryId = m.getLong("target_memory_id");
                String subject = m.getString("subject");
                String predicate = m.getString("predicate");
                String value = m.getString("value");
                String evidence = m.getString("evidence");
                Double confidence = m.getDouble("confidence");

                // 校验
                int maxLen = memoryProperties != null ? memoryProperties.getExtraction().getMaxContentLength() : 500;
                if (text == null || text.isBlank() || text.length() > maxLen) {
                    metrics.incrementExtractionRejected("too_long");
                    continue;
                }
                if (!MemoryTypeVO.isValid(type)) {
                    metrics.incrementExtractionRejected("invalid_type");
                    continue;
                }

                // 缺省 operation = ADD
                if (operation == null || operation.isBlank()) {
                    operation = "ADD";
                }
                // target_memory_id 以 0 表示「无目标」，归一为 null
                if (targetMemoryId != null && targetMemoryId == 0L) {
                    targetMemoryId = null;
                }
                // confidence 缺省时按 evidence 有无推断
                if (confidence == null) {
                    confidence = (evidence != null && !evidence.isBlank()) ? 0.8 : 0.3;
                }

                result.add(new MemoryCandidate(text.trim(), type, attributedTo, operation,
                        targetMemoryId, subject, predicate, value, evidence, confidence));
                metrics.incrementExtractionAccepted(type);
            }
            return result;
        } catch (Exception e) {
            log.warn("记忆抽取 JSON 解析失败, 原始响应: {}", llmResponse.substring(0, Math.min(200, llmResponse.length())), e);
            return Collections.emptyList();
        }
    }

    /** 余弦相似度 */
    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-10);
    }
}
