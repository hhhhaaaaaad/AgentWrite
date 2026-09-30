# 记忆系统独立评测平台 —— 生产级方案（v12 终稿）

> 本文由 ralplan 共识规划流程产出。v11 经第 5 轮终审：Architect APPROVE、Critic REVISE（3 MAJOR），全部实质且明确，补完即最佳版本。
> v12 三处关键修正：① `eval_user_id` 改由 `config_fingerprint` 派生（消除同 config 历史累积）+ finalize 清理自身命名空间；② `content_digest` 纳入 `ground_truth`（反哺闭环对指纹可见）；③ fencing 残余风险诚实化（per-stage check 是 check-then-act，非原子兜底）。
> 日期：2026-09-29

---

## 零、核心设计决策（ADR）

### D-1 语料隔离粒度：per-config 派生 userId + finalize 清理（v12 修正）

**演进**：v11 用 `eval_user_id = BASE + hash(run_id)`——但 run_id 每 run 唯一，同 config 每次 run 换新命名空间，reset 清的永远是空命名空间 → **历史语料无限累积**。而 FULLTEXT 的 IDF 是全表统计、BM25 是混合检索常驻一路（无「exact 跳过 BM25」的 freeze 项），跨周期 IDF 随历史漂移击穿 AC#12。

**Decision（选定 per-config 派生）**：

```
eval_user_id = EVAL_USER_ID_BASE + (int(config_fingerprint[:8], 16) % EVAL_USER_ID_RANGE)
```

- **由 `config_fingerprint` 派生（非 run_id）**：同 config 的 run 共享同一命名空间；同 config 已被 `uq_runs_active_cfg` 强制串行，串行下 reset 天然清掉上次残留。
- **finalize 后清理自身命名空间**：run finalize 阶段（CAS 置 succeeded/failed/cancelled 后）worker 清理自身 `eval_user_id` 的语料（reset 自身命名空间）。使语料**不残留**，FULLTEXT IDF 长期稳定——「每次只一个 config 的语料在表」从近似变为真正成立。
- **不同 config 仍各自隔离**：保留并行 A/B（各自命名空间，互不干扰）。
- **IDOR 不变**：服务端派生、客户端不可传；Java 校验 `EVAL_USER_ID_BASE ≤ eval_user_id < BASE + RANGE`，越界拒绝。
- **id 漂移无关**：命名空间复用导致自增 id 漂移，但 Recall 用 contentHash 匹配，与 id 无关。

**次生约束（已知口径，诚实标注）**：并行 A/B（不同 config，不同命名空间）运行时，FULLTEXT IDF 对 A/B 双方**对称混合**（两 config 语料同表），exact 模式**绝对**指标有微小对称 IDF 偏移；相对对比仍有效。同 config 可复现 run 串行 + finalize 清理 → IDF 稳定，AC#12 不受影响。

**备选（fallback，写死触发条件）**：若 Java 侧硬约束「eval DB/collection 只能一个 user」（无法支持保留命名空间多 userId），退化 **corpus 级串行**：`uq_runs_active_corpus ON eval_runs ((1)) WHERE status IN ('pending','running')` 全局单飞 + Celery `--concurrency=1`。本方案按 per-config 隔离设计。

---

## 一、Requirements Summary

用户核心诉求：「能协助记忆系统调优，能调参、跑测试集、看指标，能连接我的应用，是一个独立的评测系统」，并明确要求「完善为生产级、能长久使用」。

### 1.1 功能需求

- **测试集管理**：三源汇合，版本化不可变，团队协作标注。
- **评测引擎**：五维指标（抽取 / 检索 / 一致性 / 注入 / 治理），A/B 调参对比（**并行**），跨版本趋势。
- **参数可视化调整**：`/eval/params` 单一真源，参数快照绑定 run。
- **结果可视化**：雷达 / 趋势 / A/B / 分组 / 方差，口径标注。
- **反哺闭环**：错误样本 → 复核 → 归档新版本 → 回归 → diff（**ground_truth 变更对指纹可见**）。

### 1.2 非功能需求

| 类别 | 需求 |
|---|---|
| **可靠性** | run 崩溃可恢复；任务不丢不重不并发；卡死可恢复；reset 彻底清空；语料不残留 |
| **并发** | run 重负载异步；**并行 A/B（per-config 隔离）** |
| **数据积累** | 多年积累、跨版本对比、复杂聚合（Postgres） |
| **多用户** | 两档 RBAC、评测集权限、标注归属、审计 |
| **安全** | eval 端点最小权限、IDOR 防护（保留命名空间）、纯内网 |
| **可观测性** | Flower + 结构化日志先行 |
| **可迁移** | Alembic、配置外部化、内容可寻址指纹（含 ground_truth） |

### 1.3 不可推翻的既有架构约束

1. 部署独立、逻辑不独立。2. 参数动态化。3. 反哺闭环是灵魂。4. 样本三源汇合。5. 版本化 + 可复现。

---

## 二、现有架构决策在生产级下的审查结论

> **5 条既有决策全部成立**（存储隔离 A / 双模式 / compute-apply 分层 / 冻结 6 要素 / 部署独立逻辑不独立），补强摘要同前，此处略。

---

## 三、架构设计

### 3.1 服务拓扑（纯内网，无公网暴露）

```
                      ┌──────────────────────────────────────────────┐
                      │       内网访问（无公网/TLS，内部工具）          │
                      └──────────────┬───────────────────────────────┘
                                     │ HTTP（平台自身鉴权 JWT）
           ┌─────────────────────────▼───────────────────────────────┐
           │                  platform 网络（内网）                   │
           │  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐ │
           │  │ FastAPI (API)│──▶ Celery worker │──▶ llm-judge(可选)│ │
           │  │ 鉴权/编排/CRUD│  │ 执行 run+心跳 │  │ 成对胜率(后置) │ │
           │  └──────┬───────┘  └──────┬───────┘  └───────────────┘ │
           │  ┌──────▼───────┐  ┌──────▼───────┐  ┌───────────────┐ │
           │  │ 前端 React   │  │ Postgres     │  │ Redis          │ │
           │  │ (内网)       │  │ (评测数据)   │  │ broker/result  │ │
           │  └──────────────┘  └──────────────┘  └───────────────┘ │
           │  ┌──────────────┐  ┌───────────────┐                    │
           │  │ Celery Flower │  │ Celery beat   │（reaper 任务）    │
           │  │ (内网+basic)  │  └───────────────┘                    │
           │  └──────────────┘                                       │
           └────────────────────────────────────────────────────────┘
                                     │ 仅 FastAPI + worker 可达
           ┌─────────────────────────▼───────────────────────────────┐
           │                  memory 网络（内网，与 platform 隔离）    │
           │  ┌──────────────────┐  ┌────────────┐  ┌─────────────┐ │
           │  │ Java 记忆系统     │  │ MySQL      │  │ Qdrant      │ │
           │  │ MemoryEvalController│ │ 专用 eval DB│ │ 专用 collection│ │
           │  │ /api/v1/eval/** │  │ (存储隔离A) │  │ (存储隔离A)  │ │
           │  └──────────────────┘  └────────────┘  └─────────────┘ │
           └────────────────────────────────────────────────────────┘
```

### 3.2 数据流（一次评测 run 的完整生命周期）

```
用户(前端) ──▶ FastAPI POST /runs {dataset_version, param_snapshot_id, mode}
                │ 1. 鉴权 + RBAC
                │ 2. Idempotency-Key 命中 → 比对 config_fingerprint：一致返回；不一致 409
                │ 3. 派生 eval_user_id = BASE + hash(config_fingerprint)（per-config 隔离）
                │ 4. 写入 eval_runs(status='pending', config_fingerprint, eval_user_id)
                │ 5. 投递 Celery run_eval_task(run_id)
                ▼
Celery worker 领取 run_eval_task(run_id) —— 状态分支 + CAS 抢占 + 心跳 + per-stage lease check
  │  pending → CAS 抢占
  │  running → 续跑前 fencing 检查（lease CAS）
  │  破坏性阶段(reset/seed)前校验「lease_owner 仍是我 + 心跳新鲜」（check-then-act，残余风险见 §8.1）
  │  reset(物理删 MySQL + removeByUserId 向量库，仅自身 eval_user_id) ──▶ seed(幂等+catch DupKey)
  │    ──▶ 向量就绪屏障(hnsw) ──▶ 五维 ──▶ finalize(CAS succeeded → 清理自身命名空间)
  ▼
前端轮询 GET /runs/{id}

（后台）Celery beat reaper：stale heartbeat → revoke(terminate) + CAS failed
```

### 3.3 异步任务编排 —— 四件语义分离 + per-config 隔离 + 单飞（全 CAS pin）

**四件语义分离**：

| 语义 | 载体 | 机制 |
|---|---|---|
| run 身份 | `eval_runs.id`（UUID） | 一个 run = 一次执行 |
| 配置指纹 | `config_fingerprint`（非唯一） | `md5(join('|',[params_hash, config_hash, content_digest, mode]))` |
| 双击幂等 | `Idempotency-Key` → `idempotency_key` 唯一 | 命中比对 fingerprint，一致返回，不一致 409 |
| 并发护栏（同 config） | 部分唯一索引 | `WHERE status IN ('pending','running')` |

**per-config 隔离（跨 config 并发安全 + 语料不残留）**：`eval_user_id` 由 `config_fingerprint` 派生（§零 D-1）；finalize 后清理自身命名空间。同 config 串行（`uq_runs_active_cfg`）→ 语料不累积；不同 config 各自命名空间 → 并行 A/B 安全。

**CAS 谓词全 pin**：

| 操作 | CAS 谓词（pin 死） | 作用 |
|---|---|---|
| 抢占 pending→running | `UPDATE ... SET status='running', lease_owner=:me, heartbeat_at=now(), started_at=now() WHERE id=:id AND status='pending'` | 唯一 worker 抢 pending |
| lease 接管（crash-resume） | `UPDATE ... SET lease_owner=:me, heartbeat_at=now() WHERE id=:id AND status='running' AND lease_owner=:observed_owner AND heartbeat_at=:observed_heartbeat` | 两 worker 同抢 stale 时，只有 observed 匹配者成功 |
| per-stage lease check | 破坏性阶段前 `SELECT lease_owner, heartbeat_at WHERE id=:id`；`lease_owner != :me` 或 stale → 中止 | 挡 zombie（**check-then-act，非原子，残余风险见 §8.1**） |
| finalize | `UPDATE ... SET status='succeeded', finished_at=now() WHERE id=:id AND status='running' AND lease_owner=:me` | 不复活判死 run；随后清理自身命名空间 |
| reaper 判死 | `UPDATE ... SET status='failed', error_message='reaped', finished_at=now() WHERE id=:id AND status='running' AND heartbeat_at=:observed_stale_heartbeat` | 比对 stale 心跳，避免 TOCTOU 误杀 |

**阶段幂等**：

| 阶段 | 幂等保证 |
|---|---|
| reset | MySQL `DELETE FROM` + 向量库 `removeByUserId`（仅自身 eval_user_id） |
| seed | check-then-return + `catch DuplicateKeyException → existed` |
| 指标 | 确定性 + 结果 upsert（两表 UNIQUE + ON CONFLICT） |
| Recall | contentHash 直接匹配，无 createdId |

**状态机与重试**：

| 关注点 | 设计 |
|---|---|
| 重试 vs 续跑 | transient 重试与崩溃续跑统一；确定性失败 `FAILED` 不重试 |
| 卡死恢复 | reaper heartbeat-based + `revoke(terminate=True)` + CAS |
| 取消 | `CANCELLED` + 阶段间检查 + `revoke` |
| 不丢 | `task_acks_late=True` + `task_reject_on_worker_lost=True` |
| 不重 | `idempotency_key` 唯一 + body 一致性 |
| 不并发 | per-config 隔离（跨 config）+ 部分唯一索引 + CAS + fencing（同 run） |

### 3.4 检索评测：contentHash 直接匹配 + exact 模式算法澄清

- `MemoryRetriever.MemoryItem`（`:517`）已带 `content`；`MemoryItemDTO.content`（`:16`）已在 DTO。
- Recall 直接 `md5(retrieved.content) ∈ ground_truth.relevant_content_hashes`，无 createdId。
- `/eval/seed` 返回 `{"inserted","existed"}`；`/eval/search` 必须含 `content`。
- **contentHash 契约**：`content_hash = lowercase hex MD5(UTF-8 bytes of exact content)`，无规范化；语料 ≤ 512 字符；`ground_truth` 由 seed payload 同一字符串派生；与 `MemoryRecord.content_hash` 同一函数。
- **exact 模式算法澄清（Minor #5）**：exact = 冻结 6 要素 + `exact:true` **向量检索**（Qdrant 全扫，非 HNSW 近似），**BM25 FULLTEXT 仍是混合检索常驻一路**（不是 contentHash 全扫）。故确定性维度（AC#12）的稳定性依赖 §零 D-1 的 per-config 命名空间（同 config 串行 + 语料不残留 → IDF 稳定）。
- **向量就绪屏障**：seed 后 hnsw 模式 poll `vectorSyncPendingCount==0`（或校验全部 seeded item 已 synced）；exact 模式全扫不依赖向量，跳过。

---

## 四、技术选型（生产级，观测降档）

| 层 | 选择 | 理由 |
|---|---|---|
| API 框架 | FastAPI | 异步、OpenAPI、pydantic |
| 异步任务 | Celery + Redis broker + beat | 成熟队列 + beat 供 reaper |
| 数据存储 | PostgreSQL 16 + SQLAlchemy 2 + Alembic | 长期积累、并发、聚合、ACID + WAL |
| 缓存/broker | Redis 7 | broker + result_backend |
| HTTP 客户端 | httpx | 异步 + 连接池 + 超时/重试 |
| 指标计算 | 自研 + numpy + scipy | 五维 + 方差/NDCG |
| 前端 | React + Vite + ECharts | 雷达/趋势/A-B/方差 |
| 可观测性 | Flower（内网+basic）+ 结构化 JSON 日志先行 | 内部工具够用 |
| 鉴权 | JWT | 两档 RBAC |
| 配置 | pydantic-settings + env + docker secrets | 配置外部化 |
| 编排 | Docker Compose（单机起步） | 多服务 |
| 测试 | pytest + pytest-asyncio + respx | 契约/单测/异步 |

> **`canonical_json` 定义**：`json.dumps(obj, sort_keys=True, separators=(',', ':'), ensure_ascii=False)`，防跨环境浮点/非 ASCII 漂移。所有 `params_hash`/`config_hash`/`content_digest` 基于此。

---

## 五、数据模型设计（核心表 DDL 级）

> Alembic 迁移。状态 `TEXT + CHECK`。JSON `jsonb`，时间 `timestamptz`。

### 5.1 用户与权限（两档 RBAC）

```sql
CREATE TABLE eval_users (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'viewer' CHECK (role IN ('admin','viewer')),
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 5.2 评测集版本化 + 用例稳定自然键 + 内容摘要（含 ground_truth）

```sql
CREATE TABLE eval_datasets (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    description TEXT,
    created_by  BIGINT REFERENCES eval_users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    is_archived BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE eval_dataset_versions (
    id                BIGSERIAL PRIMARY KEY,
    dataset_id        BIGINT NOT NULL REFERENCES eval_datasets(id) ON DELETE CASCADE,
    version           TEXT NOT NULL,
    schema_version    INTEGER NOT NULL,
    source            TEXT NOT NULL,
    parent_version_id BIGINT REFERENCES eval_dataset_versions(id),
    content_digest    TEXT NOT NULL,               -- 见下方 pin（含 content_hash + ground_truth）
    config            JSONB NOT NULL DEFAULT '{}',
    created_by        BIGINT REFERENCES eval_users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dataset_id, version)
);

CREATE TABLE eval_cases (
    id                 BIGSERIAL PRIMARY KEY,
    dataset_version_id BIGINT NOT NULL REFERENCES eval_dataset_versions(id) ON DELETE CASCADE,
    case_type          TEXT NOT NULL,
    group_key          TEXT NOT NULL,
    content_hash       TEXT NOT NULL,              -- lowercase hex MD5(exact content)
    payload            JSONB NOT NULL,
    ground_truth       JSONB NOT NULL,             -- {"relevant_content_hashes":[...]}
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (dataset_version_id, content_hash)
);
```

> **`content_digest` pin（v12 修正，纳入 ground_truth）**：
> `content_digest = sha256( canonical_json( [ (content_hash_i, ground_truth_i) for 每个 case, 按 content_hash 升序排序 ] ) )`
> 其中 `ground_truth_i` 自身已是 `canonical_json`（sorted keys）。**ground_truth 纠错 → content_digest 变 → fingerprint 变**，反哺闭环对指纹可见，双击幂等不再错误返回旧 run、回归 diff 可归因到 ground_truth 变更。

### 5.3 参数快照 + 模型版本（内容哈希去重）

```sql
CREATE TABLE eval_param_snapshots (
    id            BIGSERIAL PRIMARY KEY,
    name          TEXT NOT NULL,
    params        JSONB NOT NULL,          -- 含 hnsw_ef/topK/threshold/... 全部可调参数
    freeze_config JSONB NOT NULL,
    params_hash   TEXT NOT NULL UNIQUE,    -- sha256(canonical_json(params ∪ freeze_config))
    created_by    BIGINT REFERENCES eval_users(id),
    description   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE eval_model_versions (
    id                 BIGSERIAL PRIMARY KEY,
    embedding_model_id TEXT NOT NULL,
    reranker_model_id  TEXT NOT NULL,
    config_hash        TEXT NOT NULL UNIQUE,   -- sha256(canonical_json(config))
    config             JSONB NOT NULL
);
```

### 5.4 评测 run（状态机 + per-config 隔离字段 + 单飞字段 + checkpoint）

```sql
CREATE TABLE eval_experiments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        TEXT NOT NULL,
    description TEXT,
    created_by  BIGINT REFERENCES eval_users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE eval_runs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_fingerprint TEXT NOT NULL,      -- md5(join('|',[params_hash, config_hash, content_digest, mode]))
    idempotency_key    TEXT,
    experiment_id      UUID REFERENCES eval_experiments(id),
    dataset_version_id BIGINT NOT NULL REFERENCES eval_dataset_versions(id),
    param_snapshot_id  BIGINT NOT NULL REFERENCES eval_param_snapshots(id),
    model_version_id   BIGINT NOT NULL REFERENCES eval_model_versions(id),
    eval_user_id       BIGINT NOT NULL,    -- per-config 派生（BASE + hash(config_fingerprint[:8]) mod RANGE）
    mode               TEXT NOT NULL DEFAULT 'exact' CHECK (mode IN ('exact','hnsw')),
    status             TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','running','succeeded','failed','cancelled')),
    lease_owner        TEXT,
    heartbeat_at       TIMESTAMPTZ,
    current_stage      TEXT,
    progress           NUMERIC(5,2) NOT NULL DEFAULT 0,
    checkpoint         JSONB NOT NULL DEFAULT '{"completed_stages":[]}',
    result_summary     JSONB,
    error_message      TEXT,
    retry_count        INTEGER NOT NULL DEFAULT 0,
    started_at         TIMESTAMPTZ,
    finished_at        TIMESTAMPTZ,
    created_by         BIGINT REFERENCES eval_users(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_runs_status    ON eval_runs (status);
CREATE INDEX idx_runs_cfg       ON eval_runs (config_fingerprint);
CREATE INDEX idx_runs_created   ON eval_runs (created_at DESC);
CREATE INDEX idx_runs_heartbeat ON eval_runs (heartbeat_at) WHERE status = 'running';
CREATE INDEX idx_runs_pending   ON eval_runs (created_at) WHERE status = 'pending';
CREATE UNIQUE INDEX uq_runs_idempotency ON eval_runs (idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uq_runs_active_cfg ON eval_runs (config_fingerprint) WHERE status IN ('pending','running');
```

> **per-config 隔离**：`eval_user_id` 由 `config_fingerprint` 派生，同 config 共享命名空间（串行 + reset 自清），不同 config 各自命名空间（并行 A/B）。
> **corpus 串行 fallback**（若 Java 硬约束单 user）：`uq_runs_active_corpus ON eval_runs ((1)) WHERE status IN ('pending','running')` + `--concurrency=1`。

### 5.5 结果（均 upsert 幂等，RESTRICT 保审计）

```sql
CREATE TABLE eval_run_results (
    id           BIGSERIAL PRIMARY KEY,
    run_id       UUID NOT NULL REFERENCES eval_runs(id) ON DELETE RESTRICT,
    dimension    TEXT NOT NULL,
    metric_name  TEXT NOT NULL,
    metric_value NUMERIC NOT NULL,
    variance     NUMERIC,
    confidence   NUMERIC,
    detail       JSONB NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, dimension, metric_name)
);

CREATE TABLE eval_case_results (
    id            BIGSERIAL PRIMARY KEY,
    run_id        UUID NOT NULL REFERENCES eval_runs(id) ON DELETE RESTRICT,
    case_id       BIGINT NOT NULL REFERENCES eval_cases(id),
    dimension     TEXT NOT NULL,
    metric_values JSONB NOT NULL,
    detail        JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, case_id, dimension)
);
CREATE INDEX idx_cr_run ON eval_case_results (run_id);
```

### 5.6 反哺闭环（状态机收敛）

```sql
CREATE TABLE eval_feedback_samples (
    id                          BIGSERIAL PRIMARY KEY,
    source                      TEXT NOT NULL,
    source_ref                  TEXT,
    case_payload                JSONB NOT NULL,
    ground_truth                JSONB NOT NULL,
    status                      TEXT NOT NULL DEFAULT 'new' CHECK (status IN ('new','reviewing','approved','rejected','archived')),
    reviewed_by                 BIGINT REFERENCES eval_users(id),
    review_comment              TEXT,
    archived_dataset_version_id BIGINT REFERENCES eval_dataset_versions(id),
    created_by                  BIGINT REFERENCES eval_users(id),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at                 TIMESTAMPTZ
);
```

**状态流转**：`new → reviewing → approved → archived`；`new → rejected`；`reviewing → new`（打回）；`reviewing → rejected`。**归档生成新 dataset_version 时重算 `content_digest`（含新 ground_truth）**。

### 5.7 LLM-judge 任务（后置）

```sql
CREATE TABLE eval_judge_jobs (
    id            BIGSERIAL PRIMARY KEY,
    run_id        UUID REFERENCES eval_runs(id) ON DELETE RESTRICT,
    pair_key      TEXT NOT NULL,
    prompt_type   TEXT NOT NULL,
    judge_model   TEXT NOT NULL,
    input_a       JSONB NOT NULL,
    input_b       JSONB NOT NULL,
    position_swap BOOLEAN NOT NULL DEFAULT FALSE,
    verdict       TEXT,
    rationale     TEXT,
    confidence    NUMERIC,
    status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','running','succeeded','failed')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
```

### 5.8 审计（append-only）

```sql
CREATE TABLE eval_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT REFERENCES eval_users(id),
    action        TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id   TEXT NOT NULL,
    before        JSONB,
    after         JSONB,
    ip            TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## 六、Java 侧改造（沿用 v6 + 生产级微调 + 承重改动显式 + 存储隔离接入点）

### 6.1 eval 端点最小权限角色
- `ROLE_EVAL` + `/api/v1/eval/**` `@PreAuthorize("hasRole('EVAL')")`。

### 6.2 seed 幂等（承重）
- `selectByUserIdAndHash(userId, contentHash)` + `addDirect void→Long` + `catch DuplicateKeyException → existed`。

### 6.3 reset 清向量库（承重）
- `IMemoryVectorStore.removeByUserId(userId)`：Qdrant `/points/delete` filter `{user_id}` **synchronous delete-await**；Simple 剥离匹配 userId 的 VectorEntry。
- reset = MySQL `DELETE FROM` + `memory_history` 孤儿清理 + `removeByUserId`。
- **warm-up 澄清**：seed 的 `upsert` 自然回填向量，无需额外 re-sync。

### 6.4 IDOR 防护 + per-config 派生 userId
- eval 端点不接受客户端传 userId，Python 服务端按 `config_fingerprint` 派生传入。
- Java 校验 `EVAL_USER_ID_BASE ≤ eval_user_id < BASE + RANGE`，越界 403。
- `MemoryManager.addDirect(Long userId,...)` / `delete(userId, memoryId)` 已支持 userId 列。

### 6.5 `hnsw_ef` 暴露 + `/eval/search` 返回 content
- `IMemoryVectorStore.search` 重载补 `hnsw_ef`；`/eval/search` 必须含 `content`。

### 6.6 eval 端点网络暴露面
- `/api/v1/eval/**` 仅内网。

### 6.7 存储隔离 A 的 Java 落地机制（Minor #6，新增）

- **现状**：`IMemoryRecordDao`（MySQL）与 `IMemoryVectorStore`（Qdrant）各只接一个 datasource/collection，Phase 0 会撞墙。
- **三种接入点**：
  1. **独立 Java 实例（推荐）**：起一个专用 eval Spring Boot 实例，`memory.eval.enabled=true` + 指向专用 eval DB/collection 的配置。隔离最彻底、无侵入，成本是额外一个 JVM。
  2. **双 datasource/collection**：同一实例内按 `memory.eval.enabled` 路由到专用 DB/collection（`@Primary` 业务源 + eval 源），省 JVM 但需改 DAO/VectorStore 的数据源注入。
  3. **tenant 路由**：单源内按 userId 前缀路由，实现复杂、隔离弱，不推荐。
- **Decision**：Phase 0 spike 在「独立实例」与「双 datasource」间定夺（倾向独立实例，简单彻底）；两者都要求 eval profile 下 cron 禁用 + 冻结态 + exact 贯穿。

### 6.8 退役脚本 + exact 模式澄清

- **Minor #4**：`11-eval-insert-corpus.sql` 用 `MD5(CONCAT(9999,content))`，与契约 `MD5(content)` 不一致。**Phase 0 退役该脚本**，seed 端点为唯一灌入通道；Phase 2 迁移 V1 时按 `MD5(content)` 重算 content_hash/ground_truth。
- **Minor #5**：exact = 冻结 6 要素 + `exact:true` 向量检索，BM25 FULLTEXT 仍常驻一路（非 contentHash 全扫）；IDF 稳定性依赖 per-config 命名空间 + 语料不残留。

---

## 七、Python 平台五模块设计（生产级）

### 7.1 连接层（connector）
`java_client.py`：登录 + 全部 eval 端点 + 连接池 + 超时/重试 + 熔断/限流 + trace-id。

### 7.2 测试集管理（datasets）
CRUD + 版本化 + 三源导入 + 校验 + RBAC + 批量回滚 + 版本 diff（`content_hash` join）+ `content_digest`（含 ground_truth）计算。

### 7.3 评测引擎（engine）
五维指标 + contentHash 匹配 + 异步编排（状态分支 + CAS + per-config 派生 + per-stage lease check + reaper + finalize CAS + **finalize 后清理自身命名空间**）+ freeze check + embedding 自检 + 向量就绪屏障（hnsw）。

### 7.4 参数管理（params）
真源 + 快照去重（`params_hash`，含 hnsw_ef）+ 参数漂移告警。

### 7.5 结果可视化 + 反哺闭环
results（雷达/趋势/A-B/分组/方差/口径）+ feedback（状态机 + 归档重算 content_digest + 回归 diff gate）。

### 7.6 LLM-judge 服务（后置）
schema 就位；位置偏置消解 + 模型适配器。

---

## 八、可靠性 / 容错 / 监控

### 8.1 可靠性

| 关注点 | 设计 |
|---|---|
| 崩溃恢复 | 状态分支续跑 + 阶段幂等 |
| double-live fencing | 入口 lease CAS + per-stage lease check |
| **fencing 残余风险（诚实化）** | per-stage check 是 check-then-act（非原子），TOCTOU 窗口：zombie 通过 check → 停顿超阈值 → B 接管 reset+seed → A 恢复 reset 删 B 语料。**缓解降级为「部分缓解 + 检测（空结果/异常告警）+ 重跑」，非原子兜底**。影响 = 单 run 空/错误 Recall，可检测可重跑，无数据丢失。更强方案（fencing token，Java 侧拒绝 token 不匹配的破坏性调用）列为 Follow-up |
| 卡死恢复 | reaper heartbeat-based：stale `heartbeat_at` → `revoke(terminate=True)` + CAS failed；stale `pending` → failed；admin force-fail |
| reaper 局限 | `revoke(terminate=True)` 跨容器/host 不可靠 → per-stage check + 检测告警兜底 |
| finalize 防护 | CAS `WHERE status='running' AND lease_owner=:me` + **清理自身命名空间** |
| 任务不丢 | `task_acks_late=True` + `task_reject_on_worker_lost=True` |
| 双击不重 | `idempotency_key` 唯一 + body 一致性 |
| 并发正确性 | per-config 隔离（跨 config）+ 部分唯一索引 + CAS + fencing（同 run） |
| 幂等 | reset（MySQL+向量）、seed（catch DupKey）、Recall contentHash、结果 upsert |
| 重试 vs 续跑 | transient 重试、确定性失败不重试 |
| 取消 | `CANCELLED` + 阶段间检查 + `revoke` |
| 熔断/限流 | connector 对 Java |
| 配置外部化 | pydantic-settings + env + secrets |
| schema 版本化 | Alembic |
| 数据备份 | Postgres WAL + `pg_dump` |

### 8.2 可观测性（降档）

| 层级 | 手段 | 阶段 |
|---|---|---|
| 任务观测 | Flower（内网 + basic-auth） | 先行 |
| 日志 | 结构化 JSON + run_id/case_id 关联 | 先行 |
| 指标面板 | Prometheus + Grafana | 后置 |

---

## 九、安全与权限

| 面 | 威胁 | 措施 |
|---|---|---|
| Java eval 端点 | 越权/滥用 | `ROLE_EVAL` + 内网 + eval 命名空间范围校验（IDOR 防护） |
| Python 平台 | 未授权访问 | JWT + bcrypt |
| RBAC | 越权操作 | 两档 admin/viewer |
| 审计 | 责任追溯 | `eval_audit_log` |
| 网络 | 横向渗透 | platform/memory 双网络隔离；纯内网 |
| Flower | 未授权访问 | 内网 + basic-auth |
| 凭据 | 泄露 | docker secrets / env |
| 注入 | SQL/命令注入 | SQLAlchemy 参数化 + pydantic |

---

## 十、Acceptance Criteria（可测试）

1. **连接**：登录 Java（`ROLE_EVAL`）调 `/eval/search`，返回含 `content`。
2. **seed 幂等**：返回 `{"inserted","existed"}`；重复 seed 不抛 `DuplicateKeyException`。
3. **reset 彻底清空**：`/eval/reset` 后 MySQL 无残留 + 向量库无 stale；连续 reset no-op；孤儿处理。
4. **per-config 隔离**：同 config 共享命名空间（串行 + reset 自清）；不同 config 并发互不干扰；并行 A/B 结果正确。
5. **语料不残留**：run finalize 后自身命名空间语料被清理（IDF 不随历史累积）。
6. **异步 run 状态机**：202 + run_id；`PENDING→RUNNING→SUCCEEDED`；长 run 不阻塞。
7. **双击幂等 + body 一致性**：同 key 同 config 返回同一 run；同 key 不同 config 409。
8. **崩溃续跑 + lease CAS**：(a) kill 后重启续跑成功；(b) 双 worker 同抢 stale 只一个接管；(c) zombie 被 per-stage check 中止（**残余 TOCTOU 窗口已接受**：zombie 在 seed 后仍能删语料的边界=单 run 空/错误 Recall，可检测可重跑）。
9. **卡死恢复**：stale heartbeat → reaper CAS failed；stale pending → failed；force-fail 支持。
10. **finalize CAS + 清理**：reaper 判死/lease 接管后原 worker 不复活/不覆盖；finalize 后清理自身命名空间。
11. **重试语义**：transient 重试成功；确定性失败不重试。
12. **五维指标**：Recall@5/MRR/NDCG@5/Hit@1 + 抽取 Precision/Recall/方差 + 治理误合并/漏隔离率。
13. **可复现（跨周期）**：同 `config_fingerprint`（exact）N≥3（强制串行），确定性维度 max abs diff < ε=0.01；contentHash 跨 reset 不漂移；无 stale 向量；**语料不残留 → IDF 稳定**。
14. **指纹可移植 + ground_truth 可见**：staging/prod 同 fingerprint；**ground_truth 纠错 → content_digest → fingerprint 变**（反哺闭环归因可见）。
15. **向量就绪屏障**：hnsw seed 后 synced 才 search；exact 跳过。
16. **RBAC**：viewer 无法建 run/改数据集。
17. **IDOR 防护**：eval_user_id 越出命名空间被拒（403）。
18. **审计**：mutation 落 `eval_audit_log`。
19. **反哺闭环**：样本流转 + 归档重算 content_digest + 回归 diff + 超阈值告警。
20. **可观测性**：Flower（basic-auth）+ run_id 日志。
21. **边界**：Java 旧路径回归全绿；`exact` 贯穿 4 调用点非 eval 三处缺省 false。

---

## 十一、Implementation Steps（分阶段）

### Phase 0 — 基础设施与 Java 侧
1. Docker Compose：platform + memory 网络，纯内网。
2. Java 侧沿用 v6 全部 10 项改动。
3. **承重改动**：`selectByUserIdAndHash` + `addDirect void→Long` + catch DupKey；`deleteByUserId = DELETE FROM` + 孤儿；`IMemoryVectorStore.removeByUserId`（synchronous delete-await + Simple 剥离）；`/eval/search` 透传 content；eval_user_id 命名空间校验。
4. **存储隔离 A 接入点**：Phase 0 spike 在「独立 Java 实例 vs 双 datasource/collection」定夺（倾向独立实例）。
5. **退役 `11-eval-insert-corpus.sql`**（`MD5(CONCAT(9999,content))` 与契约不一致）。
6. `ROLE_EVAL` + `hnsw_ef` + 存储隔离 A 环境变量化。
7. 单测：seed 幂等 + reset 物理删含向量库 + 治理禁用 + replay 只读 + IDOR + 范围校验。

### Phase 1 — Python 骨架 + 连接层 + 数据模型
8. `eval-platform/` + Alembic 迁移（§5 DDL，含 `eval_user_id`/`lease_owner`/`heartbeat_at`/`content_digest`/`config_fingerprint`）。
9. `auth/` + `audit` + `connector` + 契约测试 + smoke。

### Phase 2 — 测试集管理 + 异步编排器
10. `datasets/` + `content_hash`/`content_digest`（含 ground_truth）计算。
11. `tasks/`：状态分支 + CAS + per-config 派生 + per-stage lease check + reaper + finalize CAS（含清理命名空间）+ seed 异常吸收 + 向量就绪屏障。
12. 迁移 50+20 V1（**重算 hash 为 `MD5(content)`**，ground_truth 内容键）。

### Phase 3 — 评测引擎 + 指标
13. `engine/metrics.py`：五维 + contentHash 匹配。
14. run 编排接入五维 + 口径标注。

### Phase 4 — 参数管理 + 结果可视化 + 观测
15. `params/` + `results/` + `web/` + `observability/`。

### Phase 5 — 反哺闭环 + 安全收口
16. `feedback/`（归档重算 content_digest）+ 网络隔离 + IDOR/越权 + 审计完整性。

### Phase 6 — 联调验收 + 演进验证
17. 跑 §10 全 AC（重点 AC#5 语料不残留 + AC#8 zombie 边界 + AC#13 可复现 + AC#14 ground_truth 可见）。
18. 部署文档 + 演进路径。

---

## 十二、Risks and Mitigations

| 风险 | 影响 | 缓解 |
|---|---|---|
| **历史语料累积 → FULLTEXT IDF 漂移** | AC#13 击穿 | **per-config 派生命名空间 + finalize 清理** |
| **FULLTEXT IDF 并行 A/B 对称混合** | exact 绝对指标微偏 | 同 config 串行 + 并行 A/B 对称口径标注 |
| **fencing TOCTOU（check-then-act）** | zombie 删新语料 | **诚实降级：部分缓解 + 检测（空结果告警）+ 重跑**；fencing token 列 Follow-up |
| **ground_truth 对指纹不可见** | 反哺闭环归因失效 | content_digest 纳入 ground_truth |
| stale 向量挤占检索 | Recall 截半 | reset removeByUserId（synchronous） |
| lease 双接管（B/C 同抢） | double-live | lease CAS 比对 observed_owner + observed_heartbeat |
| reaper TOCTOU 误杀活 worker | clobber 活语料 | reaper CAS 比对 stale heartbeat |
| 逻辑被复制 | 评测失真 | CI grep 卡点 |
| replay 落库污染 | 语料漂移 | compute/apply 分层 |
| 共享存储全局态 | 跨周期不可达 | 存储隔离 A（独立实例/双 datasource） |
| embedding 浮点漂移 | AC#13 误判 | embedding 稳定性自检 |
| 有状态漂移 | 间歇不可达 | 冻结 6 要素 + freeze check |
| claim 与续跑互斥 | run 卡死 | 状态分支续跑 + CAS |
| 自增 id 漂移 | Recall 算错 | contentHash 直接匹配 |
| seed TOCTOU race | 重复 insert | catch DupKey |
| finalize 复活判死 run | 审计污染 | finalize CAS |
| 卡死 running/pending 锁槽位 | 后续 run 409 | reaper 扫 stale running + pending |
| 跨环境指纹不一致 | 结果不可对比 | 内容可寻址 + canonical_json pin |
| 向量未就绪（hnsw） | 空/不全结果 | 向量就绪屏障 |
| 多用户越权/IDOR | 数据泄露 | RBAC + 命名空间校验 + audit + 内网 |
| 反哺引入回归 | 指标恶化 | 回归 diff gate（content_hash join） |
| exact vs HNSW 口径差异 | 调参反哺削弱 | 双模式 + experiment 分组 |
| 参数动态化破坏旧行为 | 线上回归 | 默认值=旧值 + 重载 |
| 跨语言 contract 漂移 | DTO 解析失败 | 契约测试 + schema 文档 |
| 真实 Qdrant/embedding 依赖 | Connection refused | docker-compose + Simple store 兜底 |

---

## 十三、Verification Steps

1. **单元**：metrics、datasets、params、feedback、幂等 upsert、content_digest（含 ground_truth）。
2. **契约**：respx mock Java + seed 幂等 + IDOR + eval_user_id 范围校验。
3. **Java 回归**：`mvn test` 全绿 + removeByUserId + 存储隔离接入点。
4. **冻结态**：search 无副作用、recency 固定、reranker 旁路、expire pin、exact:true。
5. **reset 清向量库**：reset 后 `SimpleMemoryVectorStore.size()`（自身 eval_user_id）= 0；Qdrant filter 无 stale。
6. **per-config 隔离 + 语料不残留**：两 config 并发互不干扰；finalize 后命名空间清空；同 config 串行自清。
7. **异步/幂等/崩溃/lease CAS/zombie**：双击 + body；kill 续跑；双 worker lease CAS；zombie 中止（TOCTOU 边界已接受）。
8. **卡死恢复**：reaper CAS failed；finalize 不复活。
9. **可复现**：同 fingerprint N≥3，max abs diff < 0.01；IDF 不随历史漂移。
10. **指纹可移植 + ground_truth 可见**：staging/prod 同 fingerprint；ground_truth 纠错 → fingerprint 变。
11. **向量就绪屏障**：hnsw synced 才 search。
12. **RBAC/审计/IDOR**。
13. **反哺闭环**。
14. **可观测性**：Flower + run_id 日志。
15. **端到端**：docker compose up，跑 §10 全 AC。

---

## 十四、演进路线（单机 → 多机、SQLite → Postgres）

### 14.1 部署演进

| 阶段 | 形态 | 说明 |
|---|---|---|
| 单机（默认） | 单 compose：1 API + 1 worker + 本地 Postgres/Redis + Flower + beat | volume 持久化 |
| 多 worker（并发） | 同一 broker + Postgres，起 N worker | **正确性来自 per-config 隔离 + 部分唯一索引 + CAS + fencing，非单 worker** |
| 托管依赖 | Postgres/Redis 迁云托管 | 高可用 + 备份 |
| K8s | compose 迁 K8s | 自动扩缩容 |

### 14.2 SQLite → Postgres 迁移（仅当有原型数据）

1. Alembic 建 schema。2. 一次性导入。3. 校验。4. 冻结 SQLite 上线。

---

## 十五、RALPLAN-DR 摘要

### Principles（5 条）

1. **部署独立、逻辑不独立**。
2. **可复现优先**：存储隔离 A + 冻结 6 要素 + exact gate + contentHash + reset 清向量库 + **语料不残留**。
3. **真实性与可复现分工**：双模式检索。
4. **数据即资产**：版本化 + 审计 + 内容可寻址指纹（**含 ground_truth**）。
5. **共享态粒度正确**：语料 **per-config 隔离**（同 config 串行自清 + finalize 清理，跨 config 并行安全）+ 同 run 单飞（CAS/fencing，残余风险诚实化）+ 四件语义分离。

### Decision Drivers（top 3）

1. **长期积累与团队协作** → Postgres + RBAC + 版本化 + ground_truth 可见指纹。
2. **可靠性与重负载 + 并行 A/B** → 异步 + per-config 隔离 + CAS/fencing + reaper + 语料不残留。
3. **可复现** → 存储隔离 A + 冻结 6 要素 + exact + contentHash + reset 清向量库 + IDF 稳定。

### Viable Options（>=2）

| 选项 | 描述 | 利 | 弊 | 判定 |
|---|---|---|---|---|
| **O1（选定）** | per-config 派生 + finalize 清理 + 异步 Celery + Postgres + 两档 RBAC + 内容可寻址（含 ground_truth）+ 单飞/fencing + reaper | 语料不残留、IDF 稳定、并行 A/B、反哺闭环可见 | 保留命名空间 + 派生略增复杂度 | ✅ 选定 |
| O2 | corpus 级串行（全局单飞 + `--concurrency=1`） | 最简；IDF 天然稳定 | 无并行 A/B | ⚠️ fallback |
| O3 | 固定 EVAL_USER_ID | 简单 | 跨 config reset 互删 + 历史累积 | ❌ |
| O4 | 同步 FastAPI + SQLite | 快 | 超时/无并发/无 RBAC 审计观测 | ❌ |

**隔离子选项**：per-config 派生（选，语料不残留 + 并行 A/B）vs run_id 派生（v11，历史累积）→ 选 per-config；corpus 串行 fallback。
**fencing 子选项**：per-stage check（选，check-then-act 残余风险诚实化）vs fencing token（Java 拒绝 token 不匹配，更强）→ 选 check-then-act + 检测重跑，fencing token 列 Follow-up。

---

## 十六、Open Questions

- [ ] ε 取值（默认 0.01）。
- [ ] 前端轮询 vs WebSocket。
- [ ] `result_backend` Postgres vs Redis（Phase 2 前定夺，默认 Postgres）。
- [ ] `HEARTBEAT_INTERVAL` / `HEARTBEAT_STALE_THRESHOLD` / `EVAL_USER_ID_RANGE` 取值（实测校准）。
- [ ] **Java 侧存储隔离接入点**：独立实例 vs 双 datasource（Phase 0 spike 定，倾向独立实例）。
- [ ] **fencing token（更强 fencing）** 是否 Follow-up 落地（当前 per-stage check + 检测重跑够用）。
- [ ] LLM-judge JEV 接入（后置）。
- [ ] 反哺回归 diff 超阈值。

---

## 附：v11 → v12 差异对照（本版修订）

| 维度 | v11 | v12 |
|---|---|---|
| eval_user_id 派生 | 由 run_id | **由 config_fingerprint**（同 config 共享命名空间） |
| 语料累积 | 历史 run 永久残留 | **finalize 后清理自身命名空间，IDF 长期稳定** |
| content_digest | 只哈希 content_hash | **纳入 ground_truth（反哺闭环对指纹可见）** |
| fencing 残余风险 | 「per-stage check 兜底」 | **诚实降级：check-then-act 部分缓解 + 检测告警 + 重跑** |
| exact 模式算法 | 未澄清 BM25 | **澄清：BM25 常驻一路，非 contentHash 全扫** |
| 存储隔离接入点 | 未提 | **独立实例 vs 双 datasource（Phase 0 spike 定）** |
| 退役脚本 | 未提 | **`11-eval-insert-corpus.sql` MD5 不一致，退役重算** |
| ground_truth 归因 | 不可见 | **content_digest → fingerprint 随 ground_truth 变** |
