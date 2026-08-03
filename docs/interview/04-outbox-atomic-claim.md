# Outbox 多实例安全投递：原子抢占 + SENDING 状态机制（面试准备）

## 一、要解决的问题

当应用部署**多个实例**时，每个实例都有定时任务在扫描 `outbox_event` 表。如果不做并发控制，多个实例会同时拿到同一批事件并重复投递到 MQ。

---

## 二、错误方案：先 SELECT 再 UPDATE（有竞态窗口）

```java
// ❌ 直接查出来，发完再改状态
List<OutboxEventPO> events = dao.selectByStatus("NEW", 100);
for (event : events) {
    rocketMQTemplate.syncSend(event.getTopic(), event.getPayload());
    dao.markPublished(event.getEventId());
}
```

### 问题场景

```
时刻 T1：实例 A SELECT → 拿到 event 1,2,3（status 还是 NEW）
时刻 T1：实例 B SELECT → 也拿到 event 1,2,3（status 还是 NEW，还没被改）
时刻 T2：实例 A 发送 event 1 → markPublished
时刻 T2：实例 B 发送 event 1 → markPublished
→ event 1 被重复投递 ❌
```

**根因**：SELECT 和 UPDATE 之间有时间窗口（竞态条件），多实例会在窗口内查到同一批数据。

---

## 三、正确方案：先 UPDATE 抢占再回查（原子操作，无竞态）

```java
// ✅ 先原子抢占，再回查自己抢到的
String publisherId = UUID.randomUUID().toString();
dao.claimPublishableBatch(publisherId, limit);           // Step 1: UPDATE 抢占
List<OutboxEventPO> events = dao.findClaimedByPublisherId(publisherId);  // Step 2: 回查
for (event : events) {
    rocketMQTemplate.syncSend(event.getTopic(), event.getPayload());
    dao.markPublished(event.getEventId());
}
```

### Step 1 对应的 SQL（原子抢占）

```sql
UPDATE outbox_event
SET status = 'SENDING',
    publisher_id = #{publisherId},
    update_time = NOW()
WHERE status IN ('NEW', 'RETRYING')
  AND next_retry_at <= NOW()
ORDER BY create_time ASC
LIMIT 100
```

**为什么安全**：MySQL 的 `UPDATE` 语句对命中的行加**排他行锁（X Lock）**，同一行不会被两个 UPDATE 同时修改。A 把 event 1 改为 SENDING 后，B 的 `WHERE status='NEW'` 已经匹配不到 event 1。

### Step 2 对应的 SQL（回查自己的）

```sql
SELECT * FROM outbox_event
WHERE status = 'SENDING' AND publisher_id = #{publisherId}
ORDER BY create_time ASC
```

**为什么需要 publisherId**：UPDATE 只返回 `affectedRows`（数字），不返回行数据。需要一个标识来回查"我抢到了哪些行"。同时防止查到其他实例抢的行。

---

## 四、多实例并发执行图示

```
实例 A（publisherId = aaa）          实例 B（publisherId = bbb）
       │                                    │
       │── UPDATE SET publisher_id='aaa'    │
       │   WHERE status='NEW' LIMIT 100     │
       │   → 锁住 event 1,2,3              │
       │                                    │── UPDATE SET publisher_id='bbb'
       │                                    │   WHERE status='NEW' LIMIT 100
       │                                    │   → 1,2,3 已不是 NEW，只能拿到 4,5,6
       │                                    │
       │── SELECT WHERE publisher_id='aaa'  │── SELECT WHERE publisher_id='bbb'
       │   → 1,2,3                          │   → 4,5,6
       │                                    │
       │── 发送 1,2,3 到 MQ                  │── 发送 4,5,6 到 MQ
       │── markPublished(1,2,3)             │── markPublished(4,5,6)

结果：每条事件只被投递一次 ✅
```

---

## 五、SENDING 状态的三重作用

| 作用 | 说明 |
|------|------|
| **占位锁** | 标记"已被某实例处理中"，其他实例的 `WHERE status='NEW'` 不会再命中 |
| **崩溃检测** | 如果实例抢占后崩溃，事件会卡在 SENDING → 超时补偿任务负责恢复 |
| **防止自己重复处理** | 下一轮扫描的 WHERE 条件不含 SENDING，不会重复拿同一条 |

---

## 六、SENDING 超时恢复机制

如果实例在 `claimPublishable` 之后崩溃（还没来得及发 MQ 或 markPublished），事件会卡在 SENDING 状态。

**补偿任务**（每 60 秒执行）：

```sql
UPDATE outbox_event
SET status = 'RETRYING', publisher_id = NULL, update_time = NOW()
WHERE status = 'SENDING'
  AND update_time < NOW() - INTERVAL 5 MINUTE
```

超时 5 分钟的 SENDING 事件回退为 RETRYING，下一轮正常轮询会重新投递。

---

## 七、完整状态流转图

```
NEW ──────→ SENDING ──────→ PUBLISHED（终态，投递成功）
 │              │
 │              └── 超时 5min → RETRYING ──→ SENDING → ...（重新投递）
 │                                │
 └────────────────────────────────┘
                                  │
                          retry_count >= maxRetry(5)
                                  │
                                  ▼
                              FAILED（终态，需人工介入）
```

---

## 八、两层防护总结

| 层级 | 机制 | 保证什么 |
|------|------|---------|
| **发送端** | UPDATE 行锁 + publisherId + SENDING 状态 | 同一条事件只被一个实例投递 |
| **消费端** | CAS 原子抢占 `claimTask(WHERE status='PENDING')` | 即使消息重复，也只执行一次 |

> **面试话术**：发送端通过数据库行锁的原子 UPDATE 实现互斥抢占，配合 SENDING 中间状态隔离已抢占的事件，保证多实例不重复投递。即使极端情况下消息重复（如 markPublished 失败），消费端的 CAS 抢占也能保证幂等，做到"发送端尽量不重复，消费端绝对不重复执行"。

---

## 九、面试高频问题

### Q: 为什么不用分布式锁（如 Redis）来保证只有一个实例扫描？

> 可以用，但行锁方案更轻量：不引入额外组件依赖，且支持多实例并行扫描（每个实例抢不同的行），吞吐更高。分布式锁会退化为"同一时间只有一个实例干活"，浪费其他实例的算力。

### Q: publisherId 为什么用 UUID 而不是实例 IP/hostname？

> 用 UUID 保证"每次扫描"唯一，而不仅仅是"每个实例"唯一。同一个实例的两次扫描也需要区分（防止上一轮没处理完就开始下一轮时混淆）。

### Q: 如果 UPDATE 和 SELECT 之间实例崩溃了怎么办？

> 事件卡在 SENDING 状态 → 60 秒后 `recoverStaleSending` 补偿任务将超时 5 分钟的 SENDING 回退为 RETRYING → 下一轮正常扫描重新投递。不会丢消息。

---

## 十、即时投递（OutboxImmediatePublisher）为什么不需要 SENDING 状态？

### 场景区别

| | 即时投递（tryPublish） | 定时投递（publishPendingEvents） |
|--|--|--|
| **谁在投递** | 创建这条事件的那个请求线程 | 所有实例的定时任务都在扫描 |
| **有没有竞争** | 几乎没有（自己刚写入的） | 有（多实例同时扫同一张表） |
| **防重策略** | 状态检查 `status==NEW` 即可 | 需要行锁 + publisherId + SENDING 状态 |
| **为什么够用** | 只有一个线程在处理这一条 | N 个实例同时抢 N 条，必须严格互斥 |

### 为什么即时投递不存在竞争？

```java
AiTaskEntity task = self.doSubmitInTransaction(...);    // 事务提交，outbox_event 写入（status=NEW）
outboxImmediatePublisher.tryPublish(task.getTaskId());  // 微秒级间隔，紧接着就处理
```

这条 outbox_event 是**当前线程刚刚写入的**，此时：

| 可能的竞争者 | 能不能抢到它？ | 原因 |
|------------|-------------|------|
| 其他实例的 submitTask | ❌ | RLock 保证同一 userId+draftId+taskType 不会并发提交，不会产生同一条事件 |
| 定时任务 claimPublishable | 极小概率 | 事务提交到即时投递之间只有微秒级间隔，定时任务 2s 才扫一次 |

### 即使极小概率被定时任务抢到

`tryPublish` 里有状态检查兜底：

```java
// OutboxImmediatePublisher.tryPublish()
if (!"NEW".equals(event.getStatus().getCode())) {
    return;  // 已被定时任务抢走（变成了 SENDING），跳过，不重复发
}
```

### RLock 在这里的作用

注意：RLock **不是**用来防重复投递 MQ 的，而是防重复提交任务：

```
RLock 防的是：  用户双击按钮 → 两次 submitTask 创建两条 ai_task（业务层重复提交）
不是防的：      同一条 outbox_event 被投递两次到 MQ（技术层重复投递）
```

### 一句话总结

> 即时投递的安全性靠**时序保证**（自己刚写入、微秒级就处理，几乎不可能被别人抢走），不需要 SENDING 状态做"占位锁"。定时投递面对多实例并发扫描同一张表，才需要完整的行锁 + SENDING + publisherId 抢占机制。
