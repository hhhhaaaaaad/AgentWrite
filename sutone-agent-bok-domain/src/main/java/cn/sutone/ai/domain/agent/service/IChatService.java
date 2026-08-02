package cn.sutone.ai.domain.agent.service;

import cn.sutone.ai.domain.agent.model.entity.ChatCommandEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.sutone.ai.domain.agent.model.valobj.UserModelConfigVO;
import com.google.adk.events.Event;
import io.reactivex.rxjava3.core.Flowable;

import java.util.List;

/**
 * 对话接口
 *
 */
public interface IChatService {

    List<AiAgentConfigTableVO.Agent> queryAiAgentConfigList();

    String createSession(String agentId, String userId);

    /**
     * 创建会话。
     *
     * @param recoverHistory 是否注入该 user+agent 的历史对话上下文。
     *                       多轮对话场景传 true；AI 写作快捷操作等一次性任务应传 false，
     *                       避免历史正文污染上下文导致模型误判"文章已完整"而返回对话式回复。
     */
    String createSession(String agentId, String userId, boolean recoverHistory);

    List<String> handleMessage(String agentId, String userId, String message);

    List<String> handleMessage(String agentId, String userId, String sessionId, String message);

    Flowable<Event> handleMessageStream(String agentId, String userId, String sessionId, String message);

    /**
     * 使用指定模型配置执行流式对话（多租户场景）
     * <p>当 userConfig 不为 null 时，动态构建 Runner 使用用户自定义的 API Key 和模型；
     * 为 null 时降级到全局单例 Runner。</p>
     */
    Flowable<Event> handleMessageStreamWithConfig(String agentId, String userId, String sessionId,
                                                   String message, UserModelConfigVO userConfig);

    List<String> handleMessage(ChatCommandEntity chatCommandEntity);

}
