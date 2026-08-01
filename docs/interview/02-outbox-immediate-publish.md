# Outbox 即时投递 + 定时兜底：面试话术（面试准备）

## 背景

这是我在项目中做的一次实际优化：将纯轮询 Outbox 模式改为"即时投递 + 定时兜底"混合方案，消除了 2 秒投递延迟。

---

## Q1：你为什么要做这个优化？原来有什么问题？

原来的方案是纯轮询 Outbox：`submitTask()` 只负责写 DB（任务表 + outbox_event 表），然后由一个定时任务每 2 秒扫描一次 outbox_event 表，把待投递的事件发到 RocketMQ。

对于当前 AI 写作这种 30s~2min 的长耗时任务，2 秒延迟确实无关紧要。但我思考了两个问题：

第一，如果以后要支持秒级任务（比如标题生成、摘要提取这种快任务），2 秒延迟就占了执行时间的很大比例，用户体感会变差。

第二，从架构设计的角度，既然事务已经提交了，为什么不立即尝试发送？成功了就省掉等待，失败了反正有定时任务兜底，不影响任何功能。

所以就做了这个"即时投递 + 定时兜底"的混合方案。

---

## Q2：具体怎么改的？

核心思路是把 `submitTask()` 拆成两步：

**第一步（事务内）**：写 ai_task + 写 outbox_event，和原来一样在同一个事务中，保证一致性。

**第二步（事务外）**：事务提交成功后，立即尝试把这条 outbox 事件投递到 MQ。成功就标记为 PUBLISHED；失败就静默忽略，等 2 秒后定时任务来兜底。

```java
// 拆分后的 submitTask
public AiTaskEntity submitTask(...) {
    AiTaskEntity task = self.doSubmitInTransaction(...);  // 事务内：写 DB
    outboxImmediatePublisher.tryPublish(task.getTaskId());  // 事务外：即时投递
    return task;
}
```

即时投递器的核心逻辑很简单：查出刚写入的 outbox 事件 → 检查状态是 NEW → syncSend 到 MQ → 标记 PUBLISHED。整个 try-catch 包住，任何异常都不抛出。

---

## Q3：这里有一个问题——doSubmitInTransaction 是同类内部调用，@Transactional 怎么生效？

是的，Spring AOP 是基于代理的，同类的 `this.method()` 调用不走代理，`@Transactional` 不会生效。

我用了 `@Lazy @Resource private AiWritingService self;` 注入自身代理。这样 `self.doSubmitInTransaction()` 是通过代理对象调用的，事务正常生效。

`@Lazy` 是为了避免自我注入导致的循环依赖——Spring 创建 Bean 时还没完成，如果立即注入自己就死循环了，`@Lazy` 延迟到第一次使用时才从容器取，此时 Bean 已经创建好了。

---

## Q4：即时投递失败了怎么办？会不会丢消息？

不会丢。失败了事件状态仍然是 NEW，定时任务每 2 秒扫描 `status=NEW` 的事件，会自动补发。

从设计上看是**两条路径投递同一张表的事件**，只是触发时机不同：

| 路径 | 触发时机 | 正常路径延迟 |
|------|---------|------------|
| 即时投递 | 事务提交后立即 | ≈0ms |
| 定时兜底 | 每 2 秒扫描 | ≤2s |

正常情况下即时投递成功，事件标记为 PUBLISHED，定时任务扫描时已经不会再捞到它。只有即时投递失败时，定时任务才会补发。

---

## Q5：会不会出现即时投递和定时任务同时投递同一条事件，导致消息重复？

理论上极小概率会发生：事务刚提交的那个微秒窗口内，定时任务恰好扫到了这条 NEW 事件。

但即使重复投递了也没关系——Consumer 端有 CAS 幂等保证：

```sql
UPDATE ai_task SET status='RUNNING' WHERE id=? AND status='PENDING'
```

第一条消息 claim 成功（affectedRows=1），第二条消息 claim 时状态已经不是 PENDING 了（affectedRows=0），直接跳过。

这就是"**发送端尽量不重复，消费端保证幂等**"的两层防护设计。

---

## Q6：为什么不在事务内直接发 MQ？

因为 DB 事务和 MQ 投递是两个独立系统，无法放在同一个原子操作中。如果在事务内发 MQ：

- MQ 发成功但事务回滚 → Consumer 收到消息去查任务，DB 里没有 → 数据不一致
- MQ 超时（实际已送达）但事务回滚 → 同上
- 事务还没 commit 就发了 MQ → Consumer 可能查到 uncommitted 的脏数据

把 MQ 投递放到**事务提交之后**，保证 Consumer 收到消息时 DB 中的数据一定已经持久化了。

---

## Q7：这个优化的实际效果是什么？

正常路径延迟从 **≤2秒** 降到 **≈0毫秒**。用户提交任务后，Consumer 几乎立即就能收到消息开始执行。

从日志上看：`submitTask` 返回后几毫秒内就打印了"即时投递成功"和"Consumer 收到任务"，相比之前要等 0~2 秒的轮询间隔，响应速度有明显提升。

---

## Q8：这个方案和 CDC（Change Data Capture）有什么区别？

完全不同。CDC 是在数据库层面监听 binlog 变更，由外部组件（如 Debezium）自动把 INSERT 事件投递到 MQ，应用代码完全不感知 MQ 的存在。

我的方案是**应用层的主动推送**——代码中显式调用 `syncSend()`，只是把调用时机从"定时扫描"前置到了"事务提交后立即"。不需要引入额外组件，改动最小化。

---

## 关键代码文件

| 文件 | 作用 |
|------|------|
| `AiWritingService.java` | 拆分事务：`submitTask()` → `self.doSubmitInTransaction()` + `tryPublish()` |
| `IOutboxImmediatePublisher.java`（新增） | domain 层即时投递接口 |
| `OutboxImmediatePublisher.java`（新增） | trigger 层实现：查事件 → 检查状态 → syncSend → markPublished |
| `IOutboxEventRepository.java` | 新增 `findLatestByAggregateId()` 方法 |
| `AiTaskOutboxPublisher.java` | 不改动，继续作为定时兜底 |
| `AiTaskConsumer.java` | 不改动，CAS 幂等逻辑不变 |

---

## 面试总结一句话

> 我将纯轮询 Outbox 优化为"即时投递 + 定时兜底"混合方案：事务提交后立即尝试发 MQ（0ms 延迟），失败静默降级由定时任务每 2 秒补发。通过 `@Lazy self` 解决了 Spring AOP 事务自调用问题，消费端 CAS 保证即使极端情况重复投递也只执行一次。改动 6 个文件，不动表结构，不影响 Consumer 逻辑。
