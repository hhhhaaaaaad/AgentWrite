package cn.sutone.ai.test.contract;

import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.model.exception.MemoryEvalFencingException;
import cn.sutone.ai.domain.agent.model.valobj.GovernanceDecision;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.domain.agent.service.memory.MemoryEvalGuardService;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.memory.MemoryRetriever;
import cn.sutone.ai.domain.agent.service.memory.circuit.MemoryCircuitBreaker;
import cn.sutone.ai.domain.agent.service.memory.governance.MemoryGovernanceComputeService;
import cn.sutone.ai.trigger.http.MemoryEvalController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * eval 端点 HTTP 契约测试。
 *
 * <p>用 MockMvc standaloneSetup 固定端点契约——<b>无需 Spring 上下文、无需 MySQL/Qdrant</b>。
 * 覆盖四类契约：正常响应结构 / 参数校验失败 / 业务（fencing）拒绝 / 命名空间越界。</p>
 *
 * <p>命名空间边界值矩阵见 {@link MemoryEvalControllerNamespaceTest}，本类不重复。</p>
 */
@DisplayName("eval 端点契约测试")
@ExtendWith(MockitoExtension.class)
class EvalEndpointContractTest {

    private static final long BASE = 9_000_000_000L;
    private static final long RANGE = 1_000_000L;
    private static final long USER = BASE + 1;
    private static final String RUN_ID = "550e8400-e29b-41d4-a716-446655440000";

    private static final String SUCCESS = "0000";
    private static final String INVALID = "E0422";
    private static final String FORBIDDEN = "E0403";

    @Mock
    private MemoryManager memoryManager;
    @Mock
    private IEvalFencingRepository evalFencingRepository;
    @Mock
    private IMemoryMetricsPort metrics;
    @Mock
    private MemoryCircuitBreaker circuitBreaker;
    @Mock
    private MemoryEvalGuardService evalGuardService;
    @Mock
    private MemoryGovernanceComputeService governanceComputeService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        MemoryProperties properties = new MemoryProperties();
        MemoryProperties.Eval eval = new MemoryProperties.Eval();
        eval.setEnabled(true);
        eval.setBaseUserId(BASE);
        eval.setUserIdRange(RANGE);
        properties.setEval(eval);

        MemoryEvalController controller = new MemoryEvalController();
        setField(controller, "memoryManager", memoryManager);
        setField(controller, "memoryProperties", properties);
        setField(controller, "evalFencingRepository", evalFencingRepository);
        setField(controller, "metrics", metrics);
        setField(controller, "circuitBreaker", circuitBreaker);
        setField(controller, "evalGuardService", evalGuardService);
        setField(controller, "governanceComputeService", governanceComputeService);

        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Nested
    @DisplayName("只读观测端点")
    class ReadOnlyEndpoints {

        @Test
        @DisplayName("/params 返回参数快照并在 data 中携带检索/注入参数")
        void paramsReturnsSnapshot() throws Exception {
            mockMvc.perform(get("/api/v1/eval/params"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.rrfK").isNumber())
                    .andExpect(jsonPath("$.data.alpha").isNumber())
                    .andExpect(jsonPath("$.data.beta").isNumber())
                    .andExpect(jsonPath("$.data.injectMaxTokens").isNumber())
                    .andExpect(jsonPath("$.data.vectorStore").isString());
        }

        @Test
        @DisplayName("/metrics 返回抽取驳回率与向量积压数")
        void metricsReturnsObservabilityValues() throws Exception {
            when(metrics.getExtractionRejectRate()).thenReturn(0.125);
            when(metrics.getVectorSyncPendingCount()).thenReturn(42L);

            mockMvc.perform(get("/api/v1/eval/metrics"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.extractionRejectRate").value(0.125))
                    .andExpect(jsonPath("$.data.vectorSyncPendingCount").value(42));
        }

        @Test
        @DisplayName("/circuit-breaker 返回降级布尔值")
        void circuitBreakerReturnsDegradedFlag() throws Exception {
            when(circuitBreaker.isDegraded()).thenReturn(true);

            mockMvc.perform(get("/api/v1/eval/circuit-breaker"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data").value(true));
        }
    }

    @Nested
    @DisplayName("search 端点")
    class SearchEndpoint {

        @Test
        @DisplayName("正常响应：items 携带 content 供 contentHash 匹配")
        void returnsItemsWithContent() throws Exception {
            when(memoryManager.searchForEval(eq(USER), anyString(), anyInt(), anyDouble(),
                    anyBoolean(), any(), any()))
                    .thenReturn(List.of(new MemoryRetriever.MemoryItem(
                            7L, "记忆内容", 0.91, 0.5, MemoryTypeVO.FACT, 0.8, null, null)));

            mockMvc.perform(post("/api/v1/eval/search")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + ",\"query\":\"查询\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.items[0].id").value(7))
                    .andExpect(jsonPath("$.data.items[0].content").value("记忆内容"))
                    .andExpect(jsonPath("$.data.items[0].type").value("fact"));
        }

        @Test
        @DisplayName("空 query 返回 EVAL_INVALID 且不触达检索")
        void blankQueryIsRejected() throws Exception {
            mockMvc.perform(post("/api/v1/eval/search")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + ",\"query\":\"  \"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(INVALID));
        }
    }

    @Nested
    @DisplayName("seed / reset 破坏性端点")
    class DestructiveEndpoints {

        @Test
        @DisplayName("seed 缺少 X-Eval-Run-Id 时被框架拒绝（必填 header）")
        void seedRequiresRunIdHeader() throws Exception {
            mockMvc.perform(post("/api/v1/eval/seed")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + ",\"items\":[]}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("seed 空 items 返回 EVAL_INVALID")
        void seedRejectsEmptyItems() throws Exception {
            mockMvc.perform(post("/api/v1/eval/seed")
                            .contentType(APPLICATION_JSON)
                            .header("X-Eval-Run-Id", RUN_ID)
                            .content("{\"evalUserId\":" + USER + ",\"items\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(INVALID));
        }

        @Test
        @DisplayName("seed 正常响应：返回 inserted / existed / contentToId")
        void seedReturnsInsertedAndExisted() throws Exception {
            when(evalGuardService.seedGuarded(eq(USER), eq(RUN_ID), any(), any()))
                    .thenReturn(new MemoryEvalGuardService.SeedOutcome(2, 1,
                            java.util.Map.of("新语料", 11L, "旧语料", 12L)));

            mockMvc.perform(post("/api/v1/eval/seed")
                            .contentType(APPLICATION_JSON)
                            .header("X-Eval-Run-Id", RUN_ID)
                            .content("{\"evalUserId\":" + USER
                                    + ",\"items\":[{\"type\":\"fact\",\"content\":\"新语料\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.inserted").value(2))
                    .andExpect(jsonPath("$.data.existed").value(1))
                    .andExpect(jsonPath("$.data.contentToId.新语料").value(11));
        }

        @Test
        @DisplayName("seed fencing 校验失败映射为 E0403")
        void seedFencingFailureMapsToForbidden() throws Exception {
            when(evalGuardService.seedGuarded(eq(USER), eq(RUN_ID), any(), any()))
                    .thenThrow(new MemoryEvalFencingException("run_id 不匹配"));

            mockMvc.perform(post("/api/v1/eval/seed")
                            .contentType(APPLICATION_JSON)
                            .header("X-Eval-Run-Id", RUN_ID)
                            .content("{\"evalUserId\":" + USER
                                    + ",\"items\":[{\"type\":\"fact\",\"content\":\"语料\"}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(FORBIDDEN));
        }

        @Test
        @DisplayName("reset 正常响应：返回 mysqlDeleted 与 vectorCleared")
        void resetReturnsDeletionStats() throws Exception {
            when(evalGuardService.resetGuarded(eq(USER), eq(RUN_ID), any())).thenReturn(5);

            mockMvc.perform(post("/api/v1/eval/reset")
                            .contentType(APPLICATION_JSON)
                            .header("X-Eval-Run-Id", RUN_ID)
                            .content("{\"evalUserId\":" + USER + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.mysqlDeleted").value(5))
                    .andExpect(jsonPath("$.data.vectorCleared").value(true));
        }

        @Test
        @DisplayName("reset fencing 校验失败映射为 E0403")
        void resetFencingFailureMapsToForbidden() throws Exception {
            when(evalGuardService.resetGuarded(eq(USER), eq(RUN_ID), any()))
                    .thenThrow(new MemoryEvalFencingException("无 fencing 行"));

            mockMvc.perform(post("/api/v1/eval/reset")
                            .contentType(APPLICATION_JSON)
                            .header("X-Eval-Run-Id", RUN_ID)
                            .content("{\"evalUserId\":" + USER + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(FORBIDDEN));
        }
    }

    @Nested
    @DisplayName("fencing 端点")
    class FencingEndpoints {

        @Test
        @DisplayName("GET fencing 返回权威态哨兵")
        void getReturnsSentinel() throws Exception {
            when(evalFencingRepository.get(USER))
                    .thenReturn(new IEvalFencingRepository.EvalFencingState(3L, RUN_ID));

            mockMvc.perform(get("/api/v1/eval/fencing/" + USER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.fencingVersion").value(3))
                    .andExpect(jsonPath("$.data.activeRunId").value(RUN_ID));
        }

        @Test
        @DisplayName("acquire 返回 acquired 与版本")
        void acquireReturnsResult() throws Exception {
            when(evalFencingRepository.acquire(USER, 0L, RUN_ID))
                    .thenReturn(new IEvalFencingRepository.AcquireResult(true, 1L));

            mockMvc.perform(post("/api/v1/eval/fencing/acquire")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + ",\"expectedVersion\":0,\"runId\":\""
                                    + RUN_ID + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.acquired").value(true))
                    .andExpect(jsonPath("$.data.version").value(1));
        }

        @Test
        @DisplayName("release 返回是否释放成功")
        void releaseReturnsBoolean() throws Exception {
            when(evalFencingRepository.release(USER, RUN_ID)).thenReturn(true);

            mockMvc.perform(post("/api/v1/eval/fencing/release")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + ",\"runId\":\"" + RUN_ID + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data").value(true));
        }
    }

    @Nested
    @DisplayName("governance 端点")
    class GovernanceEndpoints {

        @Test
        @DisplayName("replay 返回决策列表且只调 compute 层")
        void replayReturnsDecisions() throws Exception {
            when(governanceComputeService.computeExpired())
                    .thenReturn(List.of(new GovernanceDecision("ARCHIVE", null, "过期",
                            List.of(new GovernanceDecision.Item(9L,
                                    MemoryStatus.ACTIVE.getCode(), MemoryStatus.ARCHIVED.getCode())))));

            mockMvc.perform(post("/api/v1/eval/governance/replay")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER
                                    + ",\"duplicates\":false,\"consistency\":false,"
                                    + "\"expired\":true,\"hallucination\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data[0].action").value("ARCHIVE"))
                    .andExpect(jsonPath("$.data[0].items[0].memoryId").value(9))
                    .andExpect(jsonPath("$.data[0].items[0].afterStatus").value("ARCHIVED"));
        }

        @Test
        @DisplayName("samples 返回四类样本桶")
        void samplesReturnsBuckets() throws Exception {
            when(governanceComputeService.samples(anyLong()))
                    .thenReturn(new MemoryGovernanceComputeService.GovernanceSamples(
                            new MemoryGovernanceComputeService.GovernanceSampleBucket(1,
                                    List.of(new MemoryGovernanceComputeService.GovernanceSampleItem(
                                            1L, USER, "fact", "内容", "ACTIVE", null, null, null, null))),
                            new MemoryGovernanceComputeService.GovernanceSampleBucket(0, List.of()),
                            new MemoryGovernanceComputeService.GovernanceSampleBucket(0, List.of()),
                            new MemoryGovernanceComputeService.GovernanceSampleBucket(0, List.of())));

            mockMvc.perform(post("/api/v1/eval/governance/samples")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.duplicates.total").value(1))
                    .andExpect(jsonPath("$.data.duplicates.items[0].memoryId").value(1))
                    .andExpect(jsonPath("$.data.consistency.total").value(0));
        }
    }

    @Nested
    @DisplayName("extract / retrieve-context 端点")
    class ExtractionEndpoints {

        @Test
        @DisplayName("extract 空 messages 返回 EVAL_INVALID")
        void extractRejectsEmptyMessages() throws Exception {
            mockMvc.perform(post("/api/v1/eval/extract")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + ",\"messages\":[]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(INVALID));
        }

        @Test
        @DisplayName("retrieve-context 空 queryContext 返回 EVAL_INVALID")
        void retrieveContextRejectsBlankQuery() throws Exception {
            mockMvc.perform(post("/api/v1/eval/retrieve-context")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + ",\"queryContext\":\"\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(INVALID));
        }

        @Test
        @DisplayName("retrieve-context 正常响应携带 formatted / tokenCount / budgetedIds")
        void retrieveContextReturnsBudgetDetail() throws Exception {
            when(memoryManager.retrieveContextForEval(eq(USER), anyString(), anyInt()))
                    .thenReturn(new MemoryRetriever.RetrieveContextResult(
                            List.of(new MemoryRetriever.MemoryItem(
                                    3L, "上下文", 0.9, 0.5, MemoryTypeVO.FACT, 0.8, null, null)),
                            "<memory_context>上下文</memory_context>", 128));

            mockMvc.perform(post("/api/v1/eval/retrieve-context")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + USER + ",\"queryContext\":\"草稿\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(SUCCESS))
                    .andExpect(jsonPath("$.data.tokenCount").value(128))
                    .andExpect(jsonPath("$.data.budgetedIds[0]").value(3))
                    .andExpect(jsonPath("$.data.formatted").isString());
        }
    }

    @Nested
    @DisplayName("跨端点：命名空间越界")
    class NamespaceGuard {

        @Test
        @DisplayName("越界 evalUserId 在所有端点统一返回 E0403")
        void outOfRangeIsForbiddenEverywhere() throws Exception {
            long outOfRange = BASE + RANGE + 1;

            mockMvc.perform(post("/api/v1/eval/search")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + outOfRange + ",\"query\":\"q\"}"))
                    .andExpect(jsonPath("$.code").value(FORBIDDEN));

            mockMvc.perform(get("/api/v1/eval/fencing/" + outOfRange))
                    .andExpect(jsonPath("$.code").value(FORBIDDEN));

            mockMvc.perform(post("/api/v1/eval/governance/samples")
                            .contentType(APPLICATION_JSON)
                            .content("{\"evalUserId\":" + outOfRange + "}"))
                    .andExpect(jsonPath("$.code").value(FORBIDDEN));
        }
    }
}
