package cn.sutone.ai.domain.agent.service.ai_writing;

import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingStreamEventVO;
import cn.sutone.ai.domain.agent.service.IChatService;
import cn.sutone.ai.domain.agent.service.ai_writing.markdown.MarkdownBlockRenderer;
import cn.sutone.ai.domain.agent.service.ai_writing.markdown.MarkdownNormalizer;
import cn.sutone.ai.domain.agent.service.ai_writing.strategy.AiWritingTaskStrategy;
import cn.sutone.ai.domain.agent.service.ai_writing.strategy.AiWritingTaskStrategyResolver;
import cn.sutone.ai.domain.agent.service.userconfig.UserModelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Agent 写作编排执行器（从 AiWritingService.generateStream 抽取）
 * 不依赖 HTTP/Servlet 回调，通过 Consumer 输出流式事件
 */
@Slf4j
@Component
public class AgentWritingRunner {

    private static final String DRAWIO_AGENT_ID = "300000";
    private static final String ILLUSTRATION_AGENT_ID = "300003";
    private static final String AUTHOR_ANALYST = "agent_writing_analyst";
    private static final String AUTHOR_GENERATOR = "agent_writing_generator";
    private static final String AUTHOR_REVIEWER = "agent_writing_reviewer";

    private static final Map<String, String> AUTHOR_PHASE_MAP = Map.of(
            AUTHOR_ANALYST, "analyzing",
            AUTHOR_GENERATOR, "generating",
            AUTHOR_REVIEWER, "reviewing"
    );
    private static final Map<String, String> PHASE_LABEL_MAP = Map.of(
            "analyzing", "正在分析草稿上下文...",
            "generating", "正在生成写作内容...",
            "illustrating", "正在识别配图需求...",
            "reviewing", "正在进行质量审查..."
    );

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final IChatService chatService;
    private final AiWritingTaskStrategyResolver strategyResolver;
    private final UserModelConfigService userModelConfigService;

    public AgentWritingRunner(IChatService chatService, AiWritingTaskStrategyResolver strategyResolver,
                              UserModelConfigService userModelConfigService) {
        this.chatService = chatService;
        this.strategyResolver = strategyResolver;
        this.userModelConfigService = userModelConfigService;
    }

    /**
     * 执行 Agent 编排，通过 eventConsumer 输出流式事件。
     * 根据策略分派到 workflow 路径或单 Agent 直调路径。
     *
     * @return 格式化后的完整文章内容
     */
    public String run(AiTaskEntity task, Consumer<AiWritingStreamEventVO> eventConsumer) {
        AiWritingTaskStrategy strategy = strategyResolver.resolve(task);
        String agentId = strategy.agentId();
        String userId = String.valueOf(task.getUserId());
        // 快捷操作为一次性任务，使用无历史会话，避免历史正文污染上下文导致模型返回对话式回复
        String sessionId = chatService.createSession(agentId, userId, false);

        if (strategy.useWorkflow()) {
            return runWorkflow(agentId, userId, sessionId, task, strategy, eventConsumer);
        } else {
            return runSingleAgent(agentId, userId, sessionId, task, strategy, eventConsumer);
        }
    }

    /**
     * 多阶段 workflow 路径：analyst → generator → reviewer → 配图（可选）。
     */
    private String runWorkflow(String agentId, String userId, String sessionId,
                               AiTaskEntity task, AiWritingTaskStrategy strategy,
                               Consumer<AiWritingStreamEventVO> eventConsumer) {
        StringBuilder responseBuilder = new StringBuilder();
        StringBuilder reviewerLineBuffer = new StringBuilder();
        boolean enableIllustration = strategy.enableIllustration();

        Flowable<Event> events = chatService.handleMessageStream(agentId, userId, sessionId, task.getPromptPayload());
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
            // reviewer：累积全部原始输出，待流结束后按块解析（对块内杂散换行免疫）
            reviewerLineBuffer.append(content);
        });
        if (reviewerLineBuffer.length() > 0) {
            renderReviewerBlocks(reviewerLineBuffer.toString(), responseBuilder, eventConsumer);
        }

        // 配图（仅 workflow 模式启用）
        List<IllustrationRequest> illustrationRequests = enableIllustration
                ? analyzeIllustrations(task.getUserId(), responseBuilder.toString()) : List.of();
        if (!illustrationRequests.isEmpty()) {
            eventConsumer.accept(statusEvent("illustrating", "正在生成配图..."));
            for (IllustrationRequest req : illustrationRequests) {
                try {
                    String drawXml = generateIllustration(task.getUserId(), req);
                    if (null != drawXml && !drawXml.isBlank()) {
                        injectIllustration(responseBuilder, req.anchor(), drawXml, eventConsumer);
                    }
                } catch (Exception e) {
                    log.error("生成配图失败 anchor={}: {}", req.anchor(), e.getMessage());
                }
            }
        }

        return formatMarkdown(responseBuilder.toString(), strategy);
    }

    /**
     * 单 Agent 直调路径：发送 prompt → 收集输出 → normalize → 返回。
     * 适用于短文本任务（摘要/标题/标签/质量检查/大纲/局部润色）。
     */
    private String runSingleAgent(String agentId, String userId, String sessionId,
                                  AiTaskEntity task, AiWritingTaskStrategy strategy,
                                  Consumer<AiWritingStreamEventVO> eventConsumer) {
        StringBuilder responseBuilder = new StringBuilder();
        eventConsumer.accept(statusEvent("generating", "正在生成内容..."));

        Flowable<Event> events = chatService.handleMessageStream(agentId, userId, sessionId, task.getPromptPayload());
        events.blockingForEach(event -> {
            if (!event.functionCalls().isEmpty() || !event.functionResponses().isEmpty()) return;
            String content = event.stringifyContent();
            if (null == content || content.isBlank()) return;
            responseBuilder.append(content);
            eventConsumer.accept(tokenEvent("generating", content));
        });

        return formatMarkdown(responseBuilder.toString(), strategy);
    }

    // ==================== 私有方法 (从 AiWritingService 迁移) ====================

    private record IllustrationRequest(String anchor, String diagramType, String requirement) {}

    private List<IllustrationRequest> analyzeIllustrations(Long userId, String articleContent) {
        String prompt = buildIllustrationPrompt(articleContent);
        String sessionId = chatService.createSession(ILLUSTRATION_AGENT_ID, String.valueOf(userId), false);
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
                if (null != anchor && null != diagramType && null != requirement) {
                    requests.add(new IllustrationRequest(anchor, diagramType, requirement));
                }
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
        String drawSessionId = chatService.createSession(DRAWIO_AGENT_ID, String.valueOf(userId), false);
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
                    String type = json.has("type") ? json.get("type").asText() : null;
                    if ("drawio_done".equals(type)) drawXml[0] = json.has("content") ? json.get("content").asText() : null;
                } catch (Exception ignored) {}
            }
        });
        for (Map.Entry<String, StringBuilder> entry : authorBuffers.entrySet()) {
            String remaining = entry.getValue().toString().trim();
            if (remaining.isEmpty()) continue;
            try {
                JsonNode json = objectMapper.readTree(remaining);
                if ("drawio_done".equals(json.has("type") ? json.get("type").asText() : null)) {
                    drawXml[0] = json.has("content") ? json.get("content").asText() : null;
                }
            } catch (Exception ignored) {}
        }
        return drawXml[0];
    }

    private void injectIllustration(StringBuilder responseBuilder, String anchor,
                                     String drawXml, Consumer<AiWritingStreamEventVO> eventConsumer) {
        String diagramBlock = "\n```drawio\n" + drawXml + "\n```\n";
        int anchorPos = findAnchor(responseBuilder, anchor);
        if (anchorPos >= 0) {
            int insertPos = anchorPos + anchor.length();
            int lineEnd = responseBuilder.indexOf("\n", insertPos);
            if (lineEnd >= 0) responseBuilder.insert(lineEnd, "\n" + diagramBlock);
            else responseBuilder.insert(insertPos, "\n" + diagramBlock);
        } else {
            responseBuilder.append("\n").append(diagramBlock);
        }
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
        for (String w : words) { if (w.length() > longest.length()) longest = w; }
        if (longest.length() >= 3) return text.indexOf(longest);
        return -1;
    }

    /**
     * 将 reviewer 的完整原始输出解析为结构化块并渲染为标准 Markdown。
     *
     * <p>按花括号配对提取顶层 JSON 对象，对每个对象剔除杂散的真实换行（模型常在「1.\n1」这类位置
     * 插入换行，破坏 JSON 与编号），再交由 {@link MarkdownBlockRenderer} 确定性渲染。
     * 若未解析出任何块（模型未按协议输出），回退为把原文当作 Markdown 直接落库。</p>
     */
    private void renderReviewerBlocks(String raw, StringBuilder responseBuilder,
                                      Consumer<AiWritingStreamEventVO> eventConsumer) {
        List<String> objects = MarkdownBlockRenderer.extractTopLevelObjects(raw);
        if (objects.isEmpty()) {
            String fallback = raw.strip();
            if (!fallback.isEmpty()) {
                responseBuilder.append(fallback).append("\n");
                eventConsumer.accept(tokenEvent("reviewing", fallback));
            }
            return;
        }
        for (String obj : objects) {
            // 剔除对象内部的真实回车换行（杂散换行），保留代码块里被转义的 \n 序列
            String cleaned = obj.replaceAll("[\\r\\n]", "");
            if (!MarkdownBlockRenderer.isBlockLine(cleaned)) continue;
            String fragment = MarkdownBlockRenderer.renderLine(cleaned);
            if (null == fragment || fragment.isEmpty()) continue;
            responseBuilder.append(fragment).append("\n\n");
            eventConsumer.accept(tokenEvent("reviewing", fragment + "\n\n", cleaned));
        }
    }

    private String formatMarkdown(String raw, AiWritingTaskStrategy strategy) {
        return MarkdownNormalizer.normalize(raw, strategy.markdownPolicy());
    }

    private AiWritingStreamEventVO statusEvent(String phase, String content) {
        return buildEvent(phase, "status", content);
    }

    private AiWritingStreamEventVO tokenEvent(String phase, String content) {
        return buildEvent(phase, "token", content);
    }

    private AiWritingStreamEventVO tokenEvent(String phase, String content, String raw) {
        return AiWritingStreamEventVO.builder()
                .phase(phase)
                .chunk(AiWritingStreamEventVO.Chunk.builder().type("token").content(content).raw(raw).build())
                .build();
    }

    private AiWritingStreamEventVO buildEvent(String phase, String type, String content) {
        return AiWritingStreamEventVO.builder()
                .phase(phase)
                .chunk(AiWritingStreamEventVO.Chunk.builder().type(type).content(content).build())
                .build();
    }
}
