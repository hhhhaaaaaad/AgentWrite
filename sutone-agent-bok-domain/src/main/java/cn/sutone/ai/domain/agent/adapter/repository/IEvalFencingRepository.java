package cn.sutone.ai.domain.agent.adapter.repository;

/**
 * 评测 fencing 权威态端口（zombie 防护）。
 *
 * <p>fencing 是破坏性写（reset/seed/finalize 清理）前的权威校验：一个 per-config 命名空间
 * （{@code eval_user_id}）同时只能有一个 run 持有 fencing。{@code fencing_version} 单调递增，
 * 用于对账预期版本（alignment protocol）；{@code active_run_id} 标识当前持有者。</p>
 */
public interface IEvalFencingRepository {

    /** fencing 权威态快照（GET 哨兵的数据源） */
    record EvalFencingState(long fencingVersion, String activeRunId) {
    }

    /** acquire 结果：acquired=true 时 version 为新版本，false 时 version 为当前权威版本（冲突） */
    record AcquireResult(boolean acquired, long version) {
    }

    /** 读取当前权威态；无行返回 {@code (0, null)} */
    EvalFencingState get(Long evalUserId);

    /**
     * 抢占 fencing（事务内读改写）。
     *
     * <p>无行 → INSERT version=1（并发撞唯一键捕获 DuplicateKeyException 后重读对账）；
     * 有行 → 对账 {@code expectedVersion}，不一致返回 conflict（当前版本），一致则版本 +1 并写新 run_id。</p>
     */
    AcquireResult acquire(Long evalUserId, long expectedVersion, String newRunId);

    /** 释放 fencing：仅当 active_run_id 匹配时才清空（CAS），返回是否释放成功 */
    boolean release(Long evalUserId, String runId);

    /** 校验 run_id 是否为当前持有者（破坏性写的前置 guard） */
    boolean isActiveRun(Long evalUserId, String runId);

    /**
     * 事务内锁定并校验 fencing（破坏性写的原子守卫）。
     *
     * <p>对 {@code eval_fencing} 行做 {@code SELECT ... FOR UPDATE}，校验 {@code activeRunId == runId}
     * 且（{@code expectedVersion} 非空时）版本一致。</p>
     *
     * <p><b>必须在调用方已开启的事务内执行</b>（{@code Propagation.MANDATORY}）——锁持有到调用方事务提交，
     * 破坏性写与该校验同事务，杜绝「校验通过 → 继任者接管 → 僵尸写入」的 TOCTOU 窗口。
     * 校验失败抛 {@code MemoryEvalFencingException}。</p>
     */
    void lockAndValidate(Long evalUserId, String runId, Long expectedVersion);
}
