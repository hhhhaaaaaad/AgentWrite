# 独立记忆系统评测平台 —— 方案与 RALPLAN-DR 摘要（v6 终稿）

> 阶段：初始（SHORT）→ v2 → v3 → v4（存储隔离决策）→ v5（实现清单补齐）→ v6（终稿：replay 只读分层 + 契约钉死）
> 日期：2026-09-28（v6）
> 评审态：Architect APPROVE / Critic REVISE（单点澄清），补齐唯一 MAJOR 即 APPROVE

---

## 0. 修订记录

### v6 终稿（对照第 5 轮终审）

| # | 类型 | 修订要点 | 落点 |
|---|---|---|---|
| 1 | MAJOR | **replay 只读性**：4 个 scan 拆 compute 层（返回决策不落库）+ apply 层（落库）；cron 调两层，replay 只调 compute；补 userId 作用域 DAO | §4.2 / §4.5 |
| 2 | Minor | MD5 统一 `MD5(content)`，退役 `11-eval-insert-corpus.sql` | §4.1 |
| 3 | Minor | `/eval/extract` 响应**移除 decideOperation**（extract 仅返回 `List<MemoryCandidate>`） | §4.1 |
| 4 | Minor | 新建 `GET /api/v1/eval/search`；`/memory/search` 仅同步编译 + 补 DTO | §4.1 |
| 5 | Minor | exact 非 eval 三处缺省 `false`，仅 eval 冻结路径传 `true` | §4.5 |
| 6 | Minor | `/eval/search` 只暴露 6 参数，其余走默认值（Phase 3 再议） | §4.5 |
| 7 | 可后置 | search 签名改动走重载（保留 3 参）零破坏（约 14 处 mock） | §4.8 |
| 8 | 可后置 | reset 关联表孤儿行（memory_history / governance_undo） | §4.8 |
| 9 | 可后置 | eval profile 需含 `memory.eval.enabled=true` | §4.8 |

### v5（已保留）：exact 贯穿链 / 幂等 seed 查重+addDirect void→Long / 治理排除 vs replay 冲突(选 a) / AC#6 embedding 前置 / 双模式检索 / @RequestMapping / DTO 补 type / extract 请求契约 / SearchParams 6 方法 / 基线 id bug
### v4（已保留）：存储隔离 A / seed id 映射 / reset 三处 / retrieveBudgetedContext / 熔断 victim / AC#6 跨周期 / expire 分支删除 / reranker 措辞 / DTO schema / 幂等 pin
### v3（已保留）：冻结漏 reranker / 治理 4 scan / 结构化注入 / 熔断器措辞 / expire 墙钟 / reset+version / recency epoch / 仅 clusterDuplicates 威胁 V1 / model_version / 负样本 / cache key 清单
### v2（已保留）：冻结+AC#6<ε / cache key / 种子幂等 / 治理排除 / 抽取方差 / 反哺后置 / on-demand replay / 成对胜率后置 / 熔断器+metrics 事实 / Principles canonical + Option2 理由 + contract 漂移 + addDirect 签名 + /eval/params

---

## 0. 现状核实结论

### 0.1 已有 HTTP API 层
`MemoryController`（`sutone-agent-bok-trigger/.../http/MemoryController.java`）挂在 `/api/v1/memory`，6 端点：search / list / {id} / DELETE {id} / migrate/all / refresh。

### 0.2 缺口
1. `addDirect(Long userId, MemoryTypeVO type, String content)`（type 是 enum）无端点。
2. `MemoryExtractor.extract(...)` 无端点（请求需 `existingMemories + newMessages + lastMessages`；返回 `List<MemoryCandidate>`，`decideOperation` 需先 embed）。
3. `search` 参数不可调（threshold/taskType/rrfK 均默认）。
4. `/search` 元数据缺字段：`MemoryItemDTO`（`:13-21`）缺 `confidence/sourceArticleTitle/sourceArticleSummary`，且 `MemoryController.search`（`:53-58`）**还丢了 `type`**。
5. `retrieveContext` 无结构化端点（只返回 String）。
6. 治理产物无查询端点。
7. 熔断器只读（有 open/close/halfOpen 方法，底层单一 `AtomicBoolean degraded`）。
8. 记忆指标无 HTTP 端点（仅两 getter）。

### 0.3 鉴权
JWT 在 httpOnly Cookie `token`；`/api/v1/memory/**` 需 authenticated；HS256。Python 用专用 eval 用户 + CookieJar 零鉴权改动。port 8091，无 context-path，信封 `{code,info,data}`。

### 0.4 现有雏形 + 基线缺陷
`MemoryEvaluationTest`：`@SpringBootTest` 进程内 autowire，`addDirect` 灌 50 语料 + 20 query。**基线缺陷**：`relevantIds` 用静态 id 1-50（`:96-115`），但 `addDirect` 分配 auto-increment id（`:137`），绝对 Recall 不可靠。

### 0.5 有状态链路 + 共享存储全局态（根因）
- 访问回写：`recordAccessAsync` → 回写 access/importance（`MemoryAccessService:41-77`）。
- recency 墙钟（`MemoryRetriever:262-284`）+ expire 墙钟（`:288`）。
- reranker 仅 `enabled=true` 时是威胁（默认 false 直接 `subList` 粗排，`RerankerClient.java:53-55`）。
- **FULLTEXT 全表 IDF**：`fulltextSearch` 用 `MATCH ... NATURAL LANGUAGE MODE`（`IMemoryRecordDao.java:77-86`），`WHERE user_id` 只过滤结果不过滤统计；软删 ghost rows 漂移 IDF。
- **HNSW 近似 + 全局单 collection**：`QdrantVectorStore.search` 无 `exact:true`/`hnsw_ef`（`:161-201`）。
- `IMemoryVectorStore.search` 签名 `search(Long userId, float[] queryEmbedding, int topK)`（`:12`），无 exact 承载位。
- **治理 compute/apply 焊死**：`clusterWithinGroup`（`MemoryGovernanceJob.java:302-361`）「算簇」与「落库」焊死（`:352` updateStatus + `:358` recordUndo）；`checkFactConsistency` 同理（`:187/:192`）。`selectActiveForDuplicateScan()`（`:207-212`）全表无 user 过滤。
- 治理 5 @Scheduled（仅 clusterDuplicates 威胁 V1；`defaultExpireTime` 对 PREFERENCE/KNOWLEDGE/EVENT 非 null）。
- `uk_user_hash (user_id, content_hash)` 已存在；SQL `MD5(CONCAT(9999,content))` 与 Java `MD5(content)` 不一致。
- `addDirect` 返回 `void`（`:290`）无条件 insert（`:295`），无 `selectByUserIdAndHash` 查重。

---

## 1. Requirements Summary

用户诉求：「能协助记忆系统调优，能调参、跑测试集、看指标，能连接我的应用，是一个独立的评测系统」。四模块：测试集管理 + 指标可视化 + 参数可视化调整 + 结果可视化。

**已确定架构约束（5 条，不可推翻）**（与 §10 Principles 不是同一组）：
1. 部署独立、逻辑不独立 —— HTTP 调真实接口，不复制逻辑（存储隔离 = 数据/索引隔离）。
2. 参数动态化是调参前提 —— 默认值兼容。
3. 反哺闭环是灵魂（降级 Phase 5）。
4. 样本三源汇合。
5. 评测集版本化 + 结果可复现（重定义「跨周期冻结态差异 < ε」）。

**功能 F1–F6 + 非功能 + MVP 边界**：MVP = Phase 0–4。

---

## 2. 架构设计

### 2.0 存储隔离决策（已判终态正确）—— 选 A
- **A（选定）**：专用 MySQL database + 专用 Qdrant collection + 专用 eval 用户；hard-delete reset + `OPTIMIZE TABLE`；冻结态 `exact:true`。
- B（降级预案）：共享实例专用 schema + exact + FULLTEXT 稳定断言。
- C（排除）：维持共享库（BM25 IDF 无法冻结）。

### 2.1 双模式检索（解决 Driver#1 真实性矛盾）
- **确定性 gate 模式**：冻结态 + `exact:true` + 隔离 collection → AC#6「跨周期可复现 < ε」。
- **production-fidelity 模式**：`exact:false`（HNSW 近似）→ 调参 A/B，**标注非复现 + 带方差容差**。

### 2.2 总体拓扑
```
┌─────────────────────────────┐  HTTP/JSON（Cookie JWT）   ┌──────────────────────────┐
│ 记忆系统（Java）              │ ◄──────────────────────── │ 评测平台（Python/FastAPI） │
│ MemoryController +           │ /api/v1/eval/**            │ connector+engine+datasets │
│ MemoryEvalController         │ seed/search/extract/       │ +params+results+feedback  │
│ eval冻结(exact/reranker旁路) │ retrieve-context/governance│ SQLite/Postgres          │
│ 参数动态化 + 缓存key修正      │ (replay只读/samples)/reset │ React 前端               │
└─────────────┬───────────────┘ /params/metrics/circuit     └──────────────────────────┘
  专用 MySQL database + 专用 Qdrant collection + 专用 eval 用户（存储隔离 A）
```

### 2.3 通信协议 + Python 模块
REST/JSON + `httpx`(CookieJar)；`connector/java_client.py` 封装全部 eval 端点。模块 `app/{main,config,connector,datasets,engine,params,results,feedback,models,api}` + `web/` + `tests/`。

---

## 3. 技术选型

| 层 | 选择 | 理由 |
|---|---|---|
| Python 框架 | FastAPI | 异步、自动 OpenAPI |
| HTTP 客户端 | httpx | async + CookieJar |
| 指标计算 | 自研 + numpy（单测） | 基础公式无重依赖 |
| 数据存储 | SQLite + SQLAlchemy（MVP） | 独立边界、可切 Postgres |
| 可视化 | React + Vite + ECharts | 雷达/折线/柱状 |
| 数据集/结果 | JSON/YAML + 版本号 | 可读可 diff 可复现 |

---

## 4. 记忆系统（Java）侧改造

### 4.1 新增评测专用接口（`MemoryEvalController`，`@RequestMapping("/api/v1/eval")`）

| 端点 | 暴露 | 契约 |
|---|---|---|
| `POST /api/v1/eval/seed` | `addDirect` 批量 | 请求 `[{type, content}]`；返回 `content → createdId` 映射；**幂等 key canonical = `MD5(content)`**（`addDirect` 用 `DigestUtils.md5Hex(content)`）；**退役 `docs/dev-ops/mysql/sql/11-eval-insert-corpus.sql`**（其用 `MD5(CONCAT(9999,content))` 不一致），seed 端点为唯一灌入通道 |
| `POST /api/v1/eval/reset` | DAO `deleteByUserId` + 向量库 `deleteByUser` | 物理删 + `bumpMemoryVersion(userId)` |
| `GET /api/v1/eval/search` | `memoryRetriever.search`（新端点） | 参数：`threshold/rrfK/topK/taskType/freezeSideEffects/exact`；返回含补全字段的 `MemoryItem` 列表 |
| `POST /api/v1/eval/extract` | `MemoryExtractor.extract(...)` | 请求 `{existingMemories, newMessages, lastMessages}`；**响应仅 `List<MemoryCandidate>`（移除 decideOperation）**——`decideOperation` 需先 embed 候选，AC#4 只依赖 extract 输出 |
| `POST /api/v1/eval/retrieve-context` | 直调 `retrieveBudgetedContext(...)` bypassCircuit | 返回 `{budgeted: List<MemoryItem>, formatted: String}` |
| `GET /api/v1/eval/governance-samples` | 治理产物只读导出 | 三源样本，导入后脱钩 |
| `POST /api/v1/eval/governance/replay` | **只调 compute 层（只读）** | 重放 clusterDuplicates/checkFactConsistency 决策，对照 ground truth 算误合并/漏隔离；**不落库** |
| `GET /api/v1/eval/circuit-breaker` | `isDegraded()` | `{degraded: bool}` |
| `GET /api/v1/eval/metrics` | 两 getter | `extractionRejectRate` + `vectorSyncPendingCount` |
| `GET /api/v1/eval/params` | 参数当前值只读 | 单一真源 |

> `MemoryController.search`（`/api/v1/memory/search`）**仅随 `SearchParams` 重构同步编译 + 补 DTO 字段**（`type/confidence/sourceArticle*`），**不新增参数暴露**；评测用 `/api/v1/eval/search`。

### 4.2 治理「排除」与「replay 只读」解耦（MAJOR v5 #3 + v6 #1）

- **replay 只读性（compute/apply 分层，v6 MAJOR）**：把 4 个 scan 拆两层——
  - **compute 层**：返回簇/冲突对决策（如 `List<ClusterDecision>`/`List<ConflictDecision>`），**不落库**（不含 `updateStatus`/`recordUndo`）。
  - **apply 层**：`updateStatus` + `recordUndo`（仅 cron 路径调用）。
  - **cron 调两层**（compute → apply），**`/eval/governance/replay` 只调 compute 层**（只读，不污染语料、不击穿 AC#6/AC#7）。
- **eval profile 禁用 cron**（选 a）：`@ConditionalOnProperty(name="memory.eval.enabled", havingValue="false", matchIfMissing=true)` 挂 5 个 `@Scheduled`，eval 环境 cron 不跑。
- **userId 作用域 DAO（v6 补）**：加 `selectActiveForDuplicateScanByUserId(userId)` 与 `selectActiveForConsistencyScanByUserId(userId)`（当前 `selectActiveForDuplicateScan()` `:207-212` 全表无 user 过滤，replay 无法定位 eval 语料）。
- 事实修正：V1 真实威胁只有 clusterDuplicates（checkFactConsistency 不命中 addDirect 种子）。

### 4.3 `search` 参数动态化 + 缓存 key
- 缓存 key 纳入动态参数清单：`rrfK/threshold/topK/taskType/alpha/beta/recencyHalfLifeDays/profileBoost/reranker.enabled`。

### 4.4 eval 冻结模式（6 要素）
`SearchParams.freezeSideEffects=true` 下冻结：
1. 跳过访问回写（`recordAccessAsync`）。
2. 旁路缓存。
3. recency 固定 epoch（`memory.eval.recency-baseline`）。
4. reranker 旁路（`skipRerank`/`reranker.enabled=false`）。
5. expire 墙钟 pin（仅 pin，不 assert expire=null）。
6. `exact:true` 贯穿链（§4.5）。

### 4.5 参数动态化 + 可实现性改造点（domain 层）

| 文件 | 当前状态 | 改造 |
|---|---|---|
| `IMemoryVectorStore` | `search(Long userId, float[] queryEmbedding, int topK)` 无 exact 位；仅 `delete(Long memoryId)` | 加 `search(..., boolean exact)`（或 VectorSearchOptions）；加 `deleteByUser(userId)` |
| `QdrantVectorStore` | body 无 `search_params`/`exact` | body 加 `"params":{"exact":true}`；`deleteByUser` delete-by-filter |
| `SimpleMemoryVectorStore` | — | `exact` no-op；`deleteByUser` 过滤删点 |
| **exact 调用点（4 处）** | `MemoryRetriever:145`、`MemoryManager:143`、`MemoryExtractor:163`、`MemoryGovernanceJob:319` | 4 处 threading `exact`；**非 eval 三处（MemoryManager:143 / MemoryExtractor:163 / MemoryGovernanceJob:319）缺省 `false`（保持现有 HNSW），仅 eval 冻结路径传 `true`** |
| `MemoryRetriever.java` | `searchCacheKey` 缺参；`budgetByType` private；`retrieveFormattedContext` 丢 budgeted；6 方法各自读 memoryProperties | 加 `SearchParams`（thread 进 6 方法：rrfFuse/finalScore/computeRecencyNorm/filterHits/loadProfileIds/rerankIfNeeded）；修正 cache key；加 public `retrieveBudgetedContext` |
| `MemoryExtractor.java` | `similarityThreshold`=@Value(0.9)；置信度/temp 硬编码 | 加 `ExtractParams` overload |
| `MemoryManager.java` | `addDirect` 返回 void 无条件 insert；`bumpMemoryVersion` private | `addDirect` void→Long（`selectByUserIdAndHash` 命中返回现有 id，否则 insert 返回 key）；暴露 `bumpMemoryVersion` |
| `IMemoryRecordDao`/`IMemoryRepository` | 无 `selectByUserIdAndHash`；无 `deleteByUserId` | 加 `selectByUserIdAndHash` + `deleteByUserId`（物理删）+ **`selectActiveForDuplicateScanByUserId` / `selectActiveForConsistencyScanByUserId`** |
| `MemoryGovernanceJob.java` | 5 @Scheduled；compute/apply 焊死 | **拆 compute 层（只读，返回决策）/ apply 层（落库）**；eval profile 禁用 cron；阈值入 `GovernanceParams` |
| `MemoryCircuitBreaker.java` | `@Value`；单一 degraded 布尔 | `isDegraded()` 只读；eval 流量不计 reject 率分母 |

> **SearchParams 承载 vs `/eval/search` 暴露（v6 Minor #6）**：内部 `SearchParams` 承载全部参数（`threshold/rrfK/topK/taskType/alpha/beta/recencyHalfLifeDays/profileBoost/minConfidence/freezeSideEffects/exact/skipRerank`），但 `/eval/search` 端点只暴露 6 个（`threshold/rrfK/topK/taskType/freezeSideEffects/exact`）；其余走默认值，Phase 3 再决定是否补全暴露。

### 4.6 鉴权方案
- 方案 A（默认）：专用 eval 用户 + cookie 登录，零鉴权改动。
- 方案 B（可选）：`memory-eval` profile + `X-Eval-User-Id` 内部头。

### 4.7 关键文件引用
`trigger/.../http/{MemoryController,MemoryEvalController}.java`、`trigger/.../security/SecurityConfig.java`、`domain/.../service/memory/{MemoryManager,MemoryRetriever,MemoryExtractor,MemoryAccessService}.java`、`domain/.../model/entity/MemoryRecordEntity.java`、`infrastructure/.../{job/MemoryGovernanceJob,metrics/MemoryMetrics,MemoryCircuitBreaker,dao/IMemoryRecordDao,adapter/repository/{IMemoryVectorStore,QdrantVectorStore,SimpleMemoryVectorStore}}.java`、`api/.../dto/memory/MemoryItemDTO.java`、`app/src/test/.../memory/*.java`、`*.sql`（`uk_user_hash`；**退役 `11-eval-insert-corpus.sql`**）。

### 4.8 可后置 Minor（附注，不阻塞 MVP）
1. **search 签名改动测试爆炸半径**：约 14 处 mock 桩（MemoryRetrieverTest 9 处 + MemoryManagerTest:128 + MemoryExtractorTest:272/284/295 + MemoryExtractorDecisionTest:108）。**建议走重载**（保留 3 参 `search(userId, queryEmbedding, topK)` 方法）零破坏。
2. **reset 关联表孤儿行**：`memory_history` / `memory_governance_undo(_item)` 会留孤儿行，低优先（不影响检索，仅占空间），Phase 5+ 再清理。
3. **eval profile 属性**：eval profile 必须含 `memory.eval.enabled=true`（配合 `@ConditionalOnProperty` 禁 cron），缺省时 cron 仍跑、污染复现。

---

## 5. 五个模块具体设计

### 5.1 测试集管理（datasets）
`Dataset`（版本号）+ `Case`（case_type + group）。三源导入；版本化不可变；run 绑定 `dataset_version`。V1 = 迁移 50+20。

### 5.2 评测引擎（engine）—— 五维可归因指标
**复现性分级**：确定性维度（检索/一致性/治理，隔离+冻结+exact 跨周期可复现）；随机维度（抽取、成对胜率，多样本+方差）。

| 维度 | 指标 | 复现性 | 数据源 |
|---|---|---|---|
| ① 抽取 | Precision/Recall/错误归属率 | 随机（多样本+方差） | `/eval/extract`（List<MemoryCandidate>） |
| ② 检索 | Recall@K/MRR/NDCG@K/Hit@1 | 确定性（exact gate）/ 近似（HNSW fidelity，方差容差） | `/eval/search` |
| ③ 一致性 | 重复率/冲突率 | 确定性 | 语料 + replay |
| ④ 注入 | 无关注入率（budgeted score 近似）；成对胜率（Phase 5+） | 无关注入率=确定性 | `/eval/retrieve-context` |
| ⑤ 治理 | 误合并率/漏隔离率（replay 只读 + 近重复对） | 确定性 | `/eval/governance/replay` |

- `EvalRun.model_version` 编码真实 embedding model id + reranker model id + config hash。
- `EvalRun` 需 seed→id 映射（`/eval/seed` 返回 content→createdId）。
- A/B：同 `dataset_version` 多组 `param_snapshot` 并行 run；HNSW fidelity A/B 标注非复现 + 方差容差。

### 5.3 参数调整（params）
真源 = `/eval/params`；叠加可调范围/描述；滑块 → `param_snapshot`（含 freezeSideEffects/skipRerank/exact）→ 绑定 run。

### 5.4 结果可视化
雷达图 + 明细 + 趋势 + A/B + 分组 + 抽取方差 + 冻结态/隔离态/exact-vs-HNSW 口径标注。

### 5.5 反哺闭环（Phase 5 增量）
错误样本 → 复核 → 归档新版本 → 回归 → diff。

---

## 6. Acceptance Criteria（可测试）

1. **连接**：Python 登录 Java 调 `search`，返回结构一致。
2. **种子 + id 映射**：`/eval/seed` 返回 `content→createdId` 映射（`addDirect` void→Long）；幂等命中返回现有 id、无 DuplicateKeyException；MD5 canonical = `MD5(content)`。
3. **清理**：`/eval/reset` 物理删数据 + 向量点 + `bumpMemoryVersion`（缓存不 stale）。
4. **抽取评测**：`/eval/extract` 接收 `{existingMemories,newMessages,lastMessages}`，返回 `List<MemoryCandidate>`，输出 Precision/Recall/错误归属率 + 方差（不依赖 decideOperation）。
5. **检索评测（标注基线缺陷）**：50+20 输出 Recall@5/MRR/NDCG@5/Hit@1；**注：现有 `MemoryEvaluationTest` 的 relevantIds 用静态 id 与 auto-increment id 不符，基线数值仅作相对参考**。
6. **可复现（跨周期）**：同一 `(dataset_version, param_snapshot, model_version)`，reset→seed→run 完整周期 N≥3（含跨天/跨 reset），确定性维度最大绝对差 < ε=0.01；前提含 FULLTEXT 稳定 + `exact:true` + reranker 旁路 + embedding 确定性前置假设。
7. **治理重放 + 负样本**：`/eval/governance/replay`（**只调 compute 层，只读不落库**）在含 deliberate 近重复对语料上返回决策，得误合并率/漏隔离率；eval profile 下 cron 不跑。
8. **治理样本接入**：`/eval/governance-samples` 拉取产物生成用例（脱钩）。
9. **熔断 victim-path 隔离**：真实熔断时 `/eval/retrieve-context` 仍返回非空 `budgeted`。
10. **边界**：Java 旧路径回归全绿；eval 流量不触发全局熔断误 open；`exact` 贯穿 4 调用点且非 eval 三处缺省 false、Qdrant body 含 `params.exact`。

---

## 7. Implementation Steps（分阶段）

MVP = Phase 0–4；Phase 5 = 反哺闭环 + 成对胜率；Phase 6 = 联调验收。

### Phase 0 — Java 侧（前置，含实现清单）
1. `IMemoryVectorStore.search` 加 `exact` 参数（**走重载，保留 3 参方法零破坏**）；Qdrant body `params.exact`；Simple store no-op；threading 4 调用点（非 eval 三处缺省 false）。
2. `MemoryRetriever.java`：`SearchParams`（thread 6 方法）；修正 cache key；加 public `retrieveBudgetedContext`。
3. `MemoryExtractor.java`：`ExtractParams` overload。
4. `IMemoryRecordDao`/`IMemoryRepository`：加 `selectByUserIdAndHash` + `deleteByUserId` + `selectActiveForDuplicateScanByUserId` + `selectActiveForConsistencyScanByUserId`；`IMemoryVectorStore` 加 `deleteByUser`；`MemoryManager`：`addDirect` void→Long + 暴露 `bumpMemoryVersion`。
5. `MemoryGovernanceJob.java`：**拆 compute 层（只读）/ apply 层（落库）**；eval profile 下 `@ConditionalOnProperty` 禁用 cron。
6. `MemoryEvalController.java`（`@RequestMapping("/api/v1/eval")`）：seed(幂等+id 映射)/search(6 参数)/extract(返回 List<MemoryCandidate>)/retrieve-context(结构化+bypassCircuit)/governance-samples/replay(只调 compute)/circuit-breaker/metrics/params。
7. `MemoryController.search` 随 SearchParams 同步编译 + `MemoryItemDTO` 补 type/confidence/sourceArticle*。
8. 存储隔离 A 落地：专用 MySQL database + 专用 Qdrant collection + 专用 eval 用户 + eval profile（含 `memory.eval.enabled=true`）。
9. DTO + 契约（schema 文档）；退役 `11-eval-insert-corpus.sql`。
10. 单测：默认值回归 + 参数生效 + 冻结态无副作用 + reset 物理删 + 治理禁用 + replay 只读 + 幂等。

### Phase 1 — Python 骨架 + 连接层
11. `eval-platform/`（FastAPI + SQLAlchemy）。
12. `connector/java_client.py`：登录 + 全部 eval 端点 + 重试/超时/健康检查。
13. 契约测试 + 真实 smoke test。

### Phase 2 — 测试集管理
14. `datasets/` CRUD + 版本化 + 导入导出 + schema 校验。
15. 迁移 50+20 V1（**修正 ground-truth id 映射为 seed 返回的 createdId**）；orchestrator 每轮 reset→seed。

### Phase 3 — 评测引擎 + 指标
16. `engine/metrics.py`：五维指标 + 单测（确定性锚定；抽取方差）。
17. run 编排：绑定 dataset_version + param_snapshot + model_version + seed id 映射；run 开头 embedding 稳定性自检。

### Phase 4 — 参数管理 + 结果可视化
18. `params/`：/eval/params 真源 + 快照 + 下发。
19. `results/` + `web/`（雷达/趋势/A-B/分组/方差/exact-vs-HNSW 口径）。

### Phase 5 — 反哺闭环 + 成对胜率（增量）
20. `feedback/` 复核 → 归档 → 回归 → diff。
21. 成对胜率引入 LLM judge。

### Phase 6 — 联调验收
22. 跑 §6 全部 AC（重点 AC#6 跨周期 + replay 只读 + 熔断 victim + embedding 稳定性）。
23. 部署文档（专用 DB/collection + 依赖 + eval profile）。

---

## 8. Risks and Mitigations

| 风险 | 影响 | 缓解 |
|---|---|---|
| 逻辑被复制 | 评测失真 | connector 只走 HTTP；review 卡「禁止重实现检索/抽取/budgetByType/compute 层」 |
| replay 落库污染（compute/apply 焊死） | 语料漂移、AC#6/AC#7 击穿 | **compute/apply 分层，replay 只调 compute（只读）** |
| 共享存储全局态（FULLTEXT IDF + HNSW + ghost rows） | AC#6 跨周期不可达 | 存储隔离 A：专用 DB/collection + hard-delete + OPTIMIZE + exact |
| embedding run-to-run 浮点漂移 | AC#6 误判 | 前置假设 + run 开头 embedding 稳定性自检 |
| 有状态漂移（访问回写 + 墙钟 + reranker 抖动） | 间歇不可达 | 冻结 6 要素（含 reranker 旁路 + expire pin + exact） |
| 熔断 victim-path | 注入评测失真 | retrieve-context 直调 retriever bypassCircuit |
| exact vs 生产 HNSW 口径差异 | 调参反哺削弱 | 双模式：exact gate + HNSW fidelity（方差容差） |
| 缓存 key 缺参致 A/B 假结果 | 调参失效 | cache key 纳入清单 + 冻结旁路缓存 |
| 参数动态化破坏旧行为 | 线上回归 | 默认值=旧值；重载保留 3 参；回归单测 |
| 种子无幂等/残留/stale | 重复行/向量/stale | 幂等 pin `(user_id,MD5(content))` + reset 物理删 + bumpVersion |
| 治理 cron 污染 / replay 空集 | 语料漂移 / AC#7 失败 | eval profile 禁用 cron + replay 独立 userId 查询 + compute 只读 |
| 抽取维度 LLM 噪声 | 回归 diff 被噪声主导 | 抽取用多样本+方差 |
| 跨语言 contract 漂移 | DTO 变更解析失败 | 契约测试 + schema 文档 + 版本化 DTO |
| 真实 Qdrant/embedding 依赖 | Connection refused | docker-compose + SimpleMemoryVectorStore 兜底 |
| model_version 不可信 | 跨 run 漂移 | 编码真实 embedding+reranker id/config hash |

---

## 9. Verification Steps

1. **单元**：pytest 跑 metrics（确定性锚定、抽取方差）、datasets、params。
2. **契约**：mock Java 接口验证 connector DTO 解析 + seed id 映射（防 contract 漂移）。
3. **Java 回归**：`mvn test` 全绿（重点 Memory 相关 + MemoryGovernanceJob；search 重载不破坏 14 处 mock）。
4. **冻结态验证**：`search` 不写 access/importance、不命缓存、recency 固定 epoch、reranker 旁路、expire pin、`exact:true`（Qdrant body 含 `params.exact`）。
5. **embedding 稳定性自检**：run 开头重 embed 同一固定 corpus 两次，断言 max cosine delta ≈ 0。
6. **可复现（AC#6 跨周期专项）**：固定配置 reset→seed→run 完整周期 N≥3，断言确定性维度最大绝对差 < 0.01。
7. **replay 只读**：`/eval/governance/replay` 调用后，数据库无 `updateStatus`/`recordUndo` 落库（compute 层只读）；cron 不跑（eval profile）。
8. **调参生效**：改 rrfK/threshold 两次跑，断言指标有差异且非缓存假象；HNSW fidelity A/B 标注方差容差。
9. **治理排除 + 熔断 victim**：真实熔断时 retrieve-context 仍非空。
10. **端到端**：真实起 Java + 专用 DB/collection + 依赖，跑 §6 全 AC；Phase 5 反哺闭环走通。

---

## 10. RALPLAN-DR 摘要

**模式：SHORT**（如需 pre-mortem + 扩展测试计划可升级 DELIBERATE）。

### Principles（5 条，canonical —— §1「5 条约束」是另一组）
1. **测的是线上那套**：HTTP 调真实接口，不复制逻辑（存储隔离 = 数据/索引隔离；replay 只读 = 复用 compute 层而非落库逻辑）。
2. **可复现优先于完美模拟**：确定性维度隔离+冻结+exact 跨周期 < ε；随机维度用方差。
3. **参数动态化是调参前提**：每请求可下发，缓存 key 完整，默认值向后兼容。
4. **样本→评测→反哺的数据飞轮**（Phase 5 增量）。
5. **边界清晰**：独立部署/前端/数据，最小侵入 Java，契约稳定。

### Decision Drivers（top 3）
1. **真实性**：测线上真实检索/抽取（双模式：exact gate + HNSW fidelity）。
2. **可复现**：状态漂移 + 共享存储全局态都必须隔离。
3. **可调参**：参数动态下发并真实生效（缓存 key 正确）。

### Viable Options
**Option 1（推荐 / 本方案）**：独立 Python 平台 + HTTP 调真实接口 + 存储隔离 A + eval 冻结（exact 贯穿/reranker 旁路）+ 双模式检索 + Java 侧 eval 接口与参数化。
- 利：真实、可复现、可调参、独立部署/前端/数据、支持飞轮；Java 侵入最小且向后兼容。
- 弊：跨语言联调；部署面；Java 改造面（eval 接口 + 参数化 + 冻结 + exact 贯穿 + 缓存 key + 治理 compute/apply 分层 + reset 三处 + addDirect void→Long）。

**Option 2（排除）**：纯 Java 增强现有测试模块（无法独立部署/前端/数据 + 可视化弱）。
**Option 3（排除）**：Python 复制检索/抽取逻辑离线评测（测的不是线上那套）。

**结论**：Option 1 唯一存活（内含存储隔离 A + 双模式检索 + replay 只读分层）。

---

## 11. ADR（最终方案）

- **Decision**：独立 Python（FastAPI+React）评测平台，HTTP 调真实接口；Java 侧新增 `MemoryEvalController`（`/api/v1/eval/**`：seed[返回 id 映射]/search[6 参数]/extract[返回 List<MemoryCandidate>]/retrieve-context[结构化+bypassCircuit]/governance-samples/replay[只读 compute 层]/circuit-breaker/metrics/params）+ 参数动态化 + eval 冻结（reranker 旁路 + expire pin + `exact:true` 贯穿）+ 缓存 key 修正 + 治理 cron 禁用 + **存储隔离 A** + **双模式检索** + **治理 compute/apply 分层**。
- **Drivers**：真实性、可复现、可调参。
- **Alternatives considered**：存储隔离 B（降级预案）、C（排除）；治理「硬编码排除」vs「eval profile 禁用 cron + replay 只读 compute」（选后者）；exact vs HNSW（选双模式）；extract 端点带 decideOperation vs 移除（选移除）；纯 Java（Option 2）、Python 复制逻辑（Option 3）均排除。
- **Why chosen**：唯一同时满足「独立部署 + 真实 + 跨周期可复现 + 可调参」；「可复现」与「真实性」双模式分工；replay 只读保证评测不污染语料。
- **Consequences**：部署面增加；冻结态+隔离态与线上态口径差异（已标注）；「可复现」= 隔离+冻结+exact 跨周期 < ε；抽取维度方差；model_version 编码真实模型；embedding 确定性前置；seed 返回 id 映射、reset 物理删 + bumpVersion、addDirect void→Long、search 走重载零破坏。
- **Follow-ups**：鉴权方案 B；生产存储 Postgres；成对胜率 LLM judge；监控异常下钻；/eval/metrics 经 actuator 扩展；MVP 无法承担独立 DB 时降级 B；reset 关联表孤儿行清理（Phase 5+）。

---

## 12. Open Questions（已同步 open-questions.md）

- [ ] ε 取值（默认 0.01）是否可接受。
- [ ] Python 平台自身存储：SQLite vs Postgres。
- [ ] 前端 React vs Dash。
- [ ] 参数动态化范围：/eval/search 是否 Phase 3 补全 alpha/beta/recencyHalfLifeDays/profileBoost/minConfidence 暴露。
