package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import cn.sutone.ai.domain.agent.model.exception.MemoryEvalFencingException;
import cn.sutone.ai.infrastructure.dao.IEvalFencingDao;
import cn.sutone.ai.infrastructure.dao.po.EvalFencingPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 评测 fencing 权威态实现。
 *
 * <p>acquire 是事务内读改写：{@code SELECT ... FOR UPDATE} → 无行 INSERT / 有行对账后 +1，
 * 隔离级别 pin REPEATABLE READ（锁定读始终读到最新已提交，用于并发抢占的正确对账）。</p>
 *
 * <p>并发抢占空表时，多个事务的 {@code SELECT ... FOR UPDATE} + INSERT 会在 MySQL 上
 * 锁竞争触发死锁（{@code DeadlockLoserDataAccessException}），这不是语义错误而是锁序问题，
 * 因此 acquire 用编程式事务（{@link TransactionTemplate}）在<b>事务外</b>随机退避重试，
 * 让 MySQL 检测并回滚的那个事务重试后收敛——最终仍只有一个 acquire 成功、其余对账返回 conflict。</p>
 */
@Slf4j
@Repository
public class EvalFencingRepository implements IEvalFencingRepository {

    /** 死锁重试上限：并发抢占空表时锁竞争死锁，重试几次即可收敛 */
    private static final int MAX_ACQUIRE_RETRIES = 5;

    @Resource
    private IEvalFencingDao evalFencingDao;

    @Resource
    private PlatformTransactionManager transactionManager;

    /**
     * 构造一个 pinned 到 REPEATABLE READ 的事务模板。
     *
     * <p><b>刻意每次现建，而不是用 {@code @PostConstruct} 缓存到字段。</b>
     * {@link TransactionTemplate} 只是个不可变的配置载体，现建的开销可以忽略
     * （{@code acquire} 一次 run 才调一次）。而缓存到字段会引入一个生命周期依赖：
     * 字段必须在 Spring 调用 {@code @PostConstruct} 之后才可用，任何不经过容器的
     * 实例化（尤其是单元测试里直接 new）都会让它在调用点变成 null，
     * 报出一个与业务逻辑毫无关系的 NPE。这个坑真实踩过——
     * {@code EvalFencingRepositoryTest} 的四条用例因此长期失败，
     * 而失败信息只字未提「忘了调生命周期方法」。</p>
     */
    private TransactionTemplate newTransactionTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }

    @Override
    public EvalFencingState get(Long evalUserId) {
        EvalFencingPO po = evalFencingDao.select(evalUserId);
        return po == null
                ? new EvalFencingState(0L, null)
                : new EvalFencingState(po.getFencingVersion(), po.getActiveRunId());
    }

    @Override
    public AcquireResult acquire(Long evalUserId, long expectedVersion, String newRunId) {
        TransactionTemplate txTemplate = newTransactionTemplate();
        for (int attempt = 0; ; attempt++) {
            try {
                return txTemplate.execute(status -> doAcquire(evalUserId, expectedVersion, newRunId));
            } catch (DeadlockLoserDataAccessException e) {
                if (attempt >= MAX_ACQUIRE_RETRIES - 1) {
                    log.error("fencing acquire 死锁重试耗尽: evalUserId={}", evalUserId, e);
                    throw e;
                }
                log.warn("fencing acquire 死锁，随机退避重试 {}/{}: evalUserId={}",
                        attempt + 1, MAX_ACQUIRE_RETRIES, evalUserId);
                try {
                    // 随机退避打散多个并发重试的时序，避免再次同时撞锁
                    Thread.sleep(ThreadLocalRandom.current().nextInt(1, 20));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** 事务内读改写：{@code SELECT ... FOR UPDATE} → 无行 INSERT / 有行对账后 +1 */
    private AcquireResult doAcquire(Long evalUserId, long expectedVersion, String newRunId) {
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
