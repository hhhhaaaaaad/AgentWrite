# Outbox 即时投递 + 定时兜底 改造计划

## 背景

当前项目采用纯轮询 Outbox 模式：`submitTask()` 只写 DB，由 `AiTaskOutboxPublisher` 每 2 秒扫描并投递到 RocketMQ。对于 AI 写作任务本身耗时 30s~2min 的场景，2s 延迟可忽略；但如果希望进一步优化响应速度（如未来支持秒级任务），可以改为"即时投递 + 定时兜底"混合方案。

## 改造目标

- 事务提交后**立即尝试**发送 MQ，正常路径延迟 ≈ 0ms
- 发送失败时不影响主流程，由现有定时任务兜底补发
- 保留 outbox_event 表的可观测性（状态、重试次数、错误信息）
- 改动最小化，不改变现有表结构和 Consumer 逻辑

## 改造方案

### 核心思路

```
@Transactional {
    INSERT ai_task
    INSERT outbox_event (status=INIT)
}
// 事务提交成功后 ↓
try {
    rocketMQTemplate.syncSend(topic, payload);
    outboxEvent 标记为 PUBLISHED;
} catch (Exception e) {
    log.warn("即时投递失败，等待定时任务兜底");
    // 不抛异常，不影响用户响应
}
```

### 修改文件

| 文件 | 改动内容 |
|------|---------|
| `AiWritingService.java` | 拆分事务：提取事务外的即时投递逻辑 |
| `AiTaskOutboxPublisher.java` | 无需修改，继续作为兜底扫描（捞 status=INIT 的漏网事件） |
| `AiTaskConsumer.java` | 无需修改，幂等逻辑不变（claimTask CAS） |

### 详细改动

#### AiWritingService.java

**改造前**（当前代码）：

```java
@Override
@Transactional
public AiTaskEntity submitTask(Long userId, Long draftId, String taskTypeCode, 
                                Map<String, Object> promptParams, Boolean enableIllustration) {
    // ... 限流、加锁、校验 ...
    
    aiTaskRepository.save(task);
    
    OutboxEventEntity outboxEvent = OutboxEventEntity.newEvent(taskId, EVENT_TYPE_CREATED, mqTopic, "{}");
    outboxEventRepository.save(outboxEvent);
    AiTaskMessage message = AiTaskMessage.builder()
            .taskId(taskId).eventId(outboxEvent.getEventId())
            .createdAt(LocalDateTime.now().toString()).build();
    outboxEventRepository.updatePayload(outboxEvent.getEventId(), JSON.toJSONString(message));
    
    return task;
}
```

**改造后**：

```java
@Override
public AiTaskEntity submitTask(Long userId, Long draftId, String taskTypeCode,
                                Map<String, Object> promptParams, Boolean enableIllustration) {
    // ... 限流、加锁、校验（不变）...
    
    // 第一步：事务内写 DB
    AiTaskEntity task = doSubmitInTransaction(userId, draftId, taskTypeCode, promptParams, enableIllustration);
    
    // 第二步：事务外立即尝试投递 MQ
    tryImmediatePublish(task.getTaskId());
    
    return task;
}

@Transactional
protected AiTaskEntity doSubmitInTransaction(Long userId, Long draftId, String taskTypeCode,
                                              Map<String, Object> promptParams, Boolean enableIllustration) {
    DraftEntity draft = draftDomainService.queryDraftDetail(draftId, userId);
    draft.checkEditable();
    AiWritingTaskTypeVO taskType = AiWritingTaskTypeVO.fromCode(taskTypeCode);
    String prompt = buildPrompt(draft, taskType, promptParams);
    AiTaskEntity task = AiTaskEntity.initPending(userId, draftId, taskType, prompt, enableIllustration);
    aiTaskRepository.save(task);
    Long taskId = task.getTaskId();

    OutboxEventEntity outboxEvent = OutboxEventEntity.newEvent(taskId, EVENT_TYPE_CREATED, mqTopic, "{}");
    outboxEventRepository.save(outboxEvent);
    AiTaskMessage message = AiTaskMessage.builder()
            .taskId(taskId).eventId(outboxEvent.getEventId())
            .createdAt(LocalDateTime.now().toString()).build();
    outboxEventRepository.updatePayload(outboxEvent.getEventId(), JSON.toJSONString(message));

    return task;
}

/**
 * 事务外即时投递：成功则标记 PUBLISHED，失败则静默等定时任务兜底
 */
private void tryImmediatePublish(Long taskId) {
    try {
        OutboxEventEntity event = outboxEventRepository.findLatestByAggregateId(taskId);
        if (event == null || !"INIT".equals(event.getStatus())) return;
        
        Object payloadObj = JSON.parse(event.getPayload());
        SendResult result = rocketMQTemplate.syncSend(event.getTopic(), payloadObj);
        outboxEventRepository.markPublished(event.getEventId());
        log.info("即时投递成功 taskId={} eventId={} msgId={}", taskId, event.getEventId(), result.getMsgId());
    } catch (Exception e) {
        log.warn("即时投递失败 taskId={}, 等待定时兜底: {}", taskId, e.getMessage());
        // 不抛异常，不影响 submitTask 的返回
    }
}
```

### 需要补充的 Repository 方法

```java
// IOutboxEventRepository.java
OutboxEventEntity findLatestByAggregateId(Long aggregateId);
```

对应 SQL：
```sql
SELECT * FROM outbox_event WHERE aggregate_id = #{aggregateId} ORDER BY created_at DESC LIMIT 1
```

### 时序图

```
用户请求
  │
  ├── submitTask()
  │     ├── doSubmitInTransaction()  ←── DB 事务
  │     │     ├── save(ai_task)
  │     │     └── save(outbox_event, status=INIT)
  │     │
  │     └── tryImmediatePublish()    ←── 事务外，无事务
  │           ├── 成功 → markPublished()
  │           └── 失败 → 静默，等兜底
  │
  └── 返回 taskId 给前端
  
                        ┌── 定时兜底（每 2s）──┐
                        │ 扫描 status=INIT     │
                        │ 投递到 MQ            │
                        │ 标记 PUBLISHED       │
                        └─────────────────────┘
```

## 注意事项

1. **`@Transactional` 自调用问题**：`doSubmitInTransaction()` 需要通过代理调用才能生效。方案：
   - 抽到独立的 `AiTaskTransactionService` 中（推荐）
   - 或注入自身代理 `@Lazy private AiWritingService self;`

2. **幂等保证不变**：Consumer 的 `claimTask()` CAS 机制不受影响，即使即时投递 + 定时兜底导致重复投递，Consumer 也只会执行一次

3. **锁的位置不变**：限流和分布式锁逻辑保持在外层 `submitTask()` 中，不进入事务方法

## 验证方式

1. 正常场景：提交任务后观察日志是否打印"即时投递成功"，Consumer 是否立即收到消息
2. 异常场景：mock `rocketMQTemplate.syncSend()` 抛异常，验证任务仍能创建成功，且 2s 后被定时任务补发
3. 幂等场景：手动触发重复投递，验证 Consumer 的 `claimTask` 只有一个成功
