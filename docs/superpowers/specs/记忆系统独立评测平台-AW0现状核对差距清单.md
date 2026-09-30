# AW-0 现状核对差距清单

> 本文是《记忆系统独立评测平台-AgentWrite项目改造工作》的 AW-0「代码现状核对和契约冻结」交付物。
> 逐项核对了真实 Java 代码与方案假设的差异，结论用于校准 16 人天估算。
> 核对时间：2026-09-29。核对基线：当前 `main` 分支工作区。

## 一、结论摘要

- **16 人天估算总体成立**，内部有增减，净变化约 ±1 人天。
- **最大的好消息**：`memory_record` 表已有 `UNIQUE KEY uk_user_hash(user_id, content_hash)`，`content_hash = MD5(content)` 口径与方案一致 → seed 幂等可大幅简化。
- **最大的坏消息**：**当前系统无角色体系**（`SecurityConfig` 注释明写「当前系统无角色体系」），`ROLE_EVAL` 需从 `JwtAuthenticationFilter` 起零建。
- **最硬的隐性约束**：`uk_user_hash` 唯一索引**不区分 `is_deleted`/`status`** → reset 必须**物理删除**，否则软删行仍占用唯一键、block 重新 seed。

## 二、逐项核对结果

| # | 方案假设（AW-0 检查点） | 结论 | 证据 / 说明 |
|---|---|---|---|
| 1 | `MemoryRetriever.MemoryItem` 含 `content` | ✅ 已有 | `record MemoryItem(Long id, String content, double score, Double importance, MemoryTypeVO type, Double confidence, String sourceArticleTitle, String sourceArticleSummary)`，`MemoryRetriever.java:517` |
| 2 | `MemoryItemDTO` 已有 `content` 字段 | ✅ 已有 | `MemoryItemDTO.java:16` |
| 3 | `MemoryProperties` 可读检索/注入/治理/告警配置 | ⚠️ 大部分已有，缺 eval 块 | 已有 `retrieval`(rrfK/alpha/beta/recencyHalfLifeDays/profileBoost/minConfidence)、`inject`(maxTokens)、`alert`(enabled+5阈值+window/evalInterval)；**缺** `memory.eval.enabled`、freeze/exact/hnsw_ef 默认值、eval_user_id 的 base/range |
| 4 | `IMemoryRecordDao` 有 `content_hash` | ✅ 已有 | `memory_record.content_hash` 列 + `MemoryRecordPO.contentHash` + 唯一索引 `uk_user_hash` |
| 5 | `IMemoryRecordDao` 有 `selectByUserIdAndHash` | ❌ 缺失 | 需新增 `@Select` 按 (user_id, content_hash) 查 |
| 6 | `IMemoryRecordDao` 有物理删除 | ❌ 缺失 | 只有软删 `deleteById`(`is_deleted=1`)；需新增 `deleteByUserId` 物理 DELETE |
| 7 | `IMemoryVectorStore` 可按 userId 删除 | ❌ 缺失 | 只有 `delete(Long memoryId)`；需新增 `removeByUserId(Long userId)` |
| 8 | Qdrant 支持同步 delete-await | ❌ 缺失 | `QdrantVectorStore.delete` 用 `points/delete`，fire-and-forget，无确认；需改用 filter 删除 + 等待 |
| 9 | `MemoryGovernanceJob` 的 `@Scheduled` 清单 | ✅ 已核清 | 共 **7 个定时任务**，见 §三.C |
| 10 | 已有可复用的评测测试 | ✅ 已有 | `MemoryEvaluationTest`：50 语料 + 20 query + 6 组，Recall@5/MRR/Hit@1/NDCG@5，用 `addDirect` + `search` |

## 三、关键发现（影响工作量的 5 点）

### A. 无角色体系，ROLE_EVAL 从零建（AW-2 上浮）

`SecurityConfig.java:41-43` 注释明写：「当前系统无角色体系，先以 authenticated() 兜底」。
`MemoryController.getCurrentUserId()` 从 JWT principal 直接 cast 成 `Long`，无角色。
→ AW-2 不只是「加个 `ROLE_EVAL`」，而是要：`JwtAuthenticationFilter` 能解析角色 → `SecurityFilterChain` 对 `/api/v1/eval/**` 加 `hasRole("EVAL")` → 新增 `MemoryEvalController` 用 `eval_user_id` 范围校验替代业务 userId。**1 人天 → 1.5 人天。**

### B. `uk_user_hash` 唯一索引已存在（AW-3 下浮）

`09-sutone-agent-bok-phase9-memory.sql:21`：
```sql
UNIQUE KEY `uk_user_hash` (`user_id`, `content_hash`)
```
且 `content_hash = MD5(content)`（`MemoryManager.addDirect` 用 `DigestUtils.md5Hex`，UTF-8，与方案口径一致）。
→ seed 幂等**不需要**先 `selectByUserIdAndHash` 再决定，直接 `insert` + 捕获 `DuplicateKeyException` 转 `existed` 即可；`selectByUserIdAndHash` 降级为「返回 existed 时回查 createdId」的可选辅助。**1.5 人天 → 1 人天。**

### C. 共 7 个定时任务需在 eval profile 禁用（AW-1/AW-8）

| 任务 | 类 | 触发 |
|---|---|---|
| clusterDuplicates | MemoryGovernanceJob | cron 3:30 每日 |
| checkFactConsistency | MemoryGovernanceJob | cron 周一 4:00 |
| cleanExpired | MemoryGovernanceJob | cron 5:00 每日 |
| spotCheckHallucination | MemoryGovernanceJob | cron 周日 6:00 |
| inspectCorrectionCircuit | MemoryGovernanceJob | cron 每分钟 |
| syncPendingVectors | MemoryVectorSyncJob | fixedDelay 30s |
| refreshDerivedRates | MemoryMetrics | fixedDelay 60s |

这些都会改写 `memory_record`（软标记 status）或读写 Qdrant，eval 实例必须全部禁用。禁用手段：加 `@ConditionalOnProperty(name="memory.eval.enabled", havingValue="false", matchIfMissing=true)`。

### D. 检索有副作用，无 freeze 开关（AW-5）

`MemoryRetriever.doSearch` 尾部：`memoryAccessService.recordAccessAsync(hitIds)` + 写 Redis 搜索缓存。
→ `freezeSideEffects=true` 需旁路这两处；`exact`/`hnsw_ef` 需下钻到 `IMemoryVectorStore.search`（当前签名 `search(userId, queryEmbedding, topK)` 无这两个参数），改接口会波及两个实现。

### E. `addDirect` 非幂等、返回 void、同步写向量（AW-3 核心）

- 非幂等：无 dedup 检查，重复调用同 content 会撞 `uk_user_hash` 抛 `DuplicateKeyException`（当前**未捕获**）。
- 返回 `void`：需改为返回 `createdId` 或 `inserted/existed`。
- 同步写向量：`addDirect` 内直接 `vectorStore.upsert` + `updateVectorStatus(id,"SYNCED")`，不走异步 sync job → 对 seed 是好事（立即 ready），但要求 eval profile 的 `vector-store` 指向专用 collection。

## 四、对估算的影响

| 工作包 | 原估算 | 调整 | 依据 |
|---|---:|---:|---|
| AW-2 ROLE_EVAL | 1 | **1.5** | 无角色体系，从 JwtAuthenticationFilter 起建 |
| AW-3 seed 幂等 | 1.5 | **1** | `uk_user_hash` 已存在，仅需捕获 DuplicateKeyException |
| AW-4 reset 物理删除 | 2 | **2** | 物理删除 + Qdrant filter 删除 + delete-await，维持 |
| 其余 AW | — | 不变 | — |
| **合计** | 16 | **约 16** | 净变化约 +0（内部增减相抵） |

## 五、契约冻结建议（AW-0 出口）

在进入 AW-1 前先冻结以下契约（两侧同时依赖）：

1. `contentHash = MD5(content UTF-8 bytes)` 小写十六进制 —— **已与现状一致，冻结**。
2. `eval_user_id` 派生规则：`EVAL_USER_ID_BASE + (int(fingerprint[:8],16) % RANGE)`，`BASE`/`RANGE` 待定（需避开业务 userId 段，现状业务 userId 为真实用户 id，eval 段建议从 `9_000_000_000` 起）。
3. eval 端点返回统一 `Response<T>` 结构（现有 `Response` 包装已存在，`code/info/data` 三段），Python connector 据此解析。
4. `search` 返回 DTO 含 `id/content/score/importance/type/confidence` —— 现有 `MemoryItemDTO` 已覆盖 `id/type/content/score/importance`，需补 `confidence` 字段（当前 DTO 无）。

## 六、建议顺序

维持文档原顺序（无需调整）：

```text
AW-0 → AW-1 → AW-2 → AW-3 → AW-4 → AW-7 → AW-5 → AW-6 → AW-8 → AW-9
```

AW-3 因唯一索引已存在可提速，AW-2 因无角色体系需补 0.5 天；AW-7 可在 AW-4 后并行准备 DDL 与 service。
