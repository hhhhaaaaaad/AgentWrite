package cn.sutone.ai.domain.agent.service.ai_writing;

import cn.sutone.ai.domain.agent.adapter.repository.IAiTaskRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IOutboxEventRepository;
import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.entity.OutboxEventEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingStreamEventVO;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingTaskTypeVO;
import cn.sutone.ai.domain.agent.service.IAiWritingService;
import cn.sutone.ai.domain.agent.service.IChatService;
import cn.sutone.ai.domain.agent.service.ITaskEventPublisher;
import cn.sutone.ai.domain.agent.service.ai_writing.markdown.MarkdownBlockRenderer;
import cn.sutone.ai.domain.agent.service.ai_writing.markdown.MarkdownNormalizer;
import cn.sutone.ai.domain.agent.service.ai_writing.strategy.AiWritingTaskStrategy;
import cn.sutone.ai.domain.agent.service.ai_writing.strategy.AiWritingTaskStrategyResolver;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.ratelimit.RateLimitService;
import cn.sutone.ai.domain.content.model.entity.DraftEntity;
import cn.sutone.ai.domain.content.service.draft.DraftDomainService;
import cn.sutone.ai.types.dto.AiTaskMessage;
import cn.sutone.ai.types.common.RedisKeyConstants;
import cn.sutone.ai.types.enums.ResponseCode;
import cn.sutone.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * AI 写作服务实现（MQ 改造版）
 */
@Slf4j
@Service
public class AiWritingService implements IAiWritingService {

    private static final String WRITING_AGENT_ID = "300002";
    private static final String DRAWIO_AGENT_ID = "300000";
    private static final String ILLUSTRATION_AGENT_ID = "300003";
    private static final String AUTHOR_ANALYST = "agent_writing_analyst";
    private static final String AUTHOR_GENERATOR = "agent_writing_generator";
    private static final String AUTHOR_REVIEWER = "agent_writing_reviewer";
    private static final String EVENT_TYPE_CREATED = "AI_WRITING_TASK_CREATED";

    @Value("${ai-writing.mq.topic:ai-writing-task}")
    private String mqTopic;

    /** 注入自身代理，解决 @Transactional 自调用不经过 AOP 代理的问题 */
    @org.springframework.context.annotation.Lazy
    @jakarta.annotation.Resource
    private AiWritingService self;

    private static final Map<String, String> AUTHOR_PHASE_MAP = Map.of(
            AUTHOR_ANALYST, "analyzing", AUTHOR_GENERATOR, "generating", AUTHOR_REVIEWER, "reviewing");
    private static final Map<String, String> PHASE_LABEL_MAP = Map.of(
            "analyzing", "正在分析草稿上下文...", "generating", "正在生成写作内容...",
            "illustrating", "正在识别配图需求...", "reviewing", "正在进行质量审查...");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final IChatService chatService;
    private final IAiTaskRepository aiTaskRepository;
    private final IOutboxEventRepository outboxEventRepository;
    private final DraftDomainService draftDomainService;
    private final RateLimitService rateLimitService;
    private final RedissonClient redissonClient;
    private final MemoryManager memoryManager;
    private final AgentWritingRunner agentWritingRunner;
    private final ITaskEventPublisher taskEventPublisher;
    private final AiWritingTaskStrategyResolver strategyResolver;
    private final cn.sutone.ai.domain.agent.adapter.repository.IOutboxImmediatePublisher outboxImmediatePublisher;
    private final cn.sutone.ai.domain.agent.service.userconfig.UserModelConfigService userModelConfigService;

    public AiWritingService(IChatService chatService, IAiTaskRepository aiTaskRepository,
                            IOutboxEventRepository outboxEventRepository,
                            DraftDomainService draftDomainService, RateLimitService rateLimitService,
                            RedissonClient redissonClient, MemoryManager memoryManager,
                            AgentWritingRunner agentWritingRunner, ITaskEventPublisher taskEventPublisher,
                            AiWritingTaskStrategyResolver strategyResolver,
                            cn.sutone.ai.domain.agent.adapter.repository.IOutboxImmediatePublisher outboxImmediatePublisher,
                            cn.sutone.ai.domain.agent.service.userconfig.UserModelConfigService userModelConfigService) {
        this.chatService = chatService;
        this.aiTaskRepository = aiTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.draftDomainService = draftDomainService;
        this.rateLimitService = rateLimitService;
        this.redissonClient = redissonClient;
        this.memoryManager = memoryManager;
        this.agentWritingRunner = agentWritingRunner;
        this.taskEventPublisher = taskEventPublisher;
        this.strategyResolver = strategyResolver;
        this.outboxImmediatePublisher = outboxImmediatePublisher;
        this.userModelConfigService = userModelConfigService;
    }

    @Override
    public AiTaskEntity submitTask(Long userId, Long draftId, String taskTypeCode, Map<String, Object> promptParams, Boolean enableIllustration) {
        // 1. redis限流器限流 每用户每分钟最多 5 次 AI 调用（快捷操作）
        if (!rateLimitService.tryAcquire(userId)) {
            throw new AppException(ResponseCode.E0001.getCode(), "AI 请求过于频繁，请稍后再试");
        }
        // 2. 拼接分布式锁（5s 用户ID + 草稿ID + 任务类型） 防止任务重复提交
        String lockKey = RedisKeyConstants.AI_TASK_LOCK_PREFIX + userId + ":" + draftId + ":" + taskTypeCode;
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (!lock.tryLock(0, 5, TimeUnit.SECONDS)) {
                throw new AppException(ResponseCode.E0001.getCode(), "请勿重复提交，上个任务仍在处理中");
            }
            // 3. 事务内：校验 + 建任务 + 写 Outbox（通过 self 代理调用，确保 @Transactional 生效）
            AiTaskEntity task = self.doSubmitInTransaction(userId, draftId, taskTypeCode, promptParams, enableIllustration);

            // 4. 事务外：立即尝试投递 MQ（失败不影响主流程，由定时任务兜底）
            outboxImmediatePublisher.tryPublish(task.getTaskId());

            return task;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ResponseCode.E0001.getCode(), "系统繁忙，请稍后再试");
        } finally {
            // 锁释放
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /**
     * 事务内：校验草稿 + 创建任务 + 写 Outbox 事件
     * <p>保证 ai_task 和 outbox_event 在同一事务中写入，原子一致。</p>
     */
    @Transactional
    public AiTaskEntity doSubmitInTransaction(Long userId, Long draftId, String taskTypeCode,
                                              Map<String, Object> promptParams, Boolean enableIllustration) {
        // 获取草稿信息，检测状态当前是否为编辑中
        DraftEntity draft = draftDomainService.queryDraftDetail(draftId, userId);
        draft.checkEditable();
        // 解析任务类型
        AiWritingTaskTypeVO taskType = AiWritingTaskTypeVO.fromCode(taskTypeCode);
        // 根据不同的任务类型，拼接出不同的提示词（会提取记忆系统）
        String prompt = buildPrompt(draft, taskType, promptParams);
        // 初始化任务并落库，状态为待处理；快照用户默认模型配置 ID（多租户）
        java.util.Optional<cn.sutone.ai.domain.agent.model.entity.UserModelConfigEntity> defaultCfg =
                userModelConfigService.queryDefaultByUserId(userId);
        Long modelConfigId = defaultCfg.map(cn.sutone.ai.domain.agent.model.entity.UserModelConfigEntity::getId).orElse(null);
        AiTaskEntity task = AiTaskEntity.initPending(userId, draftId, taskType, prompt, enableIllustration, modelConfigId);
        aiTaskRepository.save(task);
        Long taskId = task.getTaskId();

        // 创建 outbox 事件
        // 先以占位 payload 保存 Outbox 拿到真实 eventId，再用真实 eventId 更新 payload
        OutboxEventEntity outboxEvent = OutboxEventEntity.newEvent(taskId, EVENT_TYPE_CREATED, mqTopic, "{}");
        outboxEventRepository.save(outboxEvent);
        AiTaskMessage message = AiTaskMessage.builder()
                .taskId(taskId).eventId(outboxEvent.getEventId()).createdAt(java.time.LocalDateTime.now().toString()).build();
        outboxEventRepository.updatePayload(outboxEvent.getEventId(), JSON.toJSONString(message));

        log.info("任务提交 taskId={} eventId={}", taskId, outboxEvent.getEventId());
        return task;
    }

    @Override
    public AiTaskEntity queryTask(Long taskId, Long userId) {
        AiTaskEntity task = aiTaskRepository.queryById(taskId);
        if (null == task) throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "AI 任务不存在");
        task.validateOwner(userId);
        return task;
    }

    @Override
    public void generateStream(Long taskId, Long userId, Consumer<AiWritingStreamEventVO> eventConsumer) {
        AiTaskEntity task = queryTask(taskId, userId);
        task.startRunning();
        aiTaskRepository.update(task);

        String agentId = resolveAgentId();
        String sessionId = chatService.createSession(agentId, String.valueOf(userId));
        StringBuilder responseBuilder = new StringBuilder();
        StringBuilder reviewerLineBuffer = new StringBuilder();
        boolean enableIllustration = Boolean.TRUE.equals(task.getEnableIllustration());

        try {
            Flowable<Event> events = chatService.handleMessageStream(agentId, String.valueOf(userId), sessionId, task.getPromptPayload());
            String[] currentPhase = {null};
            events.blockingForEach(event -> {
                if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) return;
                String author = event.author();
                String newPhase = AUTHOR_PHASE_MAP.getOrDefault(author, "thinking");
                if (!Objects.equals(newPhase, currentPhase[0])) {
                    currentPhase[0] = newPhase;
                    String label = PHASE_LABEL_MAP.getOrDefault(newPhase, "思考中...");
                    eventConsumer.accept(statusEvent(newPhase, label));
                }
                String content = event.stringifyContent();
                if (null == content || content.isBlank()) return;
                if (AUTHOR_ANALYST.equals(author)) return;
                if (AUTHOR_GENERATOR.equals(author)) {
                    eventConsumer.accept(tokenEvent(newPhase, content));
                    return;
                }
                boolean isPartial = event.partial().orElse(false);
                reviewerLineBuffer.append(content);
                if (isPartial && reviewerLineBuffer.indexOf("\n") < 0) return;
                String accumulated = reviewerLineBuffer.toString();
                String[] lines = accumulated.split("\n", -1);
                int processUpTo = isPartial ? lines.length - 1 : lines.length;
                reviewerLineBuffer.setLength(0);
                if (isPartial && lines.length > 0 && !lines[lines.length - 1].isEmpty())
                    reviewerLineBuffer.append(lines[lines.length - 1]);
                for (int i = 0; i < processUpTo; i++)
                    consumeReviewerLine(newPhase, lines[i], responseBuilder, eventConsumer);
            });
            if (reviewerLineBuffer.length() > 0)
                consumeReviewerLine("reviewing", reviewerLineBuffer.toString(), responseBuilder, eventConsumer);

            List<IllustrationRequest> illustrationRequests = enableIllustration
                    ? analyzeIllustrations(userId, responseBuilder.toString()) : List.of();
            if (!illustrationRequests.isEmpty()) {
                eventConsumer.accept(statusEvent("illustrating", "正在生成配图..."));
                for (IllustrationRequest req : illustrationRequests) {
                    try {
                        String drawXml = generateIllustration(userId, req);
                        if (null != drawXml && !drawXml.isBlank())
                            injectIllustration(responseBuilder, req.anchor(), drawXml, eventConsumer);
                    } catch (Exception e) { log.error("生成配图失败 anchor={}: {}", req.anchor(), e.getMessage()); }
                }
            }
            String formattedContent = formatMarkdown(responseBuilder.toString());
            markSuccess(task, formattedContent);
            memoryManager.addAsync(userId, Long.parseLong(WRITING_AGENT_ID), sessionId,
                    List.of(Map.of("role", "user", "content", task.getPromptPayload()),
                            Map.of("role", "assistant", "content", formattedContent)));
            eventConsumer.accept(resultEvent(formattedContent));
            eventConsumer.accept(doneEvent());
        } catch (Exception e) {
            String errorMsg = e.getMessage();
            if (null == errorMsg || errorMsg.isBlank()) errorMsg = e.getClass().getSimpleName();
            markFailed(task, errorMsg);
            eventConsumer.accept(errorEvent(errorMsg));
        }
    }

    /**
     * MQ Consumer 入口：执行 Agent 编排，不依赖 HTTP/Servlet
     *
     * <p>执行流程：
     * 1. 查询任务详情（prompt、userId、draftId 等）
     * 2. 调用 AgentWritingRunner 执行 Multi-Agent 编排（analyst→generator→reviewer→配图）
     * 3. 执行过程中通过 eventConsumer 回调：
     *    - 每 5 秒更新一次心跳（供 RecoveryJob 判断任务是否存活）
     *    - 实时推送流式事件到 Redis Stream（前端 SSE 消费）
     * 4. 成功：结果落库 + 推送终稿事件 + 异步触发记忆抽取
     * 5. 可重试异常（模型限流/网络超时）：标记 RETRYING + 抛出让 MQ 自动重试
     * 6. 不可恢复异常：标记 FAILED + 推送错误事件给前端
     * </p>
     */
    @Override
    public void executeTask(Long taskId) {
        // Step 1: 查询任务（Consumer 的 claimTask 已将状态原子更新为 RUNNING）
        AiTaskEntity task = aiTaskRepository.queryById(taskId);
        if (null == task) {
            log.error("executeTask: 任务不存在 taskId={}", taskId);
            return;
        }

        // Step 2: 心跳节流设置
        // AI 任务执行 30s~2min，期间每产出一个 token 都会回调 eventConsumer，
        // 但心跳只需每 5 秒写一次 DB，避免 QPS 过高打爆数据库
        final long heartbeatIntervalMs = 5_000L;
        final long[] lastHeartbeat = {System.currentTimeMillis()};

        try {
            // Step 3: 执行 Agent 编排（核心调用）
            // agentWritingRunner.run() 内部会根据任务类型选择 workflow（多 Agent）或 single-agent 路径
            // eventConsumer 回调在每个 token/status 事件产出时触发
            String formattedContent = agentWritingRunner.run(task, event -> {
                // 心跳节流：距上次 ≥ 5s 才更新 DB
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat[0] >= heartbeatIntervalMs) {
                    aiTaskRepository.touchHeartbeat(taskId);
                    lastHeartbeat[0] = now;
                }
                // 实时推送事件到 Redis Stream，前端 SSE 连接监听并渲染
                taskEventPublisher.publish(taskId, event);
            });

            // Step 4: 成功路径
            // 4a. 结果落库（status=SUCCESS, response_content=终稿）
            aiTaskRepository.markSuccess(taskId, formattedContent);
            // 4b. 补发权威终稿事件：前端据此采纳最终内容，
            //     避免拼接 generator/reviewer 多阶段 token 造成重复
            //     推送前端 发送给redis 的 stream，
            taskEventPublisher.publish(taskId, resultEvent(formattedContent));
            taskEventPublisher.publishDone(taskId);

            // 4c. 异步触发记忆抽取：从本次对话中提取用户技术背景、写作偏好等
            AiWritingTaskStrategy strategy = strategyResolver.resolve(task);
            String memAgentId = strategy.agentId();
            // 此处的 sessionId 不是之前的对话id，而是给记忆抽取的 LLM 调用提供一个运行环境。
            String sessionId = chatService.createSession(memAgentId, String.valueOf(task.getUserId()));
            memoryManager.addAsync(task.getUserId(), Long.parseLong(memAgentId), sessionId,
                    List.of(Map.of("role", "user", "content", task.getPromptPayload()),
                            Map.of("role", "assistant", "content", formattedContent)));

            log.info("executeTask 完成 taskId={}", taskId);
        } catch (RetryableAgentException e) {
            // Step 5: 可重试异常（模型限流、网络超时等临时性错误）
            // 标记 RETRYING + 抛出异常 → Consumer 不 ACK → RocketMQ 自动重试（最多 3 次）
            log.error("executeTask 可重试异常 taskId={}: {}", taskId, e.getMessage());
            aiTaskRepository.markRetryingImmediate(taskId, safeMsg(e));
            throw e;
        } catch (Exception e) {
            // Step 6: 不可恢复异常（配置错误、Prompt 格式问题等永久性错误）
            // 标记 FAILED + 推送 error 事件给前端 + 不抛异常（正常 ACK，不触发 MQ 重试）
            log.error("executeTask 不可恢复错误 taskId={}: {}", taskId, e.getMessage(), e);
            aiTaskRepository.markFailed(taskId, safeMsg(e));
            taskEventPublisher.publishError(taskId, safeMsg(e));
        }
    }

    @Override
    public List<AiTaskEntity> queryTaskList(Long draftId, Long userId, int limit) {
        draftDomainService.queryDraftDetail(draftId, userId);
        return aiTaskRepository.queryLatestByDraftId(draftId, limit);
    }

    // ==================== 私有方法 ====================

    private record IllustrationRequest(String anchor, String diagramType, String requirement) {}

    private String resolveAgentId() {
        List<AiAgentConfigTableVO.Agent> agents = chatService.queryAiAgentConfigList();
        if (null == agents || agents.isEmpty()) throw new AppException(ResponseCode.E0001.getCode(), "没有可用的 Agent 配置");
        return agents.stream().filter(a -> WRITING_AGENT_ID.equals(a.getAgentId())).findFirst()
                .map(AiAgentConfigTableVO.Agent::getAgentId)
                .orElseThrow(() -> new AppException(ResponseCode.E0001.getCode(), "未找到 AI 技术写作智能体配置"));
    }

    private void markSuccess(AiTaskEntity task, String responseContent) {
        task.markSuccess(responseContent);
        aiTaskRepository.update(task);
    }

    private void markFailed(AiTaskEntity task, String errorMsg) {
        task.markFailed(errorMsg);
        aiTaskRepository.update(task);
    }

    private String safeMsg(Exception e) {
        String msg = e.getMessage();
        return (null == msg || msg.isBlank()) ? e.getClass().getSimpleName() : msg;
    }

    // ==================== 以下为 generateStream 辅助方法 ====================

    private List<IllustrationRequest> analyzeIllustrations(Long userId, String articleContent) {
        String prompt = buildIllustrationPrompt(articleContent);
        String sessionId = chatService.createSession(ILLUSTRATION_AGENT_ID, String.valueOf(userId));
        List<String> outputs = chatService.handleMessage(ILLUSTRATION_AGENT_ID, String.valueOf(userId), sessionId, prompt);
        List<IllustrationRequest> requests = new ArrayList<>();
        for (String line : outputs) {
            if (null == line || line.isBlank()) continue;
            try {
                JsonNode json = objectMapper.readTree(line.trim());
                if (json.has("none") && json.get("none").asBoolean()) { requests.clear(); break; }
                String anchor = json.has("anchor") ? json.get("anchor").asText() : null;
                String diagramType = json.has("diagramType") ? json.get("diagramType").asText() : null;
                String requirement = json.has("requirement") ? json.get("requirement").asText() : null;
                if (null != anchor && null != diagramType && null != requirement)
                    requests.add(new IllustrationRequest(anchor, diagramType, requirement));
            } catch (Exception e) { log.warn("解析配图分析结果失败，跳过该行: {}", line, e); }
        }
        return requests;
    }

    private String buildIllustrationPrompt(String articleContent) {
        return """
                分析以下技术文章，判断哪些段落适合配图。你必须且只能输出 JSON，每行一条，格式如下：
                {"type":"illustration_request","anchor":"段落标识","diagramType":"architecture|flowchart|sequence","requirement":"具体画什么"}
                规则：系统架构→architecture，业务流程→flowchart，调用时序→sequence。最多3条。
                若无需配图，输出：{"type":"illustration_request","none":true}
                严禁输出 JSON 以外的任何内容。

                ---文章内容---
                %s
                """.formatted(articleContent);
    }

    private String generateIllustration(Long userId, IllustrationRequest req) {
        String drawSessionId = chatService.createSession(DRAWIO_AGENT_ID, String.valueOf(userId));
        String drawPrompt = """
                请根据以下绘图需求，生成一个 draw.io 图表。
                图表类型：%s
                需求描述：%s
                """.formatted(req.diagramType(), req.requirement());
        Flowable<Event> drawEvents = chatService.handleMessageStream(DRAWIO_AGENT_ID, String.valueOf(userId), drawSessionId, drawPrompt);
        String[] drawXml = {null};
        Map<String, StringBuilder> authorBuffers = new LinkedHashMap<>();
        drawEvents.blockingForEach(event -> {
            if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) return;
            String author = event.author();
            String content = event.stringifyContent();
            if (null == content || content.isBlank() || null == author) return;
            if (!"agent_drawer".equals(author)) return;
            boolean isPartial = event.partial().orElse(false);
            StringBuilder buffer = authorBuffers.computeIfAbsent(author, k -> new StringBuilder());
            buffer.append(content);
            String accumulated = buffer.toString();
            if (isPartial && accumulated.indexOf('\n') < 0) return;
            String[] lines = accumulated.split("\n", -1);
            String remaining = lines[lines.length - 1];
            buffer.setLength(0);
            if (!remaining.isEmpty()) buffer.append(remaining);
            int processUpTo = isPartial ? lines.length - 1 : lines.length;
            for (int i = 0; i < processUpTo; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                try {
                    JsonNode json = objectMapper.readTree(line);
                    if ("drawio_done".equals(json.has("type") ? json.get("type").asText() : null))
                        drawXml[0] = json.has("content") ? json.get("content").asText() : null;
                } catch (Exception ignored) {}
            }
        });
        for (Map.Entry<String, StringBuilder> entry : authorBuffers.entrySet()) {
            try {
                JsonNode json = objectMapper.readTree(entry.getValue().toString().trim());
                if ("drawio_done".equals(json.has("type") ? json.get("type").asText() : null))
                    drawXml[0] = json.has("content") ? json.get("content").asText() : null;
            } catch (Exception ignored) {}
        }
        return drawXml[0];
    }

    private void injectIllustration(StringBuilder responseBuilder, String anchor, String drawXml, Consumer<AiWritingStreamEventVO> eventConsumer) {
        String diagramBlock = "\n```drawio\n" + drawXml + "\n```\n";
        int anchorPos = findAnchor(responseBuilder, anchor);
        if (anchorPos >= 0) {
            int insertPos = anchorPos + anchor.length();
            int lineEnd = responseBuilder.indexOf("\n", insertPos);
            if (lineEnd >= 0) responseBuilder.insert(lineEnd, "\n" + diagramBlock);
            else responseBuilder.insert(insertPos, "\n" + diagramBlock);
        } else responseBuilder.append("\n").append(diagramBlock);
        eventConsumer.accept(tokenEvent("illustrating", diagramBlock));
    }

    private int findAnchor(StringBuilder text, String anchor) {
        if (null == anchor || anchor.isBlank()) return -1;
        int pos = text.indexOf(anchor);
        if (pos >= 0) return pos;
        String trimmed = anchor.trim();
        if (!trimmed.equals(anchor)) { pos = text.indexOf(trimmed); if (pos >= 0) return pos; }
        String[] words = trimmed.split("\\s+");
        String longest = "";
        for (String w : words) if (w.length() > longest.length()) longest = w;
        if (longest.length() >= 3) return text.indexOf(longest);
        return -1;
    }

    private void consumeReviewerLine(String phase, String line, StringBuilder responseBuilder, Consumer<AiWritingStreamEventVO> eventConsumer) {
        if (null == line) return;
        if (line.isBlank()) { responseBuilder.append("\n"); eventConsumer.accept(tokenEvent(phase, "\n")); return; }
        if (MarkdownBlockRenderer.isBlockLine(line)) {
            String fragment = MarkdownBlockRenderer.renderLine(line);
            if (null == fragment || fragment.isEmpty()) return;
            responseBuilder.append(fragment).append("\n\n");
            eventConsumer.accept(tokenEvent(phase, fragment + "\n\n", line.trim()));
        } else {
            responseBuilder.append(line).append("\n");
            eventConsumer.accept(tokenEvent(phase, line + "\n"));
        }
    }

    private String buildPrompt(DraftEntity draft, AiWritingTaskTypeVO taskType, Map<String, Object> promptParams) {
        String extraParams = null == promptParams || promptParams.isEmpty() ? "{}" : String.valueOf(promptParams);
        String customInstruction = null == promptParams ? null : (String) promptParams.get("customInstruction");
        String selectedText = null == promptParams ? null : (String) promptParams.get("selectedText");
        String formatInstruction = null == promptParams ? null : (String) promptParams.get("formatInstruction");
        String customSuffix = null == customInstruction || customInstruction.isBlank() ? "" : "\n\n用户额外指令：%s".formatted(customInstruction);
        String formatHardRule = null == formatInstruction || formatInstruction.isBlank() ? "" : "\n\n【格式硬约束 - 必须遵守】\n%s".formatted(formatInstruction);
        String memoryContext = memoryManager.retrieveContext(draft.getUserId(), draft.getContentMd(), 5);
        String memoryPrefix = null == memoryContext || memoryContext.isBlank() ? "" : "【用户记忆上下文】\n" + memoryContext + "\n\n";
        return switch (taskType) {
            case GENERATE_OUTLINE -> memoryPrefix + """
                    你是一个高级技术写作 Agent。请基于当前草稿上下文，为这篇技术文章生成 Markdown 大纲。
                    要求：结构清晰、层级合理、适合技术社区文章，不要输出解释说明，只输出大纲。
                    %s

                    标题：%s
                    摘要：%s
                    当前正文：
                    %s

                    额外参数：%s%s
                    """.formatted(formatHardRule, nullToEmpty(draft.getTitle()), nullToEmpty(draft.getSummary()), nullToEmpty(draft.getContentMd()), extraParams, customSuffix);
            case GENERATE_BODY -> memoryPrefix + """
                    你是一个高级技术写作 Agent。请基于当前草稿上下文续写正文，输出 Markdown 内容。
                    要求：保持技术准确、表达自然、结构连贯，不要重复已有正文，不要输出解释说明。
                    注意：不要输出文章标题（# xxx），标题已在草稿中，直接从 ## 或正文内容开始写。
                    严禁输出任何对话式内容（如"文章已完整""如果你希望…""可以告诉我方向"之类），
                    也不要输出建议清单或向用户提问；无论如何都只输出正文 Markdown 本身。
                    若当前正文已较完整，则围绕现有大纲/章节补充细节、示例或代码，而不是回复"无需续写"。
                    %s

                    标题：%s
                    摘要：%s
                    当前正文：
                    %s

                    额外参数：%s%s
                    """.formatted(formatHardRule, nullToEmpty(draft.getTitle()), nullToEmpty(draft.getSummary()), nullToEmpty(draft.getContentMd()), extraParams, customSuffix);
            case POLISH_TEXT -> {
                boolean hasSelectedText = null != selectedText && !selectedText.isBlank();
                String body = hasSelectedText ? selectedText : nullToEmpty(draft.getContentMd());
                String desc = hasSelectedText
                    ? "【处理范围】选中文本\n请只对以下选中文本进行润色改写，只输出可替换选中文本的改写结果，不要输出解释说明。不要添加标题、列表或章节，除非原文已有对应结构。"
                    : "【处理范围】全文草稿\n请对当前草稿进行润色改写，保留原有结构、标题层级和段落顺序。只优化表达质量和阅读流畅度，不得把大纲扩写为正文，不得新增章节。要求：不要输出解释说明，不要输出文章标题（# xxx），标题已在草稿中。";
                yield memoryPrefix + """
                    你是一个高级技术写作 Agent。%s
                    %s

                    标题：%s
                    摘要：%s
                    待处理文本：
                    %s

                    额外参数：%s%s
                    """.formatted(desc, formatHardRule, nullToEmpty(draft.getTitle()), nullToEmpty(draft.getSummary()), body, extraParams, customSuffix);
            }
            case SUMMARIZE -> memoryPrefix + """
                    你是一个高级技术写作 Agent。请基于当前草稿生成一段适合发布页展示的文章摘要。
                    要求：100 到 200 字，突出主题、技术价值和读者收益，不要输出解释说明。

                    标题：%s
                    当前正文：
                    %s

                    额外参数：%s%s
                    """.formatted(nullToEmpty(draft.getTitle()), nullToEmpty(draft.getContentMd()), extraParams, customSuffix);
            case GENERATE_TITLE -> memoryPrefix + """
                    你是一个高级技术写作 Agent。请基于当前草稿生成 3 到 5 个候选标题。
                    要求：吸引技术读者、突出文章核心价值、简洁有力，每个标题一行，不要输出解释说明。

                    标题：%s
                    摘要：%s
                    当前正文：
                    %s

                    额外参数：%s%s
                    """.formatted(nullToEmpty(draft.getTitle()), nullToEmpty(draft.getSummary()), nullToEmpty(draft.getContentMd()), extraParams, customSuffix);
            case GENERATE_TAGS -> memoryPrefix + """
                    你是一个高级技术写作 Agent。请分析当前草稿内容，生成 3 到 5 个相关技术标签。
                    要求：标签应覆盖主要技术栈和主题，用英文逗号分隔，不要输出解释说明。

                    标题：%s
                    摘要：%s
                    当前正文：
                    %s

                    额外参数：%s%s
                    """.formatted(nullToEmpty(draft.getTitle()), nullToEmpty(draft.getSummary()), nullToEmpty(draft.getContentMd()), extraParams, customSuffix);
            case QUALITY_CHECK -> memoryPrefix + """
                    你是一个高级技术写作 Agent。请对当前草稿进行发布质量检查。
                    检查项：拼写错误、语法问题、结构完整性、代码正确性、技术准确性。
                    要求：逐项列出问题及改进建议，如无问题则输出"质量检查通过"，不要输出多余解释。

                    标题：%s
                    摘要：%s
                    当前正文：
                    %s

                    额外参数：%s%s
                    """.formatted(nullToEmpty(draft.getTitle()), nullToEmpty(draft.getSummary()), nullToEmpty(draft.getContentMd()), extraParams, customSuffix);
        };
    }

    private String nullToEmpty(String value) { return null == value ? "" : value; }

    private String formatMarkdown(String raw) { return MarkdownNormalizer.normalize(raw); }

    private AiWritingStreamEventVO statusEvent(String phase, String content) { return buildEvent(phase, "status", content); }
    private AiWritingStreamEventVO tokenEvent(String phase, String content) { return buildEvent(phase, "token", content); }
    private AiWritingStreamEventVO tokenEvent(String phase, String content, String raw) {
        return AiWritingStreamEventVO.builder().phase(phase)
                .chunk(AiWritingStreamEventVO.Chunk.builder().type("token").content(content).raw(raw).build()).build();
    }
    private AiWritingStreamEventVO doneEvent() { return buildEvent("done", "done", ""); }
    private AiWritingStreamEventVO resultEvent(String content) { return buildEvent("done", "result", content); }
    private AiWritingStreamEventVO errorEvent(String content) { return buildEvent("error", "error", content); }

    private AiWritingStreamEventVO buildEvent(String phase, String type, String content) {
        return AiWritingStreamEventVO.builder().phase(phase)
                .chunk(AiWritingStreamEventVO.Chunk.builder().type(type).content(content).build()).build();
    }
}
