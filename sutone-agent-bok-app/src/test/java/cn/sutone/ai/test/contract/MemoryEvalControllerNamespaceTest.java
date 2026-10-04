package cn.sutone.ai.test.contract;

import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryMetricsPort;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import cn.sutone.ai.domain.agent.service.memory.MemoryEvalGuardService;
import cn.sutone.ai.domain.agent.service.memory.MemoryManager;
import cn.sutone.ai.domain.agent.service.memory.circuit.MemoryCircuitBreaker;
import cn.sutone.ai.domain.agent.service.memory.governance.MemoryGovernanceComputeService;
import cn.sutone.ai.trigger.http.MemoryEvalController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * eval 端点 <b>eval_user_id 命名空间边界</b>测试（IDOR 防护）。
 *
 * <p>用 MockMvc standaloneSetup 驱动——无需 Spring 上下文、无需 MySQL/Qdrant。</p>
 *
 * <p>边界语义：合法区间是<b>左闭右开</b> {@code [baseUserId, baseUserId + userIdRange)}。
 * 越界（含 null）必须返回 EVAL_FORBIDDEN (E0403)，不得落到正常业务逻辑。</p>
 */
@DisplayName("eval 端点命名空间边界测试")
@ExtendWith(MockitoExtension.class)
class MemoryEvalControllerNamespaceTest {

    private static final long BASE = 9_000_000_000L;
    private static final long RANGE = 1_000_000L;
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

    /** 用只读端点 /search 打边界，避免被 fencing header 必填校验干扰 */
    private org.springframework.test.web.servlet.ResultActions searchWith(Long evalUserId) throws Exception {
        String body = evalUserId == null
                ? "{\"query\":\"测试查询\"}"
                : "{\"evalUserId\":" + evalUserId + ",\"query\":\"测试查询\"}";
        return mockMvc.perform(post("/api/v1/eval/search").contentType(APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("下界之下（base-1）被拒绝")
    void rejectsJustBelowBase() throws Exception {
        searchWith(BASE - 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FORBIDDEN));
    }

    @Test
    @DisplayName("上界（base+range）被拒绝——区间右开")
    void rejectsAtUpperBound() throws Exception {
        searchWith(BASE + RANGE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FORBIDDEN));
    }

    @Test
    @DisplayName("evalUserId 为 null 被拒绝")
    void rejectsNullEvalUserId() throws Exception {
        searchWith(null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FORBIDDEN));
    }

    @Test
    @DisplayName("下界（base）放行——区间左闭")
    void acceptsBase() throws Exception {
        when(memoryManager.searchForEval(anyLong(), anyString(), anyInt(), anyDouble(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of());

        searchWith(BASE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"));
    }

    @Test
    @DisplayName("上界之内（base+range-1）放行")
    void acceptsJustBelowUpperBound() throws Exception {
        when(memoryManager.searchForEval(anyLong(), anyString(), anyInt(), anyDouble(), anyBoolean(), any(), any(), any()))
                .thenReturn(List.of());

        searchWith(BASE + RANGE - 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0000"));
    }
}
