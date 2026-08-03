package cn.sutone.ai.test.domain.agent.service.ai_writing;

import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingTaskTypeVO;
import cn.sutone.ai.domain.agent.service.IChatService;
import cn.sutone.ai.domain.agent.service.ai_writing.AgentWritingRunner;
import cn.sutone.ai.domain.agent.service.ai_writing.strategy.AiWritingTaskStrategyResolver;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AgentWritingRunner 单元测试")
@ExtendWith(MockitoExtension.class)
class AgentWritingRunnerTest {

    private static final Long USER_ID = 10001L;
    private static final Long DRAFT_ID = 20001L;
    private static final String WORKFLOW_AGENT_ID = "300002";
    private static final String SINGLE_AGENT_ID = "300005";

    @Mock
    private IChatService chatService;

    @Mock
    private cn.sutone.ai.domain.agent.service.userconfig.UserModelConfigService userModelConfigService;

    private AgentWritingRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AgentWritingRunner(chatService, new AiWritingTaskStrategyResolver(), userModelConfigService);
        lenient().when(chatService.createSession(eq(WORKFLOW_AGENT_ID), eq(String.valueOf(USER_ID)), eq(false)))
                .thenReturn("session-wf");
        lenient().when(chatService.createSession(eq(SINGLE_AGENT_ID), eq(String.valueOf(USER_ID)), eq(false)))
                .thenReturn("session-single");
    }

    @Test
    @DisplayName("短文本任务（SUMMARIZE）应使用单 Agent 300005 直调，不经过 workflow")
    void shouldUseSingleAgentForShortTextTask() {
        AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.SUMMARIZE, "测试 prompt", true);
        Event singleAgentEvent = Event.builder()
                .author("agent_writing_single")
                .content(Content.fromParts(Part.fromText("本文介绍 RocketMQ 的可靠投递机制。")))
                .build();
        when(chatService.handleMessageStreamWithConfig(eq(SINGLE_AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-single"), anyString(), any()))
                .thenReturn(Flowable.just(singleAgentEvent));

        String result = runner.run(task, event -> {});

        assertEquals("本文介绍 RocketMQ 的可靠投递机制。", result);
        // 验证未调用 workflow agent 和配图 agent
        verify(chatService, never()).handleMessageStreamWithConfig(eq(WORKFLOW_AGENT_ID), anyString(), anyString(), anyString(), any());
        verify(chatService, never()).handleMessage(eq("300003"), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("正文任务（GENERATE_BODY）仍应使用 workflow agent 300002")
    void shouldUseWorkflowForArticleTask() {
        AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_BODY, "测试 prompt", false);
        Event generatorEvent = Event.builder()
                .author("agent_writing_generator")
                .content(Content.fromParts(Part.fromText("generator 初稿")))
                .build();
        Event reviewerEvent = Event.builder()
                .author("agent_writing_reviewer")
                .content(Content.fromParts(Part.fromText("reviewer 终稿")))
                .build();
        when(chatService.handleMessageStreamWithConfig(eq(WORKFLOW_AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-wf"), anyString(), any()))
                .thenReturn(Flowable.just(generatorEvent, reviewerEvent));

        String result = runner.run(task, event -> {});

        assertEquals("reviewer 终稿", result);
        assertFalse(result.contains("generator 初稿"));
    }

    @Test
    @DisplayName("reviewer 输出结构化块时，标题与正文应被渲染成分行")
    void shouldRenderStructuredBlocksSeparatingHeadingAndBody() {
        AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_BODY, "测试 prompt", false);
        String blocks = "{\"type\":\"md_heading\",\"level\":3,\"text\":\"2.1 单体架构的演进\"}\n"
                + "{\"type\":\"md_paragraph\",\"text\":\"单体架构并非一无是处，在初创期能快速迭代。\"}\n"
                + "{\"type\":\"md_done\"}";
        Event reviewerEvent = Event.builder()
                .author("agent_writing_reviewer")
                .content(Content.fromParts(Part.fromText(blocks)))
                .build();
        when(chatService.handleMessageStreamWithConfig(eq(WORKFLOW_AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-wf"), anyString(), any()))
                .thenReturn(Flowable.just(reviewerEvent));

        String result = runner.run(task, event -> {});

        assertTrue(result.contains("### 2.1 单体架构的演进"), "应渲染为三级标题: " + result);
        assertTrue(result.contains("单体架构并非一无是处"), "应包含正文: " + result);
        String headingLine = result.lines().filter(l -> l.startsWith("#")).findFirst().orElse("");
        assertFalse(headingLine.contains("单体架构并非"), "标题行不应混入正文: " + headingLine);
        assertFalse(result.contains("md_heading"), "不应残留原始 JSON: " + result);
    }

    @Test
    @DisplayName("块 JSON 内被模型插入杂散换行时，仍应正确渲染且不泄漏原始 JSON")
    void shouldRenderBlocksWithStrayNewlinesInsideJson() {
        AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_BODY, "测试 prompt", false);
        String blocks = "{\"type\":\"md_heading\",\"level\":3,\"text\":\"1.\n1微服务架构背景\"}\n"
                + "{\"type\":\"md_paragraph\",\"text\":\"过去十年间业务爆发式增长。\"}\n"
                + "{\"type\":\"md_done\"}";
        Event reviewerEvent = Event.builder()
                .author("agent_writing_reviewer")
                .content(Content.fromParts(Part.fromText(blocks)))
                .build();
        when(chatService.handleMessageStreamWithConfig(eq(WORKFLOW_AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-wf"), anyString(), any()))
                .thenReturn(Flowable.just(reviewerEvent));

        String result = runner.run(task, event -> {});

        assertFalse(result.contains("md_heading"), "不应泄漏原始 JSON: " + result);
        assertFalse(result.contains("\"type\""), "不应泄漏原始 JSON: " + result);
        assertTrue(result.contains("### 1.1微服务架构背景"), "杂散换行应被剔除并正确渲染标题: " + result);
        assertTrue(result.contains("过去十年间业务爆发式增长"), "应包含正文: " + result);
    }
}
