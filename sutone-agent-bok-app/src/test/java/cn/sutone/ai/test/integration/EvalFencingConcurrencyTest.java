package cn.sutone.ai.test.integration;

import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * fencing 权威态集成测试（真实 MySQL）。
 *
 * <p>验证单元测试无法覆盖的部分：{@code SELECT ... FOR UPDATE} 的并发正确性、唯一键撞键路径、
 * 版本单调递增。需要 MySQL（13306）就绪。</p>
 */
@SpringBootTest(properties = "memory.vector-store=memory")
@EnabledIf("cn.sutone.ai.test.integration.EvalInfra#mysqlAvailable")
@DisplayName("fencing 权威态集成测试（真实 MySQL）")
class EvalFencingConcurrencyTest {

    /** 独立测试命名空间，避免与业务数据冲突 */
    private static final Long USER = 9_000_000_999L;

    @Resource
    private IEvalFencingRepository repository;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM eval_fencing WHERE eval_user_id = ?", USER);
    }

    @Test
    @DisplayName("首次 acquire 后权威版本为 1 且持有者为该 run")
    void firstAcquireCreatesVersionOne() {
        IEvalFencingRepository.AcquireResult r = repository.acquire(USER, 0L, "run-first");

        assertTrue(r.acquired());
        assertEquals(1L, r.version());
        IEvalFencingRepository.EvalFencingState state = repository.get(USER);
        assertEquals(1L, state.fencingVersion());
        assertEquals("run-first", state.activeRunId());
    }

    @Test
    @DisplayName("expected_version 不一致时拒绝并返回当前权威版本")
    void mismatchedExpectedVersionConflicts() {
        repository.acquire(USER, 0L, "run-a");

        IEvalFencingRepository.AcquireResult r = repository.acquire(USER, 0L, "run-zombie");

        assertFalse(r.acquired());
        assertEquals(1L, r.version());
        assertEquals("run-a", repository.get(USER).activeRunId(), "冲突不得夺走持有权");
    }

    @Test
    @DisplayName("版本单调递增：连续 acquire 得到 1 → 2 → 3")
    void versionsIncreaseMonotonically() {
        assertEquals(1L, repository.acquire(USER, 0L, "run-1").version());
        assertEquals(2L, repository.acquire(USER, 1L, "run-2").version());
        assertEquals(3L, repository.acquire(USER, 2L, "run-3").version());
    }

    @Test
    @DisplayName("release 后后继 run 可从新版本接管")
    void releaseAllowsSuccessorTakeover() {
        repository.acquire(USER, 0L, "run-a");
        assertTrue(repository.release(USER, "run-a"));
        assertNull(repository.get(USER).activeRunId());

        IEvalFencingRepository.AcquireResult r = repository.acquire(USER, 1L, "run-b");
        assertTrue(r.acquired());
        assertEquals("run-b", repository.get(USER).activeRunId());
    }

    @Test
    @DisplayName("错误 run_id 的 release 不生效（CAS）")
    void releaseWithWrongRunIdIsNoop() {
        repository.acquire(USER, 0L, "run-holder");

        assertFalse(repository.release(USER, "run-imposter"));
        assertEquals("run-holder", repository.get(USER).activeRunId());
    }

    @Test
    @DisplayName("并发 acquire 只有一个成功，权威版本为 1")
    void concurrentAcquireOnlyOneWins() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<IEvalFencingRepository.AcquireResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String runId = "run-concurrent-" + i;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return repository.acquire(USER, 0L, runId);
                }));
            }
            startGate.countDown();

            List<IEvalFencingRepository.AcquireResult> results = new ArrayList<>();
            for (Future<IEvalFencingRepository.AcquireResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }

            long acquired = results.stream().filter(IEvalFencingRepository.AcquireResult::acquired).count();
            assertEquals(1, acquired, "并发 acquire 应只有一个成功");
            assertEquals(1L, repository.get(USER).fencingVersion(), "权威版本应为 1（非跳号）");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("zombie run 在继任者接管后无法通过 fence（旧 token 失效）")
    void zombieRunLosesFenceAfterSuccession() {
        repository.acquire(USER, 0L, "run-old");
        // 继任者接管（旧 run 假设已死）
        repository.acquire(USER, 1L, "run-new");

        assertFalse(repository.isActiveRun(USER, "run-old"), "旧 run 已失去 fence");
        assertTrue(repository.isActiveRun(USER, "run-new"));
        // 旧 run 的 release 也不得清掉继任者
        assertFalse(repository.release(USER, "run-old"));
        assertEquals("run-new", repository.get(USER).activeRunId());
    }
}
