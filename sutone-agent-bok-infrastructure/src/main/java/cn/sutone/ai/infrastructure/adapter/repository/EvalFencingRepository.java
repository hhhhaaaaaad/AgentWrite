package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import cn.sutone.ai.domain.agent.model.exception.MemoryEvalFencingException;
import cn.sutone.ai.infrastructure.dao.IEvalFencingDao;
import cn.sutone.ai.infrastructure.dao.po.EvalFencingPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 评测 fencing 权威态实现。
 *
 * <p>acquire 是事务内读改写：{@code SELECT ... FOR UPDATE} → 无行 INSERT / 有行对账后 +1，
 * 隔离级别 pin REPEATABLE READ（锁定读始终读到最新已提交，用于并发抢占的正确对账）。</p>
 */
@Slf4j
@Repository
public class EvalFencingRepository implements IEvalFencingRepository {

    @Resource
    private IEvalFencingDao evalFencingDao;

    @Override
    public EvalFencingState get(Long evalUserId) {
        EvalFencingPO po = evalFencingDao.select(evalUserId);
        return po == null
                ? new EvalFencingState(0L, null)
                : new EvalFencingState(po.getFencingVersion(), po.getActiveRunId());
    }

    @Override
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public AcquireResult acquire(Long evalUserId, long expectedVersion, String newRunId) {
        EvalFencingPO row = evalFencingDao.selectForUpdate(evalUserId);
        if (row == null) {
            try {
                evalFencingDao.insert(evalUserId, 1L, newRunId);
                return new AcquireResult(true, 1L);
            } catch (DuplicateKeyException e) {
                // 并发抢占：另一事务已 INSERT，重读锁定行后落到下方对账
                row = evalFencingDao.selectForUpdate(evalUserId);
                if (row == null) {
                    throw e;
                }
            }
        }
        if (row.getFencingVersion() != expectedVersion) {
            return new AcquireResult(false, row.getFencingVersion());
        }
        evalFencingDao.incrementAndSetRun(evalUserId, newRunId);
        return new AcquireResult(true, row.getFencingVersion() + 1);
    }

    @Override
    public boolean release(Long evalUserId, String runId) {
        return evalFencingDao.release(evalUserId, runId) > 0;
    }

    @Override
    public boolean isActiveRun(Long evalUserId, String runId) {
        if (runId == null) {
            return false;
        }
        EvalFencingPO po = evalFencingDao.select(evalUserId);
        return po != null && runId.equals(po.getActiveRunId());
    }

    /**
     * 事务内锁定并校验：{@code SELECT ... FOR UPDATE} 持锁到调用方事务提交。
     *
     * <p>{@code MANDATORY} 传播：无外层事务时直接抛 {@code IllegalTransactionStateException}，
     * 避免「以为持锁实则自动提交」的静默失效。</p>
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAndValidate(Long evalUserId, String runId, Long expectedVersion) {
        EvalFencingPO row = evalFencingDao.selectForUpdate(evalUserId);
        if (row == null) {
            throw new MemoryEvalFencingException(
                    "无 fencing 行: evalUserId=" + evalUserId);
        }
        if (runId == null || !runId.equals(row.getActiveRunId())) {
            throw new MemoryEvalFencingException(
                    "run_id 不匹配: expected=" + row.getActiveRunId() + ", actual=" + runId);
        }
        if (expectedVersion != null && expectedVersion != row.getFencingVersion()) {
            throw new MemoryEvalFencingException(
                    "fencing 版本不符: expected=" + expectedVersion + ", actual=" + row.getFencingVersion());
        }
    }
}
