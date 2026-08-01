# 多租户 Runner 缓存优化方案（面试准备）

## 背景

当前多租户实现中，每次 `executeTask` 都会走一遍策略树动态装配 Runner（`AiApiNode → ChatModelNode → AgentNode → RunnerNode`）。虽然开销仅微秒级（纯对象构建，无 IO），但绝大多数请求的用户配置并没有变化，重复装配属于浪费。

---

## 问题分析

| 用户类型 | 占比（预估） | 当前行为 | 理想行为 |
|---------|------------|---------|---------|
| 无自定义配置 | ~80% | 每次装配（用系统默认值） | 直接用启动时的全局单例 Runner |
| 有自定义配置，配置不变 | ~18% | 每次装配（用相同配置重复构建） | 从缓存取已构建好的 Runner |
| 有自定义配置，刚修改过 | ~2% | 每次装配（唯一合理的场景） | 缓存失效后重建 |

**结论**：只有 2% 的场景真正需要重新装配，98% 可以直接复用。

---

## 优化方案：LoadingCache + 按需失效

### 核心设计

```java
// 以 modelConfigId 为 key 缓存 Runner（null key = 系统默认，直接返回全局单例）
LoadingCache<Long, InMemoryRunner> runnerCache = Caffeine.newBuilder()
        .maximumSize(200)               // 最多缓存 200 个用户的 Runner
        .expireAfterAccess(30, MINUTES) // 30 分钟无访问自动淘汰
        .build(configId -> buildRunnerFromConfig(configId));  // miss 时走策略树装配
```

### 使用路径

```java
public String run(AiTaskEntity task, ...) {
    Long configId = task.getModelConfigId();

    if (configId == null) {
        // 无自定义配置 → 直接用启动时构建的全局单例 Runner（和原来完全一样）
        runner = defaultArmoryFactory.getAiAgentRegisterVO(agentId).getRunner();
    } else {
        // 有自定义配置 → 从缓存取（首次 miss 自动装配，后续直接命中）
        runner = runnerCache.get(configId);
    }

    return runner.runAsync(userId, sessionId, content);
}
```

### 缓存失效时机

```java
// 用户修改配置时，主动失效对应缓存条目
public void updateConfig(UserModelConfigEntity entity) {
    configRepository.update(entity);
    runnerCache.invalidate(entity.getId());  // 下次请求自动重建
}

// 用户删除配置时
public void deleteConfig(Long id, Long userId) {
    configRepository.delete(id, userId);
    runnerCache.invalidate(id);
}
```

### 多实例部署的缓存一致性

```
实例 A：用户改了配置 → invalidate 本地缓存 ✅
实例 B：不知道用户改了配置 → 本地缓存还是旧 Runner ❌
```

解决方案（二选一）：

| 方案 | 实现 | 复杂度 |
|------|------|--------|
| **Redis Pub/Sub 广播** | 改配置时发布 `config_invalidate:{configId}` 消息，各实例订阅后淘汰本地缓存 | 中 |
| **接受短暂不一致** | TTL 30 分钟后自然过期，用户最多等 30 分钟用上新配置 | 低 |

> 推荐接受短暂不一致 + 缩短 TTL 到 5 分钟。对 AI 写作场景来说，用户改完 Key 等几分钟才生效是可接受的。

---

## 线程安全性

**InMemoryRunner 是线程安全的**：

- `runner.runAsync(userId, sessionId, content)` 每次传入独立的 sessionId
- Session 数据按 sessionId 隔离（存在 Runner 内部的 ConcurrentHashMap 中）
- 多个线程并发调用同一个 Runner 实例互不影响

这和当前启动时构建的全局单例 Runner 被所有用户共享是同一个模式，已经验证过线程安全。

---

## 优化效果

| | 当前方案 | 缓存方案 |
|--|---------|---------|
| 无自定义配置 | 走策略树装配（~100μs） | 直接返回全局单例（~0μs） |
| 有自定义配置（缓存命中） | 走策略树装配（~100μs） | 缓存 get（~1μs） |
| 有自定义配置（缓存 miss） | 走策略树装配（~100μs） | 走策略树装配（~100μs） |
| 用户改了配置 | 无感 | invalidate + 下次重建 |

---

## 面试怎么说

> 当前实现为了简单性选择每次动态装配，开销是微秒级的，对 30s~2min 的 AI 任务来说可以忽略。但如果用户量上来，我的优化方向是用 Caffeine LoadingCache 缓存 Runner 实例，以 configId 为 key，配置变更时 invalidate。这样 95% 的请求走缓存零开销，只有配置变更后的第一次请求需要重建。Runner 是线程安全的，多个任务共享一个实例没有并发问题。多实例部署时用 Redis Pub/Sub 广播失效通知，或者接受 TTL 到期后自然刷新的短暂不一致。

---

## 为什么当前不做这个优化

1. **过早优化**：当前用户量小，微秒级开销完全无感
2. **引入复杂度**：缓存失效、多实例一致性、内存管理都是额外代码
3. **收益不明显**：AI 调用 60s，省 100μs 的装配时间，优化率 0.0002%

面试中展示"知道怎么优化 + 知道什么时候不该优化"比"把所有优化都做了"更有说服力。
