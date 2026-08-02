# 心跳机制与任务恢复（面试准备）

## 一、要解决的问题

AI 写作任务执行 30s~2min，如果执行到一半 Consumer 进程崩溃（OOM、Pod 被杀、网络断开），任务会**永远卡在 RUNNING 状态**，无人处理。

需要一种机制让系统**自动发现"死掉的任务"并重新执行**。

---

## 二、心跳机制设计

### 原理

Consumer 执行 Agent 任务期间，每 5 秒往 DB 更新一次心跳时间戳：

```java
// executeTask 中
final long heartbeatIntervalMs = 5_000L;
final long[] lastHeartbeat = {System.currentTimeMillis()};

agentWritingRunner.run(task, event -> {
    long now = System.currentTimeMillis();
    if (now - lastHeartbeat[0] >= heartbeatIntervalMs) {
        aiTaskRepository.touchHeartbeat(taskId);  // UPDATE SET heartbeat_at = NOW()
        lastHeartbeat[0] = now;
    }
    taskEventPublisher.publish(taskId, event);
});
```

### 为什么要节流（5 秒一次）？

Agent 执行过程中，每产出一个 token 都会触发回调。一篇文章可能产出几千个 token：

| 方案 | DB 写频率 | 效果 |
|------|----------|------|
| 每个 token 都写心跳 | 几千次/分钟 | ❌ 打爆数据库 |
| 每 5 秒写一次 | 最多 12 次/分钟 | ✅ DB 无压力 |

### 对应 SQL

```sql
UPDATE ai_task SET heartbeat_at = NOW() WHERE id = #{taskId}
```

### 时间线示例

```
正常运行：
0s    5s    10s   15s   20s   ...  90s（完成）
♥     ♥     ♥     ♥     ♥          markSuccess

崩溃场景：
0s    5s    10s   ✗（Pod 被杀）
♥     ♥     ♥
                  心跳停止
                  ...
                  5 分钟后 RecoveryJob 发现 → 恢复
```

---

## 三、恢复机制设计

### 组件

| 类 | 职责 | 触发方式 |
|---|------|---------|
| `AiTaskRecoveryJob` | 定时扫描心跳超时的 RUNNING 任务 | `@Scheduled(fixedDelay=30000)` |
| `AiTaskRecoveryExecutor` | 对单个超时任务执行恢复操作 | 被 RecoveryJob 调用 |

### AiTaskRecoveryJob（巡逻员）

```java
@Scheduled(fixedDelay = 30_000)  // 每 30 秒扫描一次
public void recoverStaleTasks() {
    LocalDateTime timeout = LocalDateTime.now().minusMinutes(5);
    List<AiTaskEntity> staleTasks = aiTaskRepository.findStaleRunning(timeout, 50);
    // SQL: SELECT * FROM ai_task WHERE status='RUNNING' AND heartbeat_at < NOW() - 5分钟

    for (AiTaskEntity task : staleTasks) {
        executor.recoverSingleTask(task);
    }
}
```

**逻辑**：如果一个任务是 RUNNING 状态，但心跳超过 5 分钟没更新，说明执行它的 Consumer 已经挂了。

### AiTaskRecoveryExecutor（急救员）

```java
@Transactional
public void recoverSingleTask(AiTaskEntity task) {
    int currentRetry = task.getRetryCount();

    // 1. 超过最大重试次数 → 放弃
    if (currentRetry >= maxRetry) {
        aiTaskRepository.markFailed(task.getTaskId(), "超时重试次数超限");
        return;
    }

    // 2. 检查是否已有未投递的 Outbox 事件（避免重复创建）
    if (outboxEventRepository.hasPendingEventForAggregate(aggregateId)) {
        return;
    }

    // 3. 标记任务为 RETRYING + 创建新 Outbox 事件
    aiTaskRepository.markRetryingImmediate(task.getTaskId(), "Worker 心跳超时");
    OutboxEventEntity retryEvent = OutboxEventEntity.newEvent(...);
    outboxEventRepository.save(retryEvent);
    // → 定时投递器会捞到这条新事件 → 投递到 MQ → Consumer 重新执行
}
```

### 恢复流程图

```
AiTaskRecoveryJob（每 30s）
  │
  ├── 扫描 heartbeat_at < NOW()-5min 且 status=RUNNING 的任务
  │
  └── 对每个超时任务：
        ├── retryCount >= 3？
        │     → YES: markFailed（放弃，需人工介入）
        │     → NO: 继续 ↓
        │
        ├── 已有待投递 Outbox 事件？
        │     → YES: 跳过（避免重复）
        │     → NO: 继续 ↓
        │
        ├── markRetryingImmediate（状态改为 RETRYING）
        └── 创建新 Outbox 事件
              → OutboxPublisher 扫描投递到 MQ
              → Consumer 收到消息 → claimTask → executeTask
              → 任务重新执行
```

---

## 四、为什么 RecoveryExecutor 单独抽成一个类？

为了解决 **Spring AOP `@Transactional` 自调用问题**：

```
RecoveryJob.recoverStaleTasks()   ← 非事务方法
  → executor.recoverSingleTask()  ← @Transactional 方法（跨类调用，代理生效 ✅）
```

如果把 `recoverSingleTask` 放在 RecoveryJob 内部，`this.recoverSingleTask()` 不走代理，事务不生效。

---

## 五、重试次数限制

| 重试次数 | 行为 |
|---------|------|
| 第 1 次 | markRetrying + 重新投递 MQ |
| 第 2 次 | 同上 |
| 第 3 次 | 超过 maxRetry → markFailed（不再重试） |

**为什么要限制？** 防止某些永久性故障（如 prompt 格式错误触发模型崩溃）导致无限重试循环。

---

## 六、与 RocketMQ 重试的区别

| | RocketMQ 重试 | RecoveryJob 恢复 |
|--|--------------|-----------------|
| 触发条件 | Consumer 抛出 `RetryableAgentException` | 心跳超时（Consumer 进程崩溃） |
| 重试方式 | MQ Broker 自动重新投递消息 | 创建新 Outbox 事件 → 重新走投递流程 |
| 场景 | 模型限流、网络超时（代码还在运行） | 进程 OOM、Pod 被杀（代码不在了） |
| 最大次数 | 3 次（maxReconsumeTimes） | 3 次（recovery.max-retry-count） |

---

## 七、面试话术

**Q: 如果 AI 任务执行到一半 Consumer 挂了怎么办？**

> 我设计了心跳 + 补偿恢复机制。Consumer 执行过程中每 5 秒更新一次心跳时间戳到 DB（节流避免打爆数据库），有一个 RecoveryJob 每 30 秒扫描一次，发现心跳超过 5 分钟没更新的 RUNNING 任务就视为"执行者已死"，把任务回退为 RETRYING 并重新创建 Outbox 事件走投递流程。最多重试 3 次，超过就标记 FAILED 需要人工介入。

**Q: 为什么心跳间隔是 5 秒而不是更短/更长？**

> 太短（如 1s）会增加 DB 压力；太长（如 30s）会导致发现死任务的延迟变大（RecoveryJob 要等 心跳超时阈值 才能判定）。5 秒是性能和及时性的平衡点，对应的超时阈值设为 5 分钟，保证不会误判正常执行中的慢任务。

**Q: 心跳和 RecoveryJob 扫描有什么配合关系？**

> 超时阈值（5 分钟）>> 心跳间隔（5 秒）>> 扫描频率（30 秒）。这样保证：正常任务不会被误判（即使偶尔有一两次心跳延迟也不会超过 5 分钟），真正挂掉的任务最多 5 分钟 + 30 秒内被发现。

---

## 八、Consumer 崩溃的极端场景

| 场景 | 发生原因 |
|------|---------|
| OOM（内存溢出） | AI 任务处理大文本时内存持续增长，JVM 被杀 |
| K8s Pod 驱逐/杀死 | 节点资源不足，K8s 根据优先级强杀 Pod（SIGKILL） |
| Full GC 导致超时 | GC 暂停过长，RocketMQ 认为 Consumer 心跳丢失 |
| 网络分区 | Consumer 和 DB/Redis 之间网络中断 |
| 代码 bug 导致死循环/阻塞 | Agent 调用卡死（HTTP 连接未设超时） |
| 机器硬件故障 | 磁盘满、宿主机宕机 |

**共同特征**：进程突然死亡或永久卡住，没有机会执行 catch 块中的 `markFailed`。

---

## 九、findStaleRunning 为什么同时查 RUNNING 和 RETRYING

### SQL 实际查询

```sql
SELECT * FROM ai_task
WHERE status IN ('RUNNING', 'RETRYING')
  AND heartbeat_at < NOW() - INTERVAL 5 MINUTE
```

### 为什么 RETRYING 也要查？

**正常恢复路径**：RETRYING 只是短暂中间状态，几秒内就被新 Consumer 消费。

**异常场景**（只查 RUNNING 会漏掉）：

```
时刻 T0：任务 RUNNING，Consumer A 崩溃
时刻 T5min：RecoveryJob 发现 → markRetrying + 创建 Outbox 事件

然后：
  - OutboxPublisher 也挂了（或 MQ Broker 不可用）→ 事件无法投递
  - 或 MQ 投递成功但新 Consumer claimTask 失败
  - 或新 Consumer 在 claim 后也崩溃了

→ 任务卡在 RETRYING，heartbeat_at 一直是旧值
→ 如果只查 RUNNING → 找不到它 → 永远无人恢复 ❌
→ 加上 RETRYING → 下一轮 RecoveryJob 能再次发现并重试 ✅
```

### 为什么不会重复恢复？

`RecoveryExecutor` 内有去重检查：

```java
if (outboxEventRepository.hasPendingEventForAggregate(aggregateId)) {
    return;  // 已有未投递事件在排队，跳过
}
```

即使 RETRYING 任务被多次扫到，只要已有 Outbox 事件在等投递，就不会重复创建。

---

## 十、用户等待体验优化

### 问题

Consumer 崩溃后，用户最坏需要等 ~5 分钟（超时阈值）+ ~30 秒（扫描间隔）才能恢复。期间前端一直显示"生成中..."，体验不好。

### 解决方案：前端超时提示

在 `AiWritingPanel` 组件中加入客户端超时检测：

```typescript
// 每收到一个事件更新时间戳
lastEventTimeRef.current = Date.now();

// 定时检测：3 分钟无新事件 → 显示恢复提示
setInterval(() => {
  if (Date.now() - lastEventTimeRef.current > 3 * 60 * 1000) {
    setTimeoutWarning(true);  // 显示"任务可能异常，正在恢复中..."
  }
}, 10_000);
```

用户看到的效果：

```
正常：   生成中... → token 流式展示 → 生成完成（30s~2min）
异常：   生成中... → 3 分钟无事件 → ⚠ "任务可能异常，系统正在恢复中..."
                                    → RecoveryJob 恢复后重新执行 → 流式事件恢复
```

### 为什么是 3 分钟而不是 5 分钟？

- 正常 AI 任务最长 ~2 分钟，超过 3 分钟大概率已异常
- 比后端超时阈值（5 分钟）短，让用户提前知道"在处理中"
- 避免用户以为页面卡死而手动刷新（刷新后 SSE 重连仍能接收到恢复后的事件）

---

## 十一、面试补充问题

**Q: Consumer 崩溃一般什么情况下会发生？**

> 最常见是 OOM（AI 任务处理大文本时 StringBuilder 持续增长）和 K8s Pod 驱逐（节点资源紧张时 kubelet 强杀低优先级 Pod）。还有网络分区、Full GC 卡顿等。共同特征是进程突然死亡，无法执行 finally 块中的清理逻辑。

**Q: 用户在等待恢复期间会不会一直卡着？**

> 后端最坏需要 5 分钟恢复，但前端做了 3 分钟超时提示，显示"任务可能异常，系统正在恢复中..."。用户知道系统在自动处理，不会误以为页面卡死。恢复后 SSE 连接会继续接收事件，前端自动恢复展示。

**Q: 为什么 findStaleRunning 同时查 RUNNING 和 RETRYING 两种状态？**

> RETRYING 正常情况是短暂中间状态（几秒内就被新 Consumer 消费），但如果 Outbox 投递失败、MQ 不可用、或新 Consumer 也崩溃了，任务可能永远卡在 RETRYING。加上 RETRYING 让 RecoveryJob 能兜底这种"恢复流程本身也失败"的二次故障。配合 `hasPendingEventForAggregate` 去重检查，保证不会重复投递。
