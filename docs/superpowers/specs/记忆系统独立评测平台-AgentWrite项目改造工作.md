# 记忆系统独立评测平台：AgentWrite 项目改造工作

> 本文从《记忆系统独立评测平台方案（v18 终稿）》中拆出“当前 AgentWrite 项目内必须修改的工作”。
> 它只覆盖 Java 记忆系统、配置、数据库和回归测试相关改造；独立 Python/FastAPI 评测平台工作见：
> `docs/superpowers/specs/记忆系统独立评测平台-独立平台项目工作.md`
>
> 原始权威方案仍为：
> `docs/superpowers/specs/记忆系统独立评测平台方案.md`

## 1. 改造目标

AgentWrite 项目的职责不是承载评测平台的用户、任务、页面和结果管理，而是把现有记忆系统开放为一个**可控、可隔离、可复现、可被外部评测平台调用的 eval 能力面**。

核心目标：

1. 增加 `/api/v1/eval/**` 评测端点。
2. 保证 eval 端点复用真实记忆逻辑，不复制一套评测专用逻辑。
3. 支持专用 eval MySQL database 和 Qdrant collection。
4. 支持 seed/reset/search/extract/retrieve-context/governance replay。
5. 对 reset、seed、finalize 清理等破坏性操作提供 Java 侧 fencing 权威校验。
6. 补齐 contentHash、向量删除、冻结模式、参数暴露和 IDOR 防护。

## 2. 改造边界

### 2.1 AgentWrite 内要做

- Java eval controller。
- eval 权限和命名空间校验。
- memory eval profile 配置。
- 记忆 DAO、Repository、VectorStore 的 eval 支持。
- `MemoryRetriever`、`MemoryExtractor`、治理 replay 的可评测入口。
- `eval_fencing` 权威表和事务校验。
- Java 单元测试、契约测试和回归测试。
- Docker Compose 或启动配置中 eval 实例的接入点。

### 2.2 AgentWrite 内不做

- 不实现评测平台用户体系。
- 不保存评测 run、case result、趋势图、反馈流转。
- 不实现 Celery worker、reaper、judge job。
- 不实现 React 结果页面。
- 不把评测集版本化表放到 AgentWrite 主业务库。

AgentWrite 只负责“真实记忆能力的受控暴露”，评测状态和评测资产由独立平台持有。

## 3. 涉及模块和建议落点

| 模块 | 可能涉及文件/目录 | 改造内容 |
|---|---|---|
| `sutone-agent-bok-trigger` | `trigger/http` | 新增 `MemoryEvalController`，暴露 `/api/v1/eval/**` |
| `sutone-agent-bok-domain` | `service/memory` | 扩展 `MemoryRetriever`、`MemoryExtractor`、`MemoryManager` 的 eval 调用能力 |
| `sutone-agent-bok-domain` | `adapter/repository` | 扩展 `IMemoryVectorStore`，支持 `removeByUserId`、exact、hnsw_ef |
| `sutone-agent-bok-domain` | `model/valobj/properties` | `MemoryProperties` 增加 eval/freeze/可调参数读取 |
| `sutone-agent-bok-infrastructure` | `dao` | `IMemoryRecordDao` 增加按 userId/hash 查询、物理删除、fencing DAO |
| `sutone-agent-bok-infrastructure` | `adapter/repository` | `QdrantVectorStore`、`SimpleMemoryVectorStore` 支持删除和 eval 搜索参数 |
| `sutone-agent-bok-infrastructure` | `job`、`metrics` | eval profile 下禁用 cron，复用 metrics getter |
| `sutone-agent-bok-app` | `config`、`resources`、`test` | profile、配置、集成测试和回归测试 |

具体文件以实际代码结构为准。实现时应优先沿用现有 DDD 分层：trigger 暴露 HTTP，domain 保持业务逻辑，infrastructure 做 MyBatis/Qdrant/指标适配。

## 4. AgentWrite 工作包总览

| 编号 | 工作包 | 优先级 | 估算人天 | 依赖 |
|---|---|---:|---:|---|
| AW-0 | 代码现状核对和契约冻结 | P0 | 0.5 | 无 |
| AW-1 | eval profile 与独立实例配置 | P0 | 1 | AW-0 |
| AW-2 | ROLE_EVAL、命名空间校验和端点骨架 | P0 | 1 | AW-1 |
| AW-3 | seed 幂等与 addDirect 返回语义 | P0 | 1.5 | AW-2 |
| AW-4 | reset 物理删除与向量库 removeByUserId | P0 | 2 | AW-3 |
| AW-5 | search exact/freeze/hnsw_ef/content 返回 | P0 | 1.5 | AW-4 |
| AW-6 | extract、retrieve-context、governance replay | P0 | 2 | AW-5 |
| AW-7 | Java `eval_fencing` 权威表与端点 | P0 | 3 | AW-2、AW-4 |
| AW-8 | cron 禁用、metrics、params 单一真源 | P0 | 1 | AW-1、AW-6 |
| AW-9 | Java 回归、契约和故障测试 | P0 | 2.5 | AW-3 至 AW-8 |
| **合计** | AgentWrite 改造 |  | **16 人天** |  |

该估算对应总排期中的 Phase 0，并略微展开了代码核对、契约冻结和测试证据。若现有接口比方案假设更完善，可压缩到 12-14 人天；若 DAO/VectorStore 差异较大，可能上浮到 18 人天。

## 5. 详细工作包

### AW-0：代码现状核对和契约冻结（0.5 人天）

目标是在写代码前确认现有实现与方案假设是否一致。

检查点：

- `MemoryRetriever.MemoryItem` 是否已经包含 `content`。
- `MemoryItemDTO` 是否已有 `content` 字段。
- `MemoryProperties` 当前可读取的检索、注入、治理和告警配置。
- `IMemoryRecordDao` 是否已有 `content_hash`、`selectByUserIdAndHash`、物理删除能力。
- `IMemoryVectorStore` 是否能按 userId 删除，Qdrant 是否支持同步 delete-await。
- `MemoryGovernanceJob` 中所有 `@Scheduled` 数量和配置位置。
- 当前测试中是否已有 MemoryEvaluationTest，可复用哪些用例。

输出：

- 一份改造差距清单。
- eval API 请求/响应契约草稿。
- 当前代码阻塞点和需要先修复的接口签名。

验收：

- 每个后续 AW 工作包都能关联到明确文件或接口。

### AW-1：eval profile 与独立实例配置（1 人天）

目标是让同一个 AgentWrite jar 可以以 eval 实例启动。

改造内容：

- 增加 `memory.eval.enabled=true/false`。
- eval 实例使用专用 datasource 和 Qdrant collection。
- 业务实例保持默认 `memory.eval.enabled=false`。
- 所有会改写业务状态的 cron 增加 `@ConditionalOnProperty(name="memory.eval.enabled", havingValue="false", matchIfMissing=true)`。
- 增加 eval 环境变量清单，包括 database、collection、role token、base user id、range。

输出：

- eval profile 配置。
- compose 或启动命令示例。
- cron 禁用清单。

验收：

- 业务 profile cron 正常。
- eval profile cron 禁用。
- eval 实例连接专用 MySQL/Qdrant。

### AW-2：ROLE_EVAL、命名空间校验和端点骨架（1 人天）

目标是建立安全边界。

改造内容：

- 新增 `MemoryEvalController`。
- 所有 `/api/v1/eval/**` 端点要求 `ROLE_EVAL`。
- 服务端校验 `eval_user_id` 在 `[EVAL_USER_ID_BASE, BASE + RANGE)`。
- 客户端不可直接传业务 userId 访问 eval 端点。
- 统一 403、409、422、5xx 响应格式，便于 Python connector 解析。

输出：

- Controller 骨架。
- 权限配置。
- eval user id guard。

验收：

- 无 token/错误角色无法访问 mutation。
- 越界 eval_user_id 返回 403。
- 错误响应结构稳定。

### AW-3：seed 幂等与 addDirect 返回语义（1.5 人天）

目标是让平台可以反复 seed 同一评测集而不污染数据。

改造内容：

- `content_hash = MD5(exact content UTF-8 bytes)`，小写十六进制。
- DAO 增加 `selectByUserIdAndHash`。
- `addDirect` 从 void 改为返回 created id 或 inserted/existed 结果。
- 捕获 `DuplicateKeyException` 并转为 existed。
- `/eval/seed` 返回 inserted/existed 计数和 `content -> createdId` 映射。

输出：

- seed DTO。
- DAO/Repository/domain 改造。
- 幂等单测。

验收：

- 连续 seed 同一 payload 不抛异常。
- 并发 seed 同一 content 最终只有一条有效记录。
- contentHash 与平台计算口径一致。

### AW-4：reset 物理删除与向量库 removeByUserId（2 人天）

目标是确保评测语料不残留。

改造内容：

- `deleteByUserId` 使用物理删除或方案指定的彻底清理语义。
- `IMemoryVectorStore.removeByUserId(Long userId)`。
- `QdrantVectorStore` 使用 `{user_id}` filter 删除，并等待删除完成。
- `SimpleMemoryVectorStore` 只删除匹配 userId 的条目，不能清空全 map。
- `/eval/reset` 同时清 MySQL 和向量库。
- 处理孤儿向量和重复 reset no-op。

输出：

- reset endpoint。
- VectorStore 接口和双实现。
- 清理验证工具或测试辅助方法。

验收：

- reset 后当前 eval_user_id 的 MySQL 记录为 0。
- reset 后当前 eval_user_id 的 Qdrant/Simple 向量为 0。
- 并行 A/B 下 reset 不影响其他 eval_user_id。

### AW-5：search exact/freeze/hnsw_ef/content 返回（1.5 人天）

目标是让检索评测具备可复现模式和真实模式。

改造内容：

- `/eval/search` 支持 `threshold`、`rrfK`、`topK`、`taskType`、`freezeSideEffects`、`exact`、`hnsw_ef`。
- 返回结果必须包含 `content`。
- `freezeSideEffects=true` 时跳过访问回写、缓存、时间漂移、reranker 等副作用。
- exact 透传到 Qdrant 搜索参数。
- search 缓存 key 包含影响结果的动态参数，eval freeze 下旁路缓存。

输出：

- search DTO。
- SearchParams 扩展。
- exact/freeze 单测。

验收：

- eval search 不更新 access_count/importance。
- exact 参数能到达 Qdrant。
- 返回 content 可用于 contentHash 匹配。

### AW-6：extract、retrieve-context、governance replay（2 人天）

目标是开放五维评测所需的其余 Java 能力。

改造内容：

- `/eval/extract` 调用 `MemoryExtractor.extract`，仅返回候选列表。
- `/eval/retrieve-context` 调用注入上下文逻辑，返回 `budgeted` 和 `formatted`。
- `/eval/governance-samples` 导出治理样本。
- `/eval/governance/replay` 只调用 compute 层，不落库。
- circuit breaker 场景下 retrieve-context 走 victim-path 隔离。

输出：

- extract/retrieve/replay endpoint。
- 只读 replay 测试。
- 注入上下文 DTO。

验收：

- replay 不改变 memory_record。
- retrieve-context 可以用于无关注入率和 token 预算评测。
- extract 结果可被平台和 ground truth 对账。

### AW-7：Java `eval_fencing` 权威表与端点（3 人天）

目标是把破坏性写的最终裁决权下沉到 Java。

改造内容：

- 新增 `eval_fencing(eval_user_id, fencing_version, active_run_id, updated_at)`。
- `GET /eval/fencing/{eval_user_id}`：无行返回 `{fencing_version:0, active_run_id:null}`。
- `POST /eval/fencing/acquire`：事务内 `SELECT ... FOR UPDATE`，无行 INSERT，已有行对账 expected_version 后递增。
- `POST /eval/fencing/release`：仅当 `active_run_id=dead_run_id` 时清空 active run。
- reset/seed/finalize 清理入口在同一事务内校验 `X-Eval-Fencing` 和 `X-Eval-Run-Id`。
- UUID 统一小写带连字符字符串。

输出：

- fencing DDL。
- DAO/service/controller。
- 并发和 zombie 测试。

验收：

- 首次 acquire 建 version=1。
- expected_version 冲突返回 current_version。
- 旧 token、错误 run_id、无 fencing 行均 403。
- release 后后继 run 可接管。

### AW-8：cron 禁用、metrics、params 单一真源（1 人天）

目标是让平台可以读取真实参数和观测状态。

改造内容：

- `/eval/params` 返回当前 Java 真实参数。
- `/eval/metrics` 返回 extractionRejectRate、vectorSyncPendingCount。
- `/eval/circuit-breaker` 返回 degraded 状态。
- eval profile 下禁用治理、同步、告警等会干扰评测的定时任务。
- 保持业务 profile 行为不变。

输出：

- 参数响应 DTO。
- metrics 响应 DTO。
- profile 条件测试。

验收：

- 平台可以生成参数快照。
- HNSW 模式能通过 pending count 判断向量就绪。
- eval 实例不会因 cron 改写语料。

### AW-9：Java 回归、契约和故障测试（2.5 人天）

目标是保证 AgentWrite 改造不会破坏原业务路径。

测试清单：

- `mvn test` 全绿。
- 旧 MemoryController 和业务检索路径回归。
- seed 幂等和 reset no-op。
- Qdrant/Simple removeByUserId。
- IDOR 和 eval_user_id 范围校验。
- freezeSideEffects 不写 access。
- exact 参数透传。
- fencing acquire/release/reset/seed 并发边界。
- eval profile cron disabled。

验收：

- 新增 eval 能力不影响业务 profile。
- 测试能证明所有破坏性操作都受 fencing 守卫。

## 6. AgentWrite 开发顺序

建议顺序：

```text
AW-0
  -> AW-1
  -> AW-2
  -> AW-3
  -> AW-4
  -> AW-7
  -> AW-5
  -> AW-6
  -> AW-8
  -> AW-9
```

其中 AW-7 可以在 AW-4 之后并行准备 DDL 和 service，但不能在 reset/seed 完整接入前验收。

## 7. 与独立平台的接口交付物

AgentWrite 需要交付给独立平台以下稳定契约：

| 契约 | 独立平台使用方 |
|---|---|
| `/eval/params` | 参数快照、config_fingerprint |
| `/eval/seed` | run seed 阶段 |
| `/eval/reset` | run reset/finalize 清理 |
| `/eval/search` | 检索指标 |
| `/eval/extract` | 抽取指标 |
| `/eval/retrieve-context` | 注入指标 |
| `/eval/governance/replay` | 一致性/治理指标 |
| `/eval/fencing/**` | run 编排和 zombie 防护 |
| `/eval/metrics` | 向量就绪屏障和观测 |

契约一旦被平台接入，后续字段只能兼容扩展，不能破坏性改名或改变语义。

## 8. AgentWrite eval 接口语义

### 8.1 统一请求上下文

除只读参数和指标接口外，所有 eval 请求都必须能在服务端确定以下上下文：

| 字段 | 来源 | 用途 |
|---|---|---|
| `eval_user_id` | URL、请求体或 seed item，但必须经过服务端派生/范围校验 | 语料命名空间 |
| `run_id` | `X-Eval-Run-Id` header | fencing active run 校验 |
| `fencing_version` | `X-Eval-Fencing` header | 拒绝 zombie worker |
| `request_id` | header 或服务端生成 | 日志和故障追踪 |

客户端可以传递 run_id 和 token，但不能自行决定一个越过 eval namespace 范围的 user id。对于 reset、seed 和 finalize 清理，缺少 fencing header 应视为请求错误或 403，不允许兼容放行。

### 8.2 端点行为约束

| 端点 | 幂等性 | 是否改变状态 | 失败后的处理 |
|---|---|---|---|
| `/eval/params` | 天然幂等 | 否 | connector 可重试 |
| `/eval/metrics` | 天然幂等 | 否 | connector 可重试 |
| `/eval/search` | 冻结模式下幂等 | 正常模式可能访问回写 | 只读错误按请求失败处理 |
| `/eval/extract` | 相同输入不保证相同生成结果 | 否 | 记录模型/响应错误，不写业务库 |
| `/eval/seed` | 必须幂等 | 是 | DuplicateKey 转 existed，fencing 403 不重试 |
| `/eval/reset` | 必须幂等 | 是 | 超时需核对清理状态，不能盲目重复跨命名空间删除 |
| `/eval/governance/replay` | 必须幂等 | 否 | 禁止落库，异常保留输入上下文 |
| `/eval/fencing/acquire` | 版本递增操作 | 是 | conflict 返回 current version，由平台重读后重试一次 |
| `/eval/fencing/release` | 幂等 | 是 | dead run 不匹配时返回无变化结果 |

### 8.3 响应设计原则

- 返回 DTO 不暴露业务内部实体和数据库敏感字段。
- search 返回 `content`、排序分数、必要的类型/元数据和可选 createdId；平台只把 contentHash 作为评测身份。
- seed 返回 `inserted`、`existed` 以及 content 到 createdId 的映射，映射只用于诊断。
- reset 返回清理统计，至少区分 MySQL 删除数和向量删除确认状态。
- fencing conflict 返回当前权威版本，但不返回数据库连接或内部 SQL 信息。

## 9. AgentWrite 改造原则

### 9.1 复用优先

eval 端点只做参数适配、权限校验和副作用冻结，核心逻辑必须继续调用：

- `MemoryExtractor` 的抽取逻辑。
- `MemoryRetriever` 的混合检索逻辑。
- `MemoryManager` 的已有编排。
- `QdrantVectorStore` 和 `SimpleMemoryVectorStore` 的统一接口。
- 治理 replay 的 compute 层。

不允许在 `MemoryEvalController` 中重新实现 RRF、BM25、身份判定或治理规则。

### 9.2 业务 profile 与 eval profile 隔离

| 项目 | 业务 profile | eval profile |
|---|---|---|
| 数据库 | 业务库 | 专用 eval 库 |
| Qdrant | 业务 collection | 专用 collection |
| cron | 启用 | 默认禁用 |
| 访问回写 | 正常 | freeze 时禁用 |
| 缓存 | 正常 | freeze 时旁路 |
| 权限 | 业务角色 | `ROLE_EVAL` |
| userId | 业务 userId | 派生 eval_user_id |

配置切换不能通过代码中的静态 if 到处散落，优先集中在 properties、profile 和条件装配。

### 9.3 失败优先于静默成功

以下情况必须抛异常或返回明确失败：

- Qdrant 删除未确认。
- 向量写入失败但 MySQL 已成功。
- fencing 权威状态不存在。
- token 和 active run 不匹配。
- exact 参数未能透传。
- replay 发生了落库副作用。

不得通过只打印日志然后返回成功来缩短联调时间。

## 10. AgentWrite 测试分层

### 10.1 单元测试

- contentHash 和 seed 去重。
- eval_user_id 范围校验。
- freezeSideEffects 参数转换。
- exact/hnsw_ef DTO 映射。
- fencing header 校验。
- governance replay compute 不调用 apply。

### 10.2 集成测试

- MySQL seed/reset 真实事务行为。
- Qdrant filter delete 和同步等待。
- SimpleMemoryVectorStore 按 userId 删除。
- `eval_fencing` 的 `FOR UPDATE` 并发 acquire。
- eval profile 的 cron 条件装配。

### 10.3 回归测试

- 业务 MemoryController 原有 search。
- 业务 add/extract/persist 流程。
- 业务向量同步和治理 job。
- 默认配置值和非 eval profile 启动。

### 10.4 契约测试

每个端点至少固定：

1. 正常响应 JSON。
2. 权限失败。
3. 参数校验失败。
4. Java 业务拒绝。
5. transient 连接错误。

## 11. 回滚和发布策略

1. 先发布兼容代码，再启用 eval profile。
2. 新增字段和表优先采用向后兼容迁移。
3. eval controller 可通过配置开关整体关闭。
4. 新 endpoint 出现异常时，不影响原业务 endpoint。
5. fencing 表和接口出现问题时，禁止降级成无 token 的 reset/seed；应停止评测任务并保留现场。
6. Qdrant 删除行为变更时，先在 Simple store 和专用 collection 验证，再切换真实 eval 流量。

回滚的底线是：不能为了恢复可用性而绕过 namespace 校验、唯一约束或 fencing。

## 12. AgentWrite 工作包完成定义

当且仅当以下条件全部满足，才认为 AgentWrite 侧改造完成：

- AW-0 至 AW-9 均有代码、测试或明确不适用说明。
- eval profile 可独立启动，业务 profile 回归通过。
- seed/reset/search 三个最小端点完成真实联调。
- fencing acquire/release/reset/seed 的并发和 zombie 测试通过。
- Qdrant 和 Simple store 的按 userId 清理语义一致。
- Java API 契约已被独立平台 connector 固化。
- `mvn test` 全绿，且失败场景有日志和排查路径。

只有达到这个出口，独立平台才适合开启真实 run 编排。
