package cn.sutone.ai.test.domain.agent.service.ai_writing;

import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
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

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AgentWritingRunner 单元测试")
@ExtendWith(MockitoExtension.class)
class AgentWritingRunnerTest {

    private static final Long USER_ID = 10001L;
    private static final Long DRAFT_ID = 20001L;
    private static final String AGENT_ID = "300002";

    @Mock
    private IChatService chatService;

    private AgentWritingRunner runner;

    @BeforeEach
    void setUp() {
        runner = new AgentWritingRunner(chatService, new AiWritingTaskStrategyResolver());
        AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
        agent.setAgentId(AGENT_ID);
        lenient().when(chatService.queryAiAgentConfigList()).thenReturn(Collections.singletonList(agent));
        lenient().when(chatService.createSession(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq(false))).thenReturn("session-1");
    }

    @Test
    @DisplayName("短文本任务应使用 generator 输出并忽略 reviewer 改写")
    void shouldUseGeneratorOutputForShortTextTask() {
        AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.SUMMARIZE, "测试 prompt", true);
        Event analystEvent = Event.builder()
                .author("agent_writing_analyst")
                .content(Content.fromParts(Part.fromText("分析过程")))
                .build();
        Event generatorEvent = Event.builder()
                .author("agent_writing_generator")
                .content(Content.fromParts(Part.fromText("## 摘要\n\n本文介绍 RocketMQ 的可靠投递机制。")))
                .build();
        Event reviewerEvent = Event.builder()
                .author("agent_writing_reviewer")
                .content(Content.fromParts(Part.fromText("被 reviewer 改写后的内容")))
                .build();
        when(chatService.handleMessageStream(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-1"), anyString()))
                .thenReturn(Flowable.just(analystEvent, generatorEvent, reviewerEvent));

        String result = runner.run(task, event -> { });

        assertEquals("本文介绍 RocketMQ 的可靠投递机制。", result);
        assertFalse(result.contains("reviewer"));
        verify(chatService, never()).handleMessage(eq("300003"), eq(String.valueOf(USER_ID)), anyString(), anyString());
    }

    @Test
    @DisplayName("正文任务仍应使用 reviewer 输出作为最终内容")
    void shouldUseReviewerOutputForArticleTask() {
        AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_BODY, "测试 prompt", false);
        Event generatorEvent = Event.builder()
                .author("agent_writing_generator")
                .content(Content.fromParts(Part.fromText("generator 初稿")))
                .build();
        Event reviewerEvent = Event.builder()
                .author("agent_writing_reviewer")
                .content(Content.fromParts(Part.fromText("reviewer 终稿")))
                .build();
        when(chatService.handleMessageStream(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-1"), anyString()))
                .thenReturn(Flowable.just(generatorEvent, reviewerEvent));

        String result = runner.run(task, event -> { });

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
        when(chatService.handleMessageStream(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-1"), anyString()))
                .thenReturn(Flowable.just(reviewerEvent));

        String result = runner.run(task, event -> { });

        assertTrue(result.contains("### 2.1 单体架构的演进"), "应渲染为三级标题: " + result);
        assertTrue(result.contains("单体架构并非一无是处"), "应包含正文: " + result);
        // 标题行不应把正文吞进去
        String headingLine = result.lines().filter(l -> l.startsWith("#")).findFirst().orElse("");
        assertFalse(headingLine.contains("单体架构并非"), "标题行不应混入正文: " + headingLine);
        assertFalse(result.contains("md_heading"), "不应残留原始 JSON: " + result);
    }

    @Test
    @DisplayName("块 JSON 内被模型插入杂散换行时，仍应正确渲染且不泄漏原始 JSON")
    void shouldRenderBlocksWithStrayNewlinesInsideJson() {
        AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_BODY, "测试 prompt", false);
        // 模拟模型在「1.1」处插入真实换行，把一个 JSON 对象拆成了两行
        String blocks = "{\"type\":\"md_heading\",\"level\":3,\"text\":\"1.\n1微服务架构背景\"}\n"
                + "{\"type\":\"md_paragraph\",\"text\":\"过去十年间业务爆发式增长。\"}\n"
                + "{\"type\":\"md_done\"}";
        Event reviewerEvent = Event.builder()
                .author("agent_writing_reviewer")
                .content(Content.fromParts(Part.fromText(blocks)))
                .build();
        when(chatService.handleMessageStream(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-1"), anyString()))
                .thenReturn(Flowable.just(reviewerEvent));

        String result = runner.run(task, event -> { });

        assertFalse(result.contains("md_heading"), "不应泄漏原始 JSON: " + result);
        assertFalse(result.contains("\"type\""), "不应泄漏原始 JSON: " + result);
        assertTrue(result.contains("### 1.1微服务架构背景"), "杂散换行应被剔除并正确渲染标题: " + result);
        assertTrue(result.contains("过去十年间业务爆发式增长"), "应包含正文: " + result);
    }
}
