# RxJava Flowable 响应式流处理（面试准备）

## 一、什么是 RxJava / Flowable

RxJava 是 **ReactiveX 的 Java 实现**，核心思想是：把一切抽象为**数据流（Stream）**，对数据流进行**声明式编排**。

`Flowable` 是 RxJava 3 中支持 **背压（Backpressure）** 的流类型，适合处理**生产者速率不可控、消费者处理能力有限**的场景。

### 核心概念

| 概念 | 类比 | 本项目中的角色 |
|------|------|-------------|
| Flowable&lt;T&gt; | 水管 | AI 模型 token 流 |
| 操作符（Operator） | 水管的阀门/过滤器 | `doOnNext`、`doOnComplete`、`doOnError` |
| 订阅（Subscribe） | 打开水龙头 | `blockingForEach` |
| 背压（Backpressure） | 水龙头控制流速 | AI 产出快 → 消费者慢 → 缓冲、丢弃或阻塞 |

---

## 二、本项目中的实际应用

### 核心代码

```java
// ChatService.handleMessageStream()
InMemoryRunner runner = aiAgentRegisterVO.getRunner();
Content userMsg = Content.fromParts(Part.fromText(message));
RunConfig runConfig = RunConfig.builder().setStreamingMode(RunConfig.StreamingMode.SSE).build();

return runner.runAsync(userId, sessionId, userMsg, runConfig)
        .doOnNext(event -> { aiResponse.append(event.stringifyContent()); })
        .doOnComplete(() -> { persistMessage(..., aiResponse.toString()); })
        .doOnError(error -> { ... });
```

```java
// AgentWritingRunner.runSingleAgent()
Flowable<Event> events = chatService.handleMessageStreamWithConfig(...);

events.blockingForEach(event -> {
    responseBuilder.append(event.stringifyContent());
    eventConsumer.accept(tokenEvent("generating", content));
});
```

### 为什么要"分流"？

同一个 event 需要被**两个消费者**处理：

| 消费者 | 位置 | 做什么 |
|--------|------|--------|
| ChatService 的 `doOnNext` | 内部 | 拼 `aiResponse`（流结束后持久化到 `chat_message`） |
| AgentWritingRunner 的 `blockingForEach` | 外层 | 实时写 Redis Stream（推给前端）+ 拼最终结果 |

为什么不能一个 `blockingForEach` 全搞定？因为 `blockingForEach` **直接消费流**，event 进去就没了。`doOnNext` 可以**旁路监听不拦截**，让外层还能继续处理。

### 操作符对比

| 操作符 | 效果 | 本项目中使用 |
|--------|------|-----------|
| `doOnNext` | 旁观：处理 event 但继续传递，不拦截流 | ChatService 中收集 AI 回复用于持久化 |
| `doOnComplete` | 流正常结束时执行 | 持久化完整的 AI 回复到 `chat_message` |
| `doOnError` | 流异常时执行 | 尽量持久化已收到的部分回复（不全丢） |
| `blockingForEach` | 同步阻塞消费整个流直到结束 | 驱动流执行 + 推 Redis Stream + 最终格式化结果 |

---

## 三、整个项目的"两阶段"流处理

### 第一阶段：AI 模型 → RxJava Flowable（异步）

```
AI 模型（DeepSeek）── SSE token 流 ──→ Google ADK InMemoryRunner
                                            │
                                       Flowable<Event>
```

Google ADK 底层通过 HTTP SSE 接收模型输出，每收到一个 token 就封装成 `Event` 对象，调用 `emitter.onNext(event)` 推入 Flowable。

Flowable 是**冷流（Cold Flowable）**——在 `blockingForEach` 调用前不触发 HTTP 请求。

### 第二阶段：Flowable → 两层消费

```
Flowable<Event>
        │
        ├── doOnNext: ChatService 收集完整回复 → 持久化到 chat_message
        │
        └── blockingForEach: AgentWritingRunner 实时推 Redis Stream → 前端 SSE
```

### 与 SSE 的关系

```
RxJava 流（进程内）                  SSE（HTTP 长连接）
  Consumer 线程                       Controller 线程
      │                                    │
      ├── blockingForEach 逐 token         │
      │      │                             │
      │      └── 写 Redis Stream ──────────┤
      │                                    ├── 每 500ms 轮询
      │                                    ├── 读到 event → emitter.send()
      │                                    └── 前端 EventSource 接收展示
```

两层没有直接调用关系：Consumer 写 Redis → Controller 读 Redis → 推前端。Redis 是它们之间的**桥接**。

---

## 四、为什么要引入 RxJava？

### 问题背景

AI 模型输出是**逐 token 产出的流式数据**，不是一次性返回的结果。需要一种机制：

1. **接收异步数据**：模型在远端每秒产出数十个 token
2. **实时处理**：每收到一个 token 就推送前端，不是等全部结束
3. **背压控制**：如果消费者慢了，不能无限缓冲导致 OOM
4. **流结束/异常处理**：流正常结束和异常中断要有不同处理

### 方案对比

| 方案 | 优点 | 缺点 |
|------|------|------|
| **RxJava Flowable**（本项目） | Google ADK 原生返回，零适配成本；背压支持；声明式操作符链 | 学习曲线中等 |
| Reactor Flux | Spring 生态更自然 | ADK 返回 RxJava，需额外适配 |
| 手写线程 + BlockingQueue | 灵活度高 | 代码量大，容易出错 |
| Servlet 3.1 异步 + SSE | 零额外依赖 | 缺少背压和流编排能力 |

选 RxJava 的决定性因素：**Google ADK 的 `runner.runAsync()` 原生返回 RxJava3 Flowable**。

---

## 五、面试高频问题

### Q: 为什么用 RxJava 而不是 Reactor？

> Google ADK 的 `runner.runAsync()` 原生返回 RxJava3 Flowable，直接使用避免了额外适配层。Reactor 是 Spring WebFlux 的默认响应式库，但本项目是 Spring MVC + SSE，不需要 Reactive Stack。Flowable 支持背压，适合 AI 模型输出速率不可控的场景。

### Q: Flowable 的"背压"是什么意思？

> AI 模型每秒可能产出几十个 token，如果消费者处理不过来（比如 Redis 网络变慢），数据会在内存中堆积直到 OOM。背压就是让消费者可以告诉生产者"慢一点"或"先存着但要有个上限"。Flowable 默认有 128 个缓冲上限，超了就阻塞或丢弃。

### Q: 为什么 `blockingForEach` 是同步的，却说整个流程是"异步"的？

> 两个"异步"说的是不同的层：
> - AI 模型在远端异步生成 token（网络 I/O 异步）
> - `blockingForEach` 让当前线程**同步等待**这些 token
> - 前端 SSE 是**另一个线程**在异步轮询 Redis Stream
> 
> 整体是异步架构，但每个环节是同步等待的。这样设计是为了简化代码逻辑——不用回调地狱，用同步写法实现异步效果。

### Q: 流式输出中如何处理不同阶段的内容（analyst vs generator vs reviewer）？

> 在 `blockingForEach` 的 lambda 中通过 `event.author()` 区分来源 Agent：
> - `analyst` 的输出不推送给用户（内部思考过程）
> - `generator` 的输出逐 token 推送
> - `reviewer` 的输出缓冲后统一解析渲染
> 
> 维护一个 `currentPhase` 状态机，author 切换时推送 status 事件通知前端更新进度条。
