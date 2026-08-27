package cn.sutone.ai.test.domain.agent.service.ai_writing;

import cn.sutone.ai.domain.agent.adapter.repository.IAiTaskRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IOutboxEventRepository;
import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.entity.OutboxEventEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.sutone.ai.domain.agent.model.valobj.AiTaskStatusVO;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingStreamEventVO;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingTaskTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO;
import cn.sutone.ai.domain.agent.service.IChatService;
import cn.sutone.ai.domain.agent.service.ITaskEventPublisher;
import cn.sutone.ai.domain.agent.service.ai_writing.AgentWritingRunner;
import cn.sutone.ai.domain.agent.service.ai_writing.AiWritingService;
import cn.sutone.ai.domain.agent.service.ai_writing.strategy.AiWritingTaskStrategy;
import cn.sutone.ai.domain.agent.service.ai_writing.strategy.AiWritingTaskStrategyResolver;
import cn.sutone.ai.domain.agent.model.valobj.MarkdownPolicyVO;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.ratelimit.RateLimitService;
import cn.sutone.ai.domain.content.model.entity.DraftEntity;
import cn.sutone.ai.domain.content.service.draft.DraftDomainService;
import cn.sutone.ai.types.exception.AppException;
import com.google.adk.events.Event;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AiWritingService 单元测试")
@ExtendWith(MockitoExtension.class)
class AiWritingServiceTest {

    @Mock
    private IChatService chatService;

    @Mock
    private IAiTaskRepository aiTaskRepository;

    @Mock
    private IOutboxEventRepository outboxEventRepository;

    @Mock
    private DraftDomainService draftDomainService;

    @Mock
    private RateLimitService rateLimitService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private MemoryManager memoryManager;

    @Mock
    private AgentWritingRunner agentWritingRunner;

    @Mock
    private ITaskEventPublisher taskEventPublisher;

    @Mock
    private AiWritingTaskStrategyResolver strategyResolver;

    @Mock
    private cn.sutone.ai.domain.agent.adapter.repository.IOutboxImmediatePublisher outboxImmediatePublisher;

    @Mock
    private cn.sutone.ai.domain.agent.service.userconfig.UserModelConfigService userModelConfigService;

    @Mock
    private RLock rLock;

    private AiWritingService aiWritingService;

    private static final Long USER_ID = 10001L;
    private static final Long DRAFT_ID = 20001L;
    private static final Long TASK_ID = 30001L;
    private static final String AGENT_ID = "300002";

    @BeforeEach
    void setUp() throws InterruptedException {
        aiWritingService = new AiWritingService(chatService, aiTaskRepository, outboxEventRepository,
                draftDomainService, rateLimitService, redissonClient, memoryManager,
                agentWritingRunner, taskEventPublisher, strategyResolver, outboxImmediatePublisher,
                userModelConfigService);
        // 通过反射注入 self 字段（模拟 Spring @Lazy 注入的代理对象）
        try {
            java.lang.reflect.Field selfField = AiWritingService.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(aiWritingService, aiWritingService);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject self", e);
        }
        lenient().when(rateLimitService.tryAcquire(anyLong())).thenReturn(true);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(rLock);
        lenient().when(rLock.tryLock(0, 5, TimeUnit.SECONDS)).thenReturn(true);
        // 模拟 save 后通过 @Options(useGeneratedKeys) 写回 taskId
        lenient().doAnswer(invocation -> {
            AiTaskEntity entity = invocation.getArgument(0);
            entity.setTaskId(TASK_ID);
            return TASK_ID;
        }).when(aiTaskRepository).save(any(AiTaskEntity.class));
        // 模拟 outbox save 后写回 eventId
        lenient().doAnswer(invocation -> {
            OutboxEventEntity event = invocation.getArgument(0);
            event.setEventId(1L);
            return null;
        }).when(outboxEventRepository).save(any(OutboxEventEntity.class));
    }

    @Nested
    @DisplayName("submitTask")
    class SubmitTask {

        @Test
        @DisplayName("应创建 PENDING 状态的任务")
        void shouldCreatePendingTask() {
            DraftEntity draft = editingDraft();
            when(draftDomainService.queryDraftDetail(DRAFT_ID, USER_ID)).thenReturn(draft);

            AiTaskEntity result = aiWritingService.submitTask(USER_ID, DRAFT_ID, "GENERATE_OUTLINE", Map.of("title", "Java 入门"), false);

            assertNotNull(result);
            assertEquals(TASK_ID, result.getTaskId()); // 由 mock save 回填
            assertEquals(AiTaskStatusVO.PENDING, result.getStatus());
            assertEquals(AiWritingTaskTypeVO.GENERATE_OUTLINE, result.getTaskType());
            verify(aiTaskRepository).save(any(AiTaskEntity.class));
            verify(outboxEventRepository).save(any(OutboxEventEntity.class));
            verify(outboxEventRepository).updatePayload(eq(1L), anyString());
        }

        @Test
        @DisplayName("草稿不可编辑时应抛出异常")
        void shouldThrowWhenDraftNotEditable() {
            DraftEntity draft = DraftEntity.initNewDraft(DRAFT_ID, USER_ID, "标题", "正文", null, null);
            draft.markPublished();
            when(draftDomainService.queryDraftDetail(DRAFT_ID, USER_ID)).thenReturn(draft);

            assertThrows(AppException.class, () ->
                    aiWritingService.submitTask(USER_ID, DRAFT_ID, "GENERATE_OUTLINE", null, false));
            verify(aiTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("无效的任务类型应抛出异常")
        void shouldThrowWhenInvalidTaskType() {
            DraftEntity draft = editingDraft();
            when(draftDomainService.queryDraftDetail(DRAFT_ID, USER_ID)).thenReturn(draft);

            assertThrows(Exception.class, () ->
                    aiWritingService.submitTask(USER_ID, DRAFT_ID, "INVALID_TYPE", null, false));
            verify(aiTaskRepository, never()).save(any());
        }

        @Test
        @DisplayName("应使用结构化记忆检索 query 而非仅正文")
        void shouldBuildStructuredMemoryQuery() {
            DraftEntity draft = DraftEntity.initNewDraft(DRAFT_ID, USER_ID, "记忆系统设计", "", "介绍混合检索", "https://cover.url");
            when(draftDomainService.queryDraftDetail(DRAFT_ID, USER_ID)).thenReturn(draft);

            aiWritingService.submitTask(USER_ID, DRAFT_ID, "GENERATE_OUTLINE", Map.of("customInstruction", "偏面试表达"), false);

            ArgumentCaptor<MemoryRetrieveQueryVO> captor = ArgumentCaptor.forClass(MemoryRetrieveQueryVO.class);
            verify(memoryManager).retrieveContext(eq(USER_ID), captor.capture(), eq(5));
            MemoryRetrieveQueryVO query = captor.getValue();
            assertEquals("GENERATE_OUTLINE", query.getTaskType());
            assertEquals("记忆系统设计", query.getTitle());
            assertEquals("介绍混合检索", query.getSummary());
            assertEquals("", query.getContentMd());
            assertEquals("偏面试表达", query.getCustomInstruction());
        }

        @Test
        @DisplayName("润色场景应把 selectedText 放入结构化记忆检索 query")
        void shouldIncludeSelectedTextInStructuredMemoryQuery() {
            DraftEntity draft = editingDraft();
            when(draftDomainService.queryDraftDetail(DRAFT_ID, USER_ID)).thenReturn(draft);

            aiWritingService.submitTask(USER_ID, DRAFT_ID, "POLISH_TEXT",
                    Map.of("selectedText", "这里是选中文本", "customInstruction", "更偏面试表达"), false);

            ArgumentCaptor<MemoryRetrieveQueryVO> captor = ArgumentCaptor.forClass(MemoryRetrieveQueryVO.class);
            verify(memoryManager).retrieveContext(eq(USER_ID), captor.capture(), eq(5));
            MemoryRetrieveQueryVO query = captor.getValue();
            assertEquals("POLISH_TEXT", query.getTaskType());
            assertEquals("这里是选中文本", query.getSelectedText());
            assertEquals("更偏面试表达", query.getCustomInstruction());
        }
    }

    @Nested
    @DisplayName("queryTask")
    class QueryTask {

        @Test
        @DisplayName("存在且所有权匹配时应返回任务")
        void shouldReturnTaskWhenExistsAndOwnerMatches() {
            AiTaskEntity task = pendingTask();
            when(aiTaskRepository.queryById(TASK_ID)).thenReturn(task);

            AiTaskEntity result = aiWritingService.queryTask(TASK_ID, USER_ID);

            assertNotNull(result);
            assertEquals(TASK_ID, result.getTaskId());
        }

        @Test
        @DisplayName("任务不存在时应抛出异常")
        void shouldThrowWhenTaskNotExists() {
            when(aiTaskRepository.queryById(TASK_ID)).thenReturn(null);

            assertThrows(AppException.class, () -> aiWritingService.queryTask(TASK_ID, USER_ID));
        }

        @Test
        @DisplayName("所有权不匹配时应抛出异常")
        void shouldThrowWhenOwnerMismatches() {
            AiTaskEntity task = pendingTask();
            when(aiTaskRepository.queryById(TASK_ID)).thenReturn(task);

            assertThrows(AppException.class, () -> aiWritingService.queryTask(TASK_ID, 99999L));
        }
    }

    @Nested
    @DisplayName("generateStream")
    class GenerateStream {

        @Test
        @DisplayName("成功时应完成 PENDING -> RUNNING -> SUCCESS 状态流转")
        void shouldTransitionStatesOnSuccess() {
            AiTaskEntity task = pendingTask();
            when(aiTaskRepository.queryById(TASK_ID)).thenReturn(task);
            AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
            agent.setAgentId(AGENT_ID);
            when(chatService.queryAiAgentConfigList()).thenReturn(Collections.singletonList(agent));
            when(chatService.createSession(AGENT_ID, String.valueOf(USER_ID))).thenReturn("session-1");

            // 只有 reviewer 的输出才进入最终结果，故 author 必须为 agent_writing_reviewer
            Event realEvent = Event.builder()
                    .author("agent_writing_reviewer")
                    .content(Content.fromParts(Part.fromText("生成的内容")))
                    .build();
            when(chatService.handleMessageStream(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-1"), anyString()))
                    .thenReturn(Flowable.just(realEvent));

            AtomicInteger eventCount = new AtomicInteger(0);
            Consumer<AiWritingStreamEventVO> eventConsumer = event -> eventCount.incrementAndGet();

            aiWritingService.generateStream(TASK_ID, USER_ID, eventConsumer);

            // 验证 update 被调用两次（RUNNING + SUCCESS）
            verify(aiTaskRepository, times(2)).update(any(AiTaskEntity.class));
            // 最终状态应为 SUCCESS
            assertEquals(AiTaskStatusVO.SUCCESS, task.getStatus());
            // 自由文本经 MarkdownNormalizer 规范化后仍应保留原文本
            assertEquals("生成的内容", task.getResponseContent());
            // 事件：status + token + result + done
            assertTrue(eventCount.get() >= 4);
        }

        @Test
        @DisplayName("analyst 的输出不应进入最终结果")
        void shouldNotIncludeAnalystOutput() {
            AiTaskEntity task = pendingTask();
            when(aiTaskRepository.queryById(TASK_ID)).thenReturn(task);
            AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
            agent.setAgentId(AGENT_ID);
            when(chatService.queryAiAgentConfigList()).thenReturn(Collections.singletonList(agent));
            when(chatService.createSession(AGENT_ID, String.valueOf(USER_ID))).thenReturn("session-1");

            Event analystEvent = Event.builder()
                    .author("agent_writing_analyst")
                    .content(Content.fromParts(Part.fromText("这是分析过程，不应落库")))
                    .build();
            Event reviewerEvent = Event.builder()
                    .author("agent_writing_reviewer")
                    .content(Content.fromParts(Part.fromText("这是终稿")))
                    .build();
            when(chatService.handleMessageStream(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-1"), anyString()))
                    .thenReturn(Flowable.just(analystEvent, reviewerEvent));

            aiWritingService.generateStream(TASK_ID, USER_ID, event -> { });

            assertEquals(AiTaskStatusVO.SUCCESS, task.getStatus());
            assertEquals("这是终稿", task.getResponseContent());
            assertFalse(task.getResponseContent().contains("分析过程"));
        }

        @Test
        @DisplayName("reviewer 的结构化块 JSON 应被渲染为标准 Markdown 落库")
        void shouldRenderStructuredBlocks() {
            AiTaskEntity task = pendingTask();
            when(aiTaskRepository.queryById(TASK_ID)).thenReturn(task);
            AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
            agent.setAgentId(AGENT_ID);
            when(chatService.queryAiAgentConfigList()).thenReturn(Collections.singletonList(agent));
            when(chatService.createSession(AGENT_ID, String.valueOf(USER_ID))).thenReturn("session-1");

            String blocks = "{\"type\":\"md_heading\",\"level\":2,\"text\":\"标题\"}\n"
                    + "{\"type\":\"md_paragraph\",\"text\":\"正文段落\"}\n"
                    + "{\"type\":\"md_done\"}";
            Event reviewerEvent = Event.builder()
                    .author("agent_writing_reviewer")
                    .content(Content.fromParts(Part.fromText(blocks)))
                    .build();
            when(chatService.handleMessageStream(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-1"), anyString()))
                    .thenReturn(Flowable.just(reviewerEvent));

            aiWritingService.generateStream(TASK_ID, USER_ID, event -> { });

            assertEquals(AiTaskStatusVO.SUCCESS, task.getStatus());
            String result = task.getResponseContent();
            assertTrue(result.contains("## 标题"), "应渲染为二级标题: " + result);
            assertTrue(result.contains("正文段落"), "应包含段落文本: " + result);
            assertFalse(result.contains("md_heading"), "不应残留原始 JSON: " + result);
        }

        @Test
        @DisplayName("流式失败时应标记为 FAILED")
        void shouldMarkFailedOnException() {
            AiTaskEntity task = pendingTask();
            when(aiTaskRepository.queryById(TASK_ID)).thenReturn(task);
            AiAgentConfigTableVO.Agent agent = new AiAgentConfigTableVO.Agent();
            agent.setAgentId(AGENT_ID);
            when(chatService.queryAiAgentConfigList()).thenReturn(Collections.singletonList(agent));
            when(chatService.createSession(AGENT_ID, String.valueOf(USER_ID))).thenReturn("session-1");
            when(chatService.handleMessageStream(eq(AGENT_ID), eq(String.valueOf(USER_ID)), eq("session-1"), anyString()))
                    .thenThrow(new RuntimeException("模型调用超时"));

            AtomicReference<String> errorContent = new AtomicReference<>();
            Consumer<AiWritingStreamEventVO> eventConsumer = event -> {
                if ("error".equals(event.getPhase()) && event.getChunk() != null) {
                    errorContent.set(event.getChunk().getContent());
                }
            };

            aiWritingService.generateStream(TASK_ID, USER_ID, eventConsumer);

            // update 至少被调用 2 次（RUNNING + FAILED）
            verify(aiTaskRepository, atLeast(2)).update(any(AiTaskEntity.class));
            // 最终状态应为 FAILED
            assertEquals(AiTaskStatusVO.FAILED, task.getStatus());
            assertTrue(task.getErrorMsg().contains("模型调用超时"));
            assertEquals("模型调用超时", errorContent.get());
        }
    }

    private static DraftEntity editingDraft() {
        return DraftEntity.initNewDraft(DRAFT_ID, USER_ID, "标题", "正文内容", "摘要", "https://cover.url");
    }

    private static AiTaskEntity pendingTask() {
        AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_OUTLINE, "测试 prompt", false);
        task.setTaskId(TASK_ID);
        return task;
    }
}
