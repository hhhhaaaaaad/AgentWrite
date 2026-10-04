# Handoff: team-plan → team-exec → team-verify

Team: `eval-platform-backlog` · 起点 2026-10-01T05:20Z

## Decided

- **走 P0~P4 待办清单作为「已批准的计划」**，跳过 autopilot 的 Phase 0/1
  （用户直接引用了 P0~P4，等于已验收范围）。直接从 team-exec 开始。
- **worker 一律不提交 git**，由 lead 在全部收敛后按主题分批提交。
  理由：五个 worker 并发改同一个仓库（eval-platform），让它们各自 `git commit`
  必然撞 `index.lock`，且会把彼此半成品混进同一个提交。
- **`app/main.py` 与 `app/settings/config.py` 由 worker-2 独占**。worker-3 的
  judge/feedback 路由**不由自己注册**，而是向 worker-2 暴露固定的 `router` 契约
  （`app/judge/router.py` / `app/feedback/router.py`，变量名 `router`），由 worker-2 挂载。
  worker-3 被要求**第一步**就把这两个文件建出来，降低 worker-2 尾部等待的概率。
- **Java 进程（8092）的生命周期归 lead**。worker-4 需要重启才能做 live 验证，
  但重启会打断 worker-5 的真实 LLM 调用，因此 worker-4 被要求「就绪后 message lead 等待」。
- **lead 不跑任何 run**：平台的独占守卫是**单行全局**（`eval_run_guard`，
  `CHECK (id = 1)`），lead 跑 drill 会给 worker-4/5 制造假 409。

## Rejected

- **拆成「Java 侧 / 平台侧」两个 worker 做治理可达性** —— 一条契约两端两个 worker，
  在无共享运行时的情况下是最典型的集成失败形态。改由 worker-4 一个人端到端做。
- **让 worker-2/3 各自注册自己的路由** —— 需要在 `main.py` 上串行，失去并行价值。
- **让 worker 自己 commit** —— 见上，且提交边界应由 lead 按主题决定。
- **用假 embedding 起 8092** —— 用户明确禁止，且本项目已有实证：假替身下
  「因为错误的原因通过」的评测集，换真模型后立刻退化。

## Risks

1. **worker-2 × worker-3 的 router 契约存在时序竞争**。缓解：文件契约固定 + worker-3
   第一步就建文件 + 明确禁止用 `try/except` 静默兜底（本项目最忌静默降级）。
   **若截止时仍缺失，worker-2 必须如实报告而非绕过。**
2. **worker-4 的 Java 构建可能与运行中的 8092 抢 jar 文件**（Windows 文件锁）。
   已指示：构建失败且原因是文件占用时**停下来报告**，不要自作主张 kill 进程。
3. **worker-4 与 worker-5 都会创建 run**，会互相撞全局独占守卫。已告知双方
   「409 是并发不是 bug，重试」。**但它们也可能因此长时间互相饿死** —— lead 需要在
   收敛阶段检查是否真的都拿到过 run。
4. **worker-1 的指标改名牵动已落库历史数据**（`eval_run_results.metric_name`）。
   已要求它二选一并论证；这是本批次**唯一会改动历史数据的**改动，最需要 review。
5. **worker-5 的 ground truth 校准有循环评分风险**（照抄模型输出 = 测模型和自己像不像）。
   已要求它显式论证「如何保证没有循环评分」，这一条**必须被验证阶段重点审查**。
6. **AgentWrite 仓库在并行期是「编译不过」的状态，这是预期的，不是回归。**
   实测（lead，`mvn -o -pl sutone-agent-bok-trigger compile`）：`MemoryEvalController.java`
   已经在调 `item.getSubject()/getPredicate()/getValue()/getConfidence()/getExpireTime()`，
   而 DTO 的改动还没进 `~/.m2`，所以找不到符号；另有一处
   `MemoryGovernanceComputeService.samples` 的签名不匹配。**两者都是 worker-4 在飞的状态。**
   ⚠️ **诊断提醒**：用 `-pl <module>` 单模块离线编译时，链接的是 `~/.m2` 里**上一次安装**的
   兄弟模块 jar，**看不到工作区的未提交改动**——在那个窗口里它必然报「找不到符号」，
   而这个报错**不能当作「改动有问题」的证据**。判定必须用全量构建（`-am` 或不带 `-pl`）。
7. **lead 的 P0-2 改动（`AiTaskConsumer` 加 `@ConditionalOnProperty`）尚未编译验证**，
   上面的单模块编译因风险 6 无法用它作证据。真正的门是收敛后的协调构建。

## Files（lead 已完成的部分）

- `E:\java\eval-platform\scripts\dev\idea_env.py`（新）——从 IDEA 运行配置读凭据
- `E:\java\eval-platform\scripts\dev\start-java-eval.sh`（重写）——真实配置，拒绝假替身
- `E:\java\eval-platform\scripts\dev\start-worker.sh`（修）——`--pool=solo`

## Environment（已在跑，勿重启）

| 组件 | 地址 | 备注 |
|---|---|---|
| AgentWrite eval | 8092 | **真实** SiliconFlow，`alpha=0.1 beta=0.1 injectMaxTokens=800 vectorStore=qdrant` |
| 平台 API | 8093 | uvicorn `--reload` |
| 平台 Postgres / Redis | 15432 / 16380 | docker |
| Celery worker / beat | — | worker 用 `--pool=solo`（Windows 必须） |
| 评测前端 | 5173 | **只监听 `[::1]`**，用 `http://localhost:5173/`（`127.0.0.1` 连不上） |

## 执行期发现（lead 独立复核过，非 worker 自述）

### ★ 最高价值的发现：维度④注入路径**查询无关**（AgentWrite 侧真 bug）

run `bfefe22f`（hnsw，真模型），同 20 个 query、同一 run：

| 端点 | 结果 | 判定 |
|---|---|---|
| `/api/v1/eval/search`（维度②） | **20 个互不相同**的检索集合，`recall_at_k` 0.5~1.0 | 查询相关，正常 |
| `/api/v1/eval/retrieve-context`（维度④） | **1 个集合**，20 case 逐位相同 | 查询无关，**坏了** |

注入侧逐 case 证据（`eval_case_results.detail`）：q1 与 q2 的 `injected` 是**同样的 5 个 ID、
同样的顺序、同样的 `token_count=66`**，而两者的 `relevant` 毫无交集（q2 命中 0/5）。
且 **q1 在 `/search` 上的检索集合与该注入集合逐位相同** → 最高可能性是
**注入路径没把 query 传下去**，每次都用同一个 query 检索。

**意义**：维度④现有全部指标是**常量**，20 个 case 恒等。报告上显示的
`irrelevant_injection_rate=0.92` 是**在如实报告这个缺陷**——worker-1 先查清了
「这是口径错还是真问题」，结论是真问题，因此**没有去改指标**（这个判断是对的）。
已建任务 #75，等待 worker-4 收工后串行接手（要动 `MemoryEvalController.java`，有重叠）。

### worker-1：指标改名 + 迁移已由 **lead 实际施加**

- `duplicates/expired/hallucination_count` → `*_case_count`，Alembic `0003` **已 upgrade**（实测旧名 0 行、新名 3 行）。
- 迁移选型 (a) in-place rename，理由：`eval_run_results` 是**跑批快照**（用于跨 run 趋势比对），
  不是审计证据；趋势查询按单个 `metric_name` 过滤，读侧别名会让历史上出现两个名字、**拆断趋势连续性**。
- 有意**未迁移** `eval_runs.result_summary`（反规范化 JSONB）；当前前端读 `/runs/{id}/results`
  不读它，但**任何直接读 `result_summary` 键的客户端会看到旧名** —— 这是一个已知的、范围明确的缺口。

### worker-5：方差量化结论（本轮最有解释力的数字）

- **语义层稳定，措辞层抖动**：d1 严格 Jaccard 0.19~0.21、**完全一致仅 2%~7%**；
  而**松口径 Jaccard 1.0**（45 对全覆盖）。→ 直接论证了松口径不是"放水"，是**唯一能读的口径**。
- d2 十次全空（详见下条争议）。
- 校准后：松口径 f1 **恒 1.0（std 0）**；严格口径 0~1 抖动（std 0.32），**全是措辞噪声**。

## team-verify 已跑的门（lead，2026-10-01）

| 门 | 结果 |
|---|---|
| `pytest -q`（eval-platform，真 Postgres） | **579 passed, 0 failed** |
| `mypy app` | Success: no issues found in 74 source files |
| `ruff check .` | All checks passed |
| AgentWrite 全量 `mvn -B package -Dmaven.test.skip=true` | BUILD SUCCESS（含 app 模块，17s） |
| 平台路由表 | 29 条，双前缀已消除 |
| 8092 启动日志 | `ai-writing-worker-group` 出现 **0 次**（RocketMQ 本身正常初始化）→ P0-2 生效 |

**修掉的三个门失败（都是 worker 交付里漏的，不是它们自报的）**：
1. `scripts/quantify_extraction_variance.py:117` `RuntimeError` → `TypeError`（ruff TRY004）。
   worker-5 只跑了 `py_compile`，没跑仓库级 `ruff check .`。
2. `app/observability/service.py:157-159` `Pool` 无 `size/checkedout/overflow`（mypy）。
   worker-2 报了 ruff + pytest，**没提 mypy**，而 mypy 在 CI 里。改为
   `isinstance(pool, QueuePool)` 收窄，非 QueuePool 时返回 `None`（守住「未知 ≠ 零」）。
3. 同文件 `int(pending)` → `cast(int, pending)`：redis-py 把同步/异步签名合成了
   `Awaitable[int] | int`，原写法既没表达意图、又掩盖了「拿到协程会炸」的真实风险点。

## Remaining（交给 team-verify）

1. **#75 注入路径查询无关** —— 最高优先级，需要 Java 侧修复（等 worker-4 让路）。
2. **#76 d2 是否该复原为负样本** —— lead 已反驳 worker-5「移除该 case」的做法，worker-5 正在复查。
   **审查要点**：删掉一条「系统行为正确、但标注写错」的 case，与「测试挂了就删测试」在结果上无法区分。
   若平台无法表达空 ground truth，那是**平台能力缺口**，须如实记为缺口而非「已处理」。
3. ~~worker-1 的 `result_summary` 旧键名缺口~~ —— **lead 已复核并判定「不改」**，理由链完整：
   (a) `app/results/service.py` 的 `metric_trend` docstring **明确写了** `result_summary` 是
   JSONB blob、**不适合跨 run 聚合**（要么 GIN + 复杂路径表达式、要么全表扫描），
   「结果落库真正的用处」是 `eval_run_results`；
   (b) 全仓 grep `frontend/src/**` 只有 `api/types.ts:73` 声明了该字段，
   **没有任何组件读取它的键** —— 即对**已发布的前端**不可见；
   (c) 真正可查询的那份（`eval_run_results`）**已随迁移 0003 改名**。
   → 结论：`result_summary` 保留旧名是**有意的历史快照语义**，不是遗漏。
   唯一受影响的是「直接读该字段的外部 API 客户端」，且该字段在接口文档里本就被定位成快照。
4. lead 侧：按主题分批提交（**不要**把 worker 半成品混进同一提交）。
5. 用户侧：P2-9 前端浏览器验收（`docs/acceptance-checklist.md`）——唯一必须由人做的。
6. 未做：P2-10/11 可靠性演练（cancel / crash recovery）。**不是被独占守卫挡住，
   而是环境根本给不出窗口**：实测 run「建 run 返回」到「取消发出」之间就跑完了
   （远快于 1 秒），连「创建后立即取消」都只拿到 409 终态守卫。
   要验证需先造「冷缓存 + 足够多 case」的 run。已把实测值与成因写进
   `docs/acceptance-checklist.md` §3.2 与 `scripts/drill_reliability.py` 的 NOTE。

### P4 里**没做**的两项（都需要先做策略决定，不是纯实现）

8. **`eval_fencing` 历史行无清理策略** —— 实测 23 行且随 run 增长。
   要先定「保留多久 / 保留多少条 / 是否需要可查询」，再决定清理机制
   （定时任务 vs 按需脚本 vs 分区）。
9. **`config_fingerprint` 里没有「embedding 来源」** —— 假替身与真模型的 run
   只在模型名上区分。⚠️ **这一项不能顺手改**：`config_fingerprint` 是参与
   「同一配置的多次 run 是否可比」判定的冻结参数，往里加字段会让**新 run 与
   已有 run 不再可比**（跨 run 趋势线会断）。所以它是一个**需要配套迁移/版本策略的
   设计变更**，不是加一个字段那么简单。**留作决定，不要当作小修处理。**
7. 未做：评测前端 `vite.config.ts` 的 proxy target 仍硬编码——**评估后判定不值得改**（仅 dev 用，
   生产走 nginx.conf，已核实 `proxy_pass` 无 URI 后缀、`/api/v1/...` 正确透传，交叉引用也有效）。
