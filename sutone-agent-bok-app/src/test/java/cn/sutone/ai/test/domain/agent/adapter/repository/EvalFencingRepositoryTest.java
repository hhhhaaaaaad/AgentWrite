package cn.sutone.ai.test.domain.agent.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import cn.sutone.ai.domain.agent.model.exception.MemoryEvalFencingException;
import cn.sutone.ai.infrastructure.adapter.repository.EvalFencingRepository;
import cn.sutone.ai.infrastructure.dao.IEvalFencingDao;
import cn.sutone.ai.infrastructure.dao.po.EvalFencingPO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * fencing 权威态生命周期单元测试（zombie 防护核心逻辑）。
 */
@DisplayName("EvalFencingRepository 单元测试")
@ExtendWith(MockitoExtension.class)
class EvalFencingRepositoryTest {

    @Mock
    private IEvalFencingDao evalFencingDao;

    @InjectMocks
    private EvalFencingRepository repository;

    private static final Long USER_ID = 9_000_000_001L;
    private static final String RUN_ID = "550e8400-e29b-41d4-a716-446655440000";

    private EvalFencingPO fencing(long version, String runId) {
        return EvalFencingPO.builder()
                .evalUserId(USER_ID)
                .fencingVersion(version)
                .activeRunId(runId)
                .build();
    }

    @Test
    @DisplayName("首次 acquire（无行）建 version=1 并写新 run_id")
    void acquireFirstTime_insertsVersionOne() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(null);

        IEvalFencingRepository.AcquireResult r = repository.acquire(USER_ID, 0L, RUN_ID);

        assertTrue(r.acquired());
        assertEquals(1L, r.version());
        verify(evalFencingDao).insert(USER_ID, 1L, RUN_ID);
        verify(evalFencingDao, never()).incrementAndSetRun(any(), any());
    }

    @Test
    @DisplayName("expected_version 一致时版本 +1 并换 run_id")
    void acquireMatchingExpectedVersion_increments() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(fencing(3L, "old-run"));

        IEvalFencingRepository.AcquireResult r = repository.acquire(USER_ID, 3L, RUN_ID);

        assertTrue(r.acquired());
        assertEquals(4L, r.version());
        verify(evalFencingDao).incrementAndSetRun(USER_ID, RUN_ID);
    }

    @Test
    @DisplayName("expected_version 不一致时返回冲突（当前权威版本）")
    void acquireMismatchedExpectedVersion_conflicts() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(fencing(5L, "holder-run"));

        IEvalFencingRepository.AcquireResult r = repository.acquire(USER_ID, 3L, RUN_ID);

        assertFalse(r.acquired());
        assertEquals(5L, r.version());
        verify(evalFencingDao, never()).incrementAndSetRun(any(), any());
        verify(evalFencingDao, never()).insert(any(), anyLong(), any());
    }

    @Test
    @DisplayName("并发 insert 撞唯一键后重读对账，落到冲突")
    void acquireConcurrentInsert_reconcilesToConflict() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(null, fencing(1L, "other-run"));
        doThrow(new DuplicateKeyException("duplicate")).when(evalFencingDao)
                .insert(eq(USER_ID), eq(1L), eq(RUN_ID));

        IEvalFencingRepository.AcquireResult r = repository.acquire(USER_ID, 0L, RUN_ID);

        assertFalse(r.acquired());
        assertEquals(1L, r.version());
    }

    @Test
    @DisplayName("release 匹配 active_run_id 返回 true")
    void releaseMatchingRunId_returnsTrue() {
        when(evalFencingDao.release(USER_ID, RUN_ID)).thenReturn(1);
        assertTrue(repository.release(USER_ID, RUN_ID));
    }

    @Test
    @DisplayName("release 不匹配返回 false")
    void releaseMismatchedRunId_returnsFalse() {
        when(evalFencingDao.release(USER_ID, RUN_ID)).thenReturn(0);
        assertFalse(repository.release(USER_ID, RUN_ID));
    }

    @Test
    @DisplayName("get 无行返回 (0, null) 哨兵")
    void getNoRow_returnsZeroNull() {
        when(evalFencingDao.select(USER_ID)).thenReturn(null);

        IEvalFencingRepository.EvalFencingState s = repository.get(USER_ID);

        assertEquals(0L, s.fencingVersion());
        assertNull(s.activeRunId());
    }

    @Test
    @DisplayName("isActiveRun 仅当前持有者匹配")
    void isActiveRun_matchesOnlyCurrentHolder() {
        when(evalFencingDao.select(USER_ID)).thenReturn(fencing(2L, RUN_ID));

        assertTrue(repository.isActiveRun(USER_ID, RUN_ID));
        assertFalse(repository.isActiveRun(USER_ID, "other-run"));
        assertFalse(repository.isActiveRun(USER_ID, null));
    }

    @Test
    @DisplayName("lockAndValidate: run_id 与版本均匹配时放行")
    void lockAndValidate_passesWhenRunIdAndVersionMatch() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(fencing(3L, RUN_ID));

        assertDoesNotThrow(() -> repository.lockAndValidate(USER_ID, RUN_ID, 3L));
    }

    @Test
    @DisplayName("lockAndValidate: 无 fencing 行时拒绝")
    void lockAndValidate_rejectsWhenNoRow() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(null);

        assertThrows(MemoryEvalFencingException.class,
                () -> repository.lockAndValidate(USER_ID, RUN_ID, 0L));
    }

    @Test
    @DisplayName("lockAndValidate: run_id 不匹配（僵尸 run）时拒绝")
    void lockAndValidate_rejectsZombieRun() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(fencing(2L, "successor-run"));

        assertThrows(MemoryEvalFencingException.class,
                () -> repository.lockAndValidate(USER_ID, RUN_ID, 2L));
    }

    @Test
    @DisplayName("lockAndValidate: 版本不符时拒绝")
    void lockAndValidate_rejectsVersionMismatch() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(fencing(5L, RUN_ID));

        assertThrows(MemoryEvalFencingException.class,
                () -> repository.lockAndValidate(USER_ID, RUN_ID, 3L));
    }

    @Test
    @DisplayName("lockAndValidate: 未传版本时只校验 run_id")
    void lockAndValidate_versionOptional() {
        when(evalFencingDao.selectForUpdate(USER_ID)).thenReturn(fencing(7L, RUN_ID));

        assertDoesNotThrow(() -> repository.lockAndValidate(USER_ID, RUN_ID, null));
    }
}
