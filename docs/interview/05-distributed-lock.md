# 分布式锁在 AI 任务提交中的应用（面试准备）

## 一、要解决的问题

在**多实例部署 + 负载均衡**的环境下，用户可能因为快速双击或网络重试，导致**同一操作**的多个请求被路由到不同的应用实例，如果不做控制，会创建出多条重复的 AI 任务。

```
用户双击 AI 写作按钮
    → 两个请求同时发出
    → 经过 Nginx 负载均衡
    → 请求 1 路由到 实例 A
    → 请求 2 路由到 实例 B
    → 两个实例各自处理，互不知情 → 同一篇草稿生成了两篇 AI 结果 ❌
```

---

## 二、为什么不能用本地锁

| 锁类型 | 作用范围 | 多实例场景 |
|--------|---------|-----------|
| `synchronized` | 当前 JVM | ❌ 只能互斥本实例内的线程 |
| `ReentrantLock` | 当前 JVM | ❌ 同上 |
| Redis 分布式锁 | 跨 JVM（共享 Redis） | ✅ 对所有实例生效 |

本地锁的内存空间在各自 JVM 内，实例 A 不知道实例 B 是否持有锁。

---

## 三、本项目的实现

### 代码

```java
// AiWritingService.submitTask()
String lockKey = RedisKeyConstants.AI_TASK_LOCK_PREFIX + userId + ":" + draftId + ":" + taskTypeCode;
// 例如："ai:task:lock:10001:20001:GENERATE_BODY"
RLock lock = redissonClient.getLock(lockKey);
try {
    if (!lock.tryLock(0, 5, TimeUnit.SECONDS)) {
        throw new AppException(ResponseCode.E0001.getCode(), "请勿重复提交，上个任务仍在处理中");
    }
    // ... 正常业务逻辑 ...
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```

### 锁粒度设计

```
lockKey = prefix + userId + ":" + draftId + ":" + taskTypeCode
         ────────   ─────────┐    ──────────┐
                            │              │
                    用户维度（确保只限自己的操作）
                                   │
                            草稿维度（不同草稿互不影响）
                                          │
                                   操作维度（续写和润色可以同时做）
```

**为什么这样设计粒度？**

- 太粗（如只锁 userId）：用户不能同时对两篇草稿操作，体验差
- 太细（如锁 userId+draftId+taskType+随机数）：起不到防重效果
- 当前粒度：用户对同一草稿的**同一操作**不能并发，但可以对**不同草稿**或**不同操作**同时进行

---

## 四、Redisson 分布式锁的原理

### 加锁

```lua
-- Redisson 内部使用 Lua 脚本保证原子性
if (redis.call('exists', KEYS[1]) == 0) then
    redis.call('hincrby', KEYS[1], ARGV[2], 1);  -- 设置锁
    redis.call('pexpire', KEYS[1], ARGV[1]);        -- 设置过期时间
    return nil;
end;
-- 锁已存在 → 返回剩余过期时间（用于自旋等待）
return redis.call('pttl', KEYS[1]);
```

### 解锁

```lua
if (redis.call('hexists', KEYS[1], ARGV[3]) == 0) then
    return nil;  -- 不是自己持有的锁，不解
end;
redis.call('del', KEYS[1]);  -- 只有持有者才能释放
```

### 看门狗机制（Watchdog）

Redisson 的锁默认过期 30 秒，但如果业务还没执行完，**看门狗会每 10 秒自动续期，直到主动 unlock**。

```java
// tryLock(0, 5, TimeUnit.SECONDS)
//     ↑   ↑
//   不等待 手动过期时间（覆盖看门狗，设为 5 秒）
```

本项目用了手动过期 5 秒（不依赖看门狗续期），因为 submitTask 逻辑很简单，5 秒足够。

---

## 五、分布式锁 vs JVM 锁 对比

| | `synchronized` / `ReentrantLock` | Redis 分布式锁（Redisson） |
|--|--|--|
| 作用范围 | 单个 JVM 内 | 跨 JVM（所有连接同一 Redis 的实例） |
| 实现原理 | JVM 内存中的对象监视器 | Redis 中的 key-value + Lua 脚本 |
| 锁释放 | 代码块退出自动释放 | finally 中手动 unlock + 过期时间兜底 |
| 部署要求 | 单机 | 多实例（需要 Redis） |
| 性能 | 极高（内存操作） | 较高（一次网络 I/O） |
| 适用场景 | 单机应用 | 分布式系统 |

---

## 六、分布式锁 vs 数据库唯一索引

两种方案都能防重复提交，但选择不同：

| | Redis 分布式锁 | 数据库唯一索引 |
|--|--|--|
| 检测时机 | **提交前**（请求入口就拦截） | **提交后**（INSERT 时发现冲突） |
| 用户体验 | 友好提示"请勿重复提交" | 返回数据库错误，不够友好 |
| 资源浪费 | 不浪费 DB 连接 | 先占 DB 连接再报错 |
| 灵活度 | 可以自定义锁粒度、TTL | 粒度固定（唯一索引的列组合） |

> 本项目的选择：用分布式锁在**入口拦截**重复请求，用唯一索引在 DB 层做**最后兜底**，双层防护。

---

## 七、面试高频问题

### Q: 分布式锁怎么选型？Redis 还是 ZooKeeper？

> Redis 方案更适合本项目的场景：
> - Redis 已在项目中用于缓存和限流，复用现有基础设施，不增加运维成本
> - AI 任务提交对强一致性要求不高（偶尔重复还有消费端 CAS 兜底）
> - Redisson 的 API 简单，看门狗机制省去手动续期
> 
> ZooKeeper 用临时顺序节点实现锁，强一致性更好，但需额外部署和运维，适合对锁安全性要求极高的场景（如金融领域）。

### Q: 锁过期时间怎么设置？设短了怎么办？

> 本项目设了 5 秒手动过期，因为 submitTask 内部逻辑非常快（毫秒级）。如果真超时了，锁自动释放也不会造成严重问题——最多让另一个请求成功创建任务，等价于放宽了"同一操作必须串行"的限制，不会产生数据错误。

### Q: 如何避免误删别人的锁？

> Redisson 的解锁脚本会检查 `hexists`，只允许**锁的持有者**释放。`isHeldByCurrentThread()` 也在 `finally` 中做了双重校验。

### Q: Redis 挂了锁就失效了，怎么办？

> - 生产环境 Redis 用 Sentinel / Cluster 做主从 + 自动故障转移
> - 业务层面，还有数据库唯一索引做兜底
> - 即使锁失效导致重复创建了任务，消费端的 CAS 也能保证只执行一次

---

## 八、与项目中其他防护措施的协同

| 防护层 | 机制 | 防什么问题 |
|--------|------|-----------|
| **限流** | Redisson 令牌桶 | 用户请求频率过高（每分 5 次） |
| **分布式锁** | Redisson RLock | 同一操作重复提交（双击） |
| **DB 唯一索引** | UNIQUE KEY | 极限情况下的最后兜底 |
| **消费端 CAS** | WHERE status='PENDING' | 即使任务创建了两次，只执行一次 |
