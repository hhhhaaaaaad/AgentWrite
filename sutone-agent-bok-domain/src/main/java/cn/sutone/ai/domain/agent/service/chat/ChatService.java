package cn.sutone.ai.domain.agent.service.chat;

import cn.sutone.ai.domain.agent.adapter.repository.IChatMessageRepository;
import cn.sutone.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.sutone.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.sutone.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.sutone.ai.domain.agent.model.valobj.UserModelConfigVO;
import cn.sutone.ai.domain.agent.model.valobj.properties.AiAgentAutoConfigProperties;
import cn.sutone.ai.domain.agent.service.IChatService;
import cn.sutone.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.sutone.ai.types.enums.ResponseCode;
import cn.sutone.ai.types.exception.AppException;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class ChatService implements IChatService {

    @Resource
    private DefaultArmoryFactory defaultArmoryFactory;

    @Resource
    private AiAgentAutoConfigProperties aiAgentAutoConfigProperties;

    @Resource
    private IChatMessageRepository chatMessageRepository;

    private final Map<String, String> userSessions = new ConcurrentHashMap<>();

    @Override
    public List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList() {
        Map<String, AiAgentConfigTableVO> tables = aiAgentAutoConfigProperties.getTables();

        List<AiAgentConfigTableVO.Agent> agentList = new ArrayList<>();
        if (null != tables) {
            for (AiAgentConfigTableVO vo : tables.values()) {
                if (null != vo.getAgent()) {
                    agentList.add(vo.getAgent());
                }
            }
        }

        return agentList;
    }

    @Override
    public String createSession(String agentId, String userId) {
        return createSession(agentId, userId, true);
    }

    @Override
    public String createSession(String agentId, String userId, boolean recoverHistory) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String appName = aiAgentRegisterVO.getAppName();
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Session session = runner.sessionService().createSession(appName, userId)
                .blockingGet();
        
        String sessionId = session.id();
        // Update cache so subsequent handleMessage calls without sessionId can use this new session
        String cacheKey = userId + "_" + agentId;
        userSessions.put(cacheKey, sessionId);
        
        // 注入历史对话上下文（一次性写作任务应跳过，避免历史正文污染上下文）
        if (recoverHistory) {
            recoverHistoryContext(userId, agentId, null, aiAgentRegisterVO, sessionId);
        }
        
        return sessionId;
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        String cacheKey = userId + "_" + agentId;
        String sessionId = userSessions.get(cacheKey);
        if (sessionId == null) {
            sessionId = createSession(agentId, userId);
            // createSession 内部已调用 recoverHistoryContext 注入历史上下文
        }

        return handleMessage(agentId, userId, sessionId, message);
    }

    /** 重启后从 chat_message 恢复上下文（按 userId+agentId 过滤） */
    private void recoverHistoryContext(String userId, String agentId, String currentMessage,
                                       AiAgentRegisterVO vo, String newSessionId) {
        try {
            Long uid = null;
            try { uid = Long.parseLong(userId); } catch (NumberFormatException ignored) {}
            if (uid == null) return;

            List<String> history = chatMessageRepository.getLastMessagesByUserAgent(uid, agentId, 20);
            if (history.isEmpty()) return;

            InMemoryRunner runner = vo.getRunner();
            // 将历史消息前缀拼入新 session 的 context
            String prefix = "【历史对话上下文】\n" + String.join("\n", history);
            if (currentMessage != null && !currentMessage.isBlank()) {
                prefix += "\n\n【当前消息】\n";
            }
            Content prefixContent = Content.fromParts(Part.fromText(prefix));
            runner.runAsync(userId, newSessionId, prefixContent).blockingForEach(e -> {});
            log.info("Session 恢复: userId={} agentId={} 恢复 {} 条历史", userId, agentId, history.size());
        } catch (Exception e) {
            log.warn("Session 恢复失败 userId={} agentId={}: {}", userId, agentId, e.getMessage());
        }
    }

    @Override
    public List<String> handleMessage(String agentId, String userId, String sessionId, String message) {

        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 持久化用户消息
        persistMessage(userId, sessionId, agentId, "user", message);

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Content userMsg = Content.fromParts(Part.fromText(message));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        // 持久化 AI 回复
        String response = String.join("\n", outputs);
        if (!response.isBlank()) {
            persistMessage(userId, sessionId, agentId, "assistant", response);
        }

        return outputs;
    }

    // 全局单例的 runner
    @Override
    public Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(agentId);

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        // 持久化用户消息
        persistMessage(userId, sessionId, agentId, "user", message);

        InMemoryRunner runner = aiAgentRegisterVO.getRunner();
        // 将消息包装为 Google ADK 的 Content 对象
        Content userMsg = Content.fromParts(Part.fromText(message));
        // 告诉 ADK 用 SSE 模式接收模型响应（逐 token 推送，不是一次性返回）
        RunConfig runConfig = RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build();

        // 收集 AI 回复并持久化
        StringBuilder aiResponse = new StringBuilder();
        // 三个 RxJava 操作符：doOnNext、doOnComplete、doOnError
        return runner.runAsync(userId, sessionId, userMsg, runConfig)
                .doOnNext(event -> {
                    String content = event.stringifyContent();
                    if (content != null && !content.isBlank()) {
                        aiResponse.append(content);
                    }
                })
                .doOnComplete(() -> {
                    String response = aiResponse.toString();
                    if (!response.isBlank()) {
                        persistMessage(userId, sessionId, agentId, "assistant", response);
                    }
                })
                .doOnError(error -> {
                    log.error("流式对话异常 sessionId={} agentId={}: {}", sessionId, agentId, error.getMessage(), error);
                    String response = aiResponse.toString();
                    if (!response.isBlank()) {
                        persistMessage(userId, sessionId, agentId, "assistant", response);
                    }
                });
    }

    @Override
    public Flowable<Event> handleMessageStreamWithConfig(String agentId, String userId, String sessionId,
                                                          String message, UserModelConfigVO userConfig) {
        // 无用户配置，降级到全局单例 Runner 路径，sessionId 由外部创建，合法
        if (userConfig == null) {
            return handleMessageStream(agentId, userId, sessionId, message);
        }

        // 多租户：动态装配，使用用户自定义 API Key 和模型
        log.info("多租户动态装配 agentId={} userId={} configId={}", agentId, userId, userConfig.configId());

        // 查找该 agentId 对应的配置表
        AiAgentConfigTableVO configTable = aiAgentAutoConfigProperties.getTables().values().stream()
                .filter(t -> t.getAgent() != null && agentId.equals(t.getAgent().getAgentId()))
                .findFirst()
                .orElseThrow(() -> new AppException(ResponseCode.E0001.getCode(), "未找到 agentId=" + agentId + " 的配置"));

        // 走策略树动态装配，注入用户模型配置
        ArmoryCommandEntity command = ArmoryCommandEntity.builder()
                .aiAgentConfigTableVO(configTable)
                .userModelConfig(userConfig)
                .build();

        try {
            AiAgentRegisterVO registerVO = defaultArmoryFactory.armoryStrategyHandler()
                    .apply(command, new DefaultArmoryFactory.DynamicContext());

            InMemoryRunner runner = registerVO.getRunner();

            // 动态 Runner 拥有独立的 InMemorySessionService，外部传入的 sessionId 是在默认 Runner
            // 的 SessionService 中创建的，动态 Runner 无法识别。必须用动态 Runner 自己的
            // SessionService 创建新 Session，保证 createSession 和 runAsync 使用同一个 SessionService。
            String dynamicSessionId = runner.sessionService()
                    .createSession(registerVO.getAppName(), userId)
                    .blockingGet()
                    .id();

            persistMessage(userId, dynamicSessionId, agentId, "user", message);

            Content userMsg = Content.fromParts(Part.fromText(message));
            RunConfig runConfig = RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build();

            StringBuilder aiResponse = new StringBuilder();
            return runner.runAsync(userId, dynamicSessionId, userMsg, runConfig)
                    .doOnNext(event -> {
                        String content = event.stringifyContent();
                        if (content != null && !content.isBlank()) {
                            aiResponse.append(content);
                        }
                    })
                    .doOnComplete(() -> {
                        String response = aiResponse.toString();
                        if (!response.isBlank()) {
                            persistMessage(userId, dynamicSessionId, agentId, "assistant", response);
                        }
                    })
                    .doOnError(error -> {
                        log.error("多租户流式对话异常 sessionId={} agentId={}: {}", dynamicSessionId, agentId, error.getMessage(), error);
                        String response = aiResponse.toString();
                        if (!response.isBlank()) {
                            persistMessage(userId, dynamicSessionId, agentId, "assistant", response);
                        }
                    });
        } catch (Exception e) {
            log.error("多租户动态装配失败 agentId={} configId={}: {}", agentId, userConfig.configId(), e.getMessage(), e);
            throw new AppException(ResponseCode.E0001.getCode(), "动态装配 Agent 失败: " + e.getMessage());
        }
    }

    /** 持久化对话消息，不影响主流程 */
    private void persistMessage(String userId, String sessionId, String agentId, String role, String content) {
        try {
            Long uid;
            try {
                uid = Long.parseLong(userId);
            } catch (NumberFormatException e) {
                // 非数字 userId（如 "admin"），使用 0L 作为占位
                log.warn("对话消息持久化: userId 非数字, 使用 0L 占位, userId={}, sessionId={}, role={}", userId, sessionId, role);
                uid = 0L;
            }
            chatMessageRepository.save(uid, sessionId, agentId, role, content);
        } catch (Exception e) {
            log.error("对话消息持久化失败 userId={} sessionId={} role={}: {}", userId, sessionId, role, e.getMessage(), e);
        }
    }

    @Override
    public List<String> handleMessage(ChatCommandEntity chatCommandEntity) {
        AiAgentRegisterVO aiAgentRegisterVO = defaultArmoryFactory.getAiAgentRegisterVO(chatCommandEntity.getAgentId());

        if (null == aiAgentRegisterVO) {
            throw new AppException(ResponseCode.E0001.getCode());
        }

        List<Part> parts = new ArrayList<>();
        StringBuilder userContentBuilder = new StringBuilder();

        List<ChatCommandEntity.Content.Text> texts = chatCommandEntity.getTexts();
        if (null != texts && !texts.isEmpty()) {
            for (ChatCommandEntity.Content.Text text : texts) {
                parts.add(Part.fromText(text.getMessage()));
                userContentBuilder.append(text.getMessage());
            }
        }

        List<ChatCommandEntity.Content.File> files = chatCommandEntity.getFiles();
        if (null != files && !files.isEmpty()) {
            for (ChatCommandEntity.Content.File file : files) {
                parts.add(Part.fromUri(file.getFileUri(), file.getMimeType()));
            }
        }

        List<ChatCommandEntity.Content.InlineData> inlineDatas = chatCommandEntity.getInlineDatas();
        if (null != inlineDatas && !inlineDatas.isEmpty()) {
            for (ChatCommandEntity.Content.InlineData inlineData : inlineDatas) {
                parts.add(Part.fromBytes(inlineData.getBytes(), inlineData.getMimeType()));
            }
        }

        Content content = Content.builder().role("user").parts(parts).build();

        // 持久化用户消息
        String userContent = userContentBuilder.toString();
        if (!userContent.isBlank()) {
            persistMessage(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(),
                    chatCommandEntity.getAgentId(), "user", userContent);
        }

        // 获取运行体
        InMemoryRunner runner = aiAgentRegisterVO.getRunner();

        Flowable<Event> events = runner.runAsync(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(), content);

        List<String> outputs = new ArrayList<>();
        events.blockingForEach(event -> outputs.add(event.stringifyContent()));

        // 持久化 AI 回复
        String response = String.join("\n", outputs);
        if (!response.isBlank()) {
            persistMessage(chatCommandEntity.getUserId(), chatCommandEntity.getSessionId(),
                    chatCommandEntity.getAgentId(), "assistant", response);
        }

        return outputs;
    }

}
