# AgentWrite 面试学习手册

> 适用目标：秋招 Java 后端 / AI 应用开发 / Agent 工程方向面试。  
> 项目定位：面向技术创作者的多智能体个性化创作工作台。  
> 学习方式：以简历五个亮点为主线，对照当前项目代码，准备“能讲清楚、能被追问、能落到实现”的项目表达。

---

## 1. 项目总览

### 1.1 一句话介绍

AgentWrite 是一个面向技术创作者的 AI 写作工作台。它不是简单的聊天应用，而是把一篇技术文章的创作拆成需求分析、正文生成、质量审校、智能配图、记忆增强和异步执行几个环节，通过多 Agent 编排、流式响应、个性化记忆和 MQ 任务系统，让长耗时 AI 写作任务更稳定、更个性化地完成。

面试中可以这样讲：

```text
我的项目是一个面向技术创作者的 AI 写作工作台。用户可以基于已有草稿进行续写、润色、生成摘要、质量审校和智能配图。系统后端使用 Spring Boot 3.4 和 Java 21，Agent 层基于 Google ADK，支持配置化装配多个 Agent；生成过程通过 Flowable 消费 ADK 事件流，并通过 Redis Stream + SSE 推送到前端；同时系统实现了一个类似 Mem0 思路的个性化记忆链路，通过 LLM 抽取用户背景和写作偏好，结合 Qdrant、BM25 和 Reranker 做检索增强；对于长耗时写作任务，则使用 RocketMQ + Transactional Outbox 做异步执行和可靠投递。
```

### 1.2 项目解决的核心问题

这个项目不是“调一个大模型接口生成文章”，而是在解决 AI 内容创作产品里几个真实工程问题：

1. **复杂写作任务如何拆解**
   - 一次高质量技术写作通常包含需求理解、正文生成、格式审查、配图等步骤。
   - 单 Prompt 很容易职责混乱，输出不可控。
   - 项目用多个 Agent 分工，并用工作流串联。

2. **长文本生成如何实时返回**
   - AI 写作耗时长，如果等完整结果返回，用户体验差且 HTTP 容易超时。
   - 项目通过 ADK 事件流、RxJava Flowable、Redis Stream 和 SSE 实现阶段化、增量化返回。

3. **大模型输出格式不稳定怎么办**
   - 技术文章 Markdown 容易出现标题粘连、列表断裂、表格格式错乱、代码块转义等问题。
   - 项目引入结构化块渲染和 CommonMark AST 规范化。

4. **生成内容如何体现用户个性化**
   - 只拼接历史对话会导致上下文膨胀、噪声变大。
   - 项目把历史对话抽取成长期记忆，检索后注入 Prompt。

5. **长耗时任务如何可靠执行**
   - 多 Agent 写作链路不适合放在同步 HTTP 请求中。
   - 项目引入 RocketMQ、Outbox、任务状态机、条件抢占和心跳补偿。

---

## 2. 项目代码地图

### 2.1 模块分层

```text
sutone-agent-bok-api
  对外 API 契约、DTO、Response

sutone-agent-bok-trigger
  HTTP Controller、RocketMQ Consumer、定时任务、安全认证

sutone-agent-bok-domain
  Agent 编排、AI 写作、记忆系统、文章、社交、限流等核心领域逻辑

sutone-agent-bok-infrastructure
  MyBatis DAO、Repository、Redis、Qdrant、Embedding、Reranker、MQ 发布适配

sutone-agent-bok-types
  公共枚举、异常、常量、消息 DTO
```

### 2.2 推荐阅读顺序

1. `sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/http/AiWritingController.java`
2. `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AiWritingService.java`
3. `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AgentWritingRunner.java`
4. `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/chat/ChatService.java`
5. `sutone-agent-bok-app/src/main/resources/agent/agent-writing.yml`
6. `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/armory/`
7. `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/memory/`
8. `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/content/service/cache/ArticleCacheService.java`
9. `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/content/service/social/SocialService.java`
10. `sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/job/AiTaskOutboxPublisher.java`
11. `sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/listener/AiTaskConsumer.java`

---

## 3. 核心链路总览

### 3.1 AI 写作异步链路

```text
前端提交任务
-> AiWritingController.submitTask
-> AiWritingService.submitTask
-> 限流 + 防重复提交锁
-> 查询草稿并构造 Prompt
-> 保存 ai_task，状态 PENDING
-> 保存 outbox_event，状态 NEW
-> 返回 taskId

Outbox 定时发布
-> AiTaskOutboxPublisher.publishPendingEvents
-> claimPublishable 原子抢占待发布事件
-> RocketMQTemplate.syncSend
-> markPublished / scheduleRetry / markFailed

Consumer 执行
-> AiTaskConsumer.onMessage
-> aiTaskRepository.claimTask 条件更新抢占任务
-> AiWritingService.executeTask
-> AgentWritingRunner.run
-> ChatService.handleMessageStream
-> ADK Runner 返回 Flowable<Event>
-> 事件写入 Redis Stream
-> 成功 markSuccess，失败 markFailed / markRetryingImmediate
-> 触发 MemoryManager.addAsync

前端接收
-> AiWritingController.stream
-> 读取 Redis Stream
-> ResponseBodyEmitter 推送 SSE
```

### 3.2 同步旧链路与 MQ 新链路的区别

项目里还能看到 `AiWritingService.generateStream` 这类直接执行并 SSE 返回的逻辑，但面试时主讲 MQ 链路。

- `generateStream`：更像早期同步/半同步实现，HTTP 请求中直接驱动 Agent。
- `executeTask`：当前更适合作为主线，Consumer 后台执行，Redis Stream 暂存过程事件，SSE 只负责读取事件。

面试中如果被问到“项目是否经历过演进”，可以这样讲：

```text
早期实现是提交后直接在 HTTP/SSE 请求里驱动 Agent 生成，优点是链路简单，但多 Agent 写作耗时长，连接断开、超时和失败恢复都不好处理。后来我把执行链路改造成任务化：提交接口只落库并返回 taskId，后台通过 RocketMQ 消费执行，执行过程写 Redis Stream，前端 SSE 只订阅任务事件。这样任务执行和用户连接解耦，也更容易做重试和补偿。
```

---

## 4. 简历点一：多 Agent 编排与配置化装配

### 4.1 需求背景

AI 技术写作不是单一步骤。一个完整写作任务至少包含：

- 理解用户需求和草稿上下文
- 判断任务类型：续写、润色、摘要、大纲、标签、质量检查
- 生成符合技术社区风格的 Markdown 内容
- 审查格式和文章质量
- 对适合配图的段落生成 draw.io 图表

如果把这些职责全部塞进一个 Prompt，会出现几个问题：

- Prompt 过长，职责混乱
- 模型既要分析又要生成又要审校，输出不稳定
- 后续新增配图、审校、检索工具时，需要频繁改代码
- 不同 Agent 的模型、工具和工作流很难独立演进

所以项目选择多 Agent 编排和配置化装配。

### 4.2 当前实现

核心代码：

- `sutone-agent-bok-app/src/main/java/cn/sutone/ai/config/AiAgentAutoConfig.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/armory/ArmoryService.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/armory/factory/DefaultArmoryFactory.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/armory/node/AiApiNode.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/armory/node/ChatModelNode.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/armory/node/AgentNode.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/armory/node/AgentWorkflowNode.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/armory/node/RunnerNode.java`
- `sutone-agent-bok-app/src/main/resources/agent/agent-writing.yml`
- `sutone-agent-bok-app/src/main/resources/agent/agent-draw-io.yml`

启动装配流程：

```text
ApplicationReadyEvent
-> AiAgentAutoConfig.onApplicationEvent
-> armoryService.acceptArmoryAgents
-> DefaultArmoryFactory.armoryStrategyHandler
-> RootNode
-> AiApiNode
-> ChatModelNode
-> AgentNode
-> AgentWorkflowNode
-> SequentialAgentNode / ParallelAgentNode / LoopAgentNode
-> RunnerNode
-> 注册 AiAgentRegisterVO 到 Spring 容器
```

`agent-writing.yml` 中定义了三个核心 Agent：

- `agent_writing_analyst`：分析草稿、任务类型、读者和输出约束
- `agent_writing_generator`：生成正文、大纲、摘要、标题、标签等
- `agent_writing_reviewer`：检查 Markdown 格式并输出终稿

工作流配置：

```yaml
agent-workflows:
  - type: sequential
    name: sequential_writing_process
    description: 标准技术写作流程：分析草稿 → 生成内容 → 质量审查
    sub-agents:
      - agent_writing_analyst
      - agent_writing_generator
      - agent_writing_reviewer

runner:
  agent-name: sequential_writing_process
```

### 4.3 为什么选择配置化 Agent 装配

候选方案对比：

**方案一：单 Agent + 一个大 Prompt**

优点：实现简单，适合 Demo。

缺点：

- 分析、生成、审校职责混在一起
- Prompt 越来越长，可维护性差
- 无法针对不同阶段选择不同工具和模型
- 很难在事件流中区分当前阶段

**方案二：代码里手写多个 Agent 调用**

优点：控制力强，流程清楚。

缺点：

- 每新增一个 Agent 或工具都要改 Java 代码
- 工作流无法通过配置调整
- 配图、PPT、写作等不同 Agent 应用难复用

**方案三：基于 YAML 的配置化装配**

优点：

- 模型、Prompt、MCP、Skills、工作流都可配置
- 支持 sequential、parallel、loop 等工作流
- 业务层只通过 agentId 使用 Runner
- 新增 Agent 应用时改配置即可

缺点：

- 装配链路复杂度更高
- 配置错误需要更好的校验和启动期报错
- 动态 Bean 注册需要注意命名冲突和生命周期

项目选择方案三，因为这个项目的核心不是单次聊天，而是多种 AI 创作能力的工作台，需要扩展性。

### 4.4 面试重点

需要掌握：

- Google ADK 在项目中的角色
- Spring AI `ChatModel` 和 ADK `LlmAgent` 的关系
- YAML 如何映射到 `AiAgentConfigTableVO`
- 装配树每个节点的职责
- 为什么使用 `SequentialAgent`
- draw.io 子流程如何从主写作链路中被调用

### 4.5 高频追问

**Q1：你说多 Agent 编排，具体多在哪里？**

答题思路：

```text
写作主链路有三个 Agent：分析、生成、审校。分析 Agent 负责理解任务和草稿，输出 writing_analysis；生成 Agent 基于 writing_analysis 和原始草稿生成 draft_content；审校 Agent 基于 draft_content 做 Markdown 格式修正并输出终稿。它们通过 ADK SequentialAgent 串起来，不是我在业务代码里简单 for 循环调用。
```

**Q2：Google ADK 做了什么，你自己做了什么？**

答题思路：

```text
ADK 提供 Agent、Workflow、Runner、Session 和 Event 流能力。我自己做的是配置化装配，把 YAML 中的模型、Prompt、MCP 工具、Skills 和工作流转换成 ADK 对象，并注册到 Spring 容器；业务侧根据 agentId 获取 Runner 执行，同时对 ADK 事件流做 author 分流、阶段映射、SSE 推送和业务落库。
```

**Q3：为什么不用 LangChain4j 或 Spring AI Advisors？**

答题思路：

```text
Spring AI 更偏模型调用和工具抽象，LangChain4j 也能做链式编排，但我的项目希望重点使用 Google ADK 的 Agent、Workflow 和 Runner 模型，尤其是 SequentialAgent、ParallelAgent、LoopAgent 这些工作流抽象。Spring AI 在这里主要作为 ChatModel 适配层，ADK 负责 Agent 运行，业务代码负责配置化装配和事件处理。
```

**Q4：配置化装配有什么风险？**

答题思路：

```text
主要风险是配置错误运行时才暴露，比如 runner.agentName 指向不存在的 Agent，MCP 初始化失败，Prompt outputKey 不一致。当前代码在 MCP 初始化失败时会跳过并打日志，但后续可以增强启动期校验，比如校验 subAgents 是否存在、runner 入口是否存在、agentId 是否重复。
```

### 4.6 相关八股文

- Spring Boot 配置绑定：`@EnableConfigurationProperties`
- Spring 生命周期：`ApplicationReadyEvent`
- Spring Bean 动态注册：`DefaultListableBeanFactory`
- 策略模式 / 责任链模式
- 工厂模式
- DDD 中领域服务与基础设施适配
- Agent 工作流：sequential、parallel、loop
- Tool Calling / MCP / Skills 的区别

---

## 5. 简历点二：响应式流处理 + Markdown 治理

### 5.1 需求背景

AI 写作任务有两个特点：

1. **生成耗时长**
   - 用户不能等几十秒甚至几分钟才看到结果。
   - 前端需要实时展示进度和内容。

2. **输出结构复杂**
   - 技术文章包含标题、列表、表格、代码块、公式、引用。
   - 大模型经常输出不规范 Markdown。

所以项目需要同时解决流式返回和格式治理。

### 5.2 当前实现

核心代码：

- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/chat/ChatService.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AgentWritingRunner.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/markdown/MarkdownBlockRenderer.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/markdown/MarkdownNormalizer.java`
- `sutone-agent-bok-infrastructure/src/main/java/cn/sutone/ai/infrastructure/redis/TaskEventPublisher.java`
- `sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/http/AiWritingController.java`

ADK 事件流入口：

```java
RunConfig runConfig = RunConfig.builder()
        .setStreamingMode(RunConfig.StreamingMode.SSE)
        .build();

return runner.runAsync(userId, sessionId, userMsg, runConfig)
```

`ChatService.handleMessageStream` 返回 `Flowable<Event>`。`AgentWritingRunner.run` 中使用：

```java
Flowable<Event> events = chatService.handleMessageStream(agentId, userId, sessionId, task.getPromptPayload());
events.blockingForEach(event -> { ... });
```

按 `event.author()` 做阶段映射：

```text
agent_writing_analyst   -> analyzing
agent_writing_generator -> generating
agent_writing_reviewer  -> reviewing
```

不同阶段处理方式：

- analyst：只推阶段状态，不进入最终正文
- generator：实时推 token 给前端
- reviewer：按行缓冲，拼装最终结果
- illustration：额外调用配图 Agent，生成 draw.io XML 并插回 Markdown

### 5.3 SSE 与 Redis Stream 的关系

当前 MQ 链路里，真正执行任务的是 Consumer，不是 HTTP 线程。Consumer 执行过程中通过 `TaskEventPublisher` 把事件写入 Redis Stream：

```text
AgentWritingRunner.run
-> eventConsumer.accept(event)
-> taskEventPublisher.publish(taskId, event)
-> Redis Stream: ai:task:stream:{taskId}
```

前端建立 SSE 连接后，`AiWritingController.stream` 循环读取 Redis Stream：

```text
readEvents(taskId, lastEventId)
-> ResponseBodyEmitter.send("data: ...\n\n")
```

这种设计的优点：

- AI 任务执行和前端连接解耦
- 前端晚连接也能读到 Stream 中已有事件
- 支持 `lastEventId` 做断点续读
- Redis Stream 设置 24 小时 TTL，避免无限增长

### 5.4 Markdown 治理方案

项目采用两层治理：

**第一层：结构化块渲染**

`MarkdownBlockRenderer` 支持模型输出一行一个 JSON：

```json
{"type":"md_heading","level":2,"text":"标题"}
{"type":"md_paragraph","text":"段落文本"}
{"type":"md_list","ordered":false,"items":["a","b"]}
{"type":"md_table","headers":["列1","列2"],"rows":[["a","b"]]}
{"type":"md_code","lang":"java","text":"代码"}
```

后端确定性渲染为标准 Markdown，减少模型自由输出带来的不稳定。

**第二层：CommonMark AST 规范化**

`MarkdownNormalizer` 做了：

- 移除全文 ```markdown 包裹
- 修复代码块外错误转义：`\*\*`、`\|`、`\#`、`\.`
- 修复有序列表断行
- 修复列表标记后缺空格
- 修复标题和正文粘连
- 修复标题与表格粘连
- 修复表格空行
- 使用 CommonMark Parser 解析 AST
- 调整标题层级，例如 `## 2.1 xxx` 转为 `### 2.1 xxx`
- 使用 MarkdownRenderer 重新渲染

### 5.5 方案对比

**方案一：前端直接渲染模型输出**

优点：后端简单。

缺点：模型输出不规范会直接污染前端编辑器和落库内容。

**方案二：纯正则修复 Markdown**

优点：实现快。

缺点：正则很难准确理解 Markdown 结构，容易误伤代码块、表格和标题。

**方案三：结构化协议 + AST 规范化**

优点：

- 结构化块让关键格式由代码保证
- CommonMark AST 能理解 Markdown 结构边界
- 最终落库内容更稳定

缺点：

- 对 Prompt 约束要求更高
- 结构化输出会牺牲一点模型自由度
- 流式阶段不能完全依赖最终 AST，需要区分实时展示和最终落库

项目采用方案三。

### 5.6 高频追问

**Q1：为什么用 SSE，不用 WebSocket？**

```text
当前场景主要是服务端向前端单向推送生成进度和内容，前端不需要在同一连接里频繁双向通信。SSE 基于 HTTP，浏览器原生支持 EventSource，协议简单，也支持断线重连和 lastEventId。WebSocket 更适合强双向实时交互，比如协同编辑或实时聊天。对于 AI 写作流式输出，SSE 更轻量。
```

**Q2：Flowable 在这里解决了什么问题？**

```text
ADK Runner 返回的是 Flowable<Event>，它适合表达持续产生的异步事件流。我们可以在 doOnNext 中收集回复，在 blockingForEach 中按事件逐个处理，并根据 author、partial、functionCalls 等信息做过滤和分流。相比一次性 List 返回，Flowable 能支持长文本增量生成。
```

**Q3：为什么审校阶段要缓冲？**

```text
生成阶段适合直接把 token 推给前端，因为用户需要实时看到内容。但审校阶段输出的是最终可落库内容，可能包含结构化 JSON 行或 Markdown 块，如果半行就处理容易解析失败，所以代码里对 reviewer 输出做行级缓冲，等换行或非 partial 事件再处理。
```

**Q4：Markdown 修复为什么不用纯正则？**

```text
纯正则很难区分代码块、表格、标题和普通文本，容易误修。我的做法是正则只做 AST 解析前的预处理，比如标题粘连、转义字符、列表缺空格；核心结构修复交给 CommonMark Parser 和 MarkdownRenderer，解析成 AST 后再统一渲染为标准 Markdown。
```

### 5.7 相关八股文

- SSE 原理：`text/event-stream`、EventSource、自动重连、lastEventId
- SSE vs WebSocket vs 长轮询
- RxJava：Observable、Flowable、背压、异步流
- HTTP 长连接和超时
- Redis Stream：消息 ID、范围读取、消费者组、TTL
- Markdown AST：Parser、Renderer、结构化解析
- 生产者消费者模型

---

## 6. 简历点三：个性化记忆系统

### 6.1 需求背景

技术创作者使用 AI 写作时，希望模型理解自己的背景，例如：

- 用户擅长 Java、后端、Agent、Redis、MQ
- 用户正在准备秋招
- 用户偏好面试导向、工程化表达
- 用户曾经写过哪些项目
- 用户喜欢怎样的写作风格

如果每次都让用户重复输入这些信息，体验很差。如果直接把全部历史对话拼进 Prompt，又会带来：

- 上下文越来越长，成本上升
- 噪声变多，相关内容反而被淹没
- 历史对话中很多内容是短期信息，不适合长期记忆

所以项目实现了独立的记忆系统。

### 6.2 当前实现

核心代码：

- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/memory/MemoryManager.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/memory/MemoryExtractor.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/memory/MemoryRetriever.java`
- `sutone-agent-bok-infrastructure/src/main/java/cn/sutone/ai/infrastructure/adapter/repository/MemoryRepository.java`
- `sutone-agent-bok-infrastructure/src/main/java/cn/sutone/ai/infrastructure/adapter/repository/QdrantVectorStore.java`
- `sutone-agent-bok-infrastructure/src/main/java/cn/sutone/ai/infrastructure/adapter/repository/MemoryEmbeddingClient.java`
- `sutone-agent-bok-infrastructure/src/main/java/cn/sutone/ai/infrastructure/adapter/repository/RerankerClient.java`

写入时机：

```text
AiWritingService.executeTask 成功
-> memoryManager.addAsync(userId, agentId, sessionId, messages)
```

### 6.3 记忆写入链路

```text
MemoryManager.addAsync
-> MemoryManager.add
-> 获取最近历史消息 getLastMessages
-> 拼接当前 user/assistant 消息
-> embedding 当前会话内容
-> Qdrant 搜索已有相似记忆 top-10
-> MemoryExtractor.extract 调 LLM 抽取候选记忆
-> MD5 hash 去重
-> 批量 embedding 候选记忆
-> 向量相似度 top-1 判断是否 UPDATE
-> MySQL 插入 memory_record
-> Qdrant 插入向量
-> memory_history 记录 ADD / UPDATE
```

其中去重有三层：

1. **LLM 级去重**
   - 把已有记忆传给 LLM，让它抽取时避免重复。

2. **Hash 去重**
   - 对记忆文本做 MD5。
   - 完全相同内容直接跳过。

3. **向量相似度更新**
   - `findUpdateTarget` 使用 Qdrant 搜索 top-1。
   - 相似度大于 `0.9` 时认为是已有记忆的更新，而不是新增。

### 6.4 记忆检索链路

```text
AiWritingService.buildPrompt
-> memoryManager.retrieveContext(userId, draftContent, 5)
-> MemoryRetriever.retrieveFormattedContext
-> MemoryRetriever.search
-> Redis search cache
-> embedding query
-> Qdrant semantic search
-> MySQL fulltext BM25 search
-> profile cache 注入
-> 分数融合：semantic + bm25 + recency + importance
-> Reranker 精排 Top-5
-> 格式化为 Prompt 上下文
```

记忆注入格式：

```text
【用户记忆上下文】
- 用户熟悉 Java 后端和 Redis
- 用户正在准备秋招面试
- 用户偏好工程化、面试导向的表达
```

### 6.5 为什么使用“向量召回 + BM25 + Reranker”

**只用向量召回的问题：**

- 语义相似强，但对精确关键词不敏感
- 技术名词、框架名、项目名可能被弱化
- embedding 不可用时没有降级方案

**只用 BM25 的问题：**

- 依赖关键词重合
- 同义表达召回能力差
- 例如“消息队列可靠投递”和“MQ 任务一致性”可能不完全命中

**增加 Reranker 的价值：**

- 向量和 BM25 属于粗召回
- Reranker 可以对 query 与候选记忆做更精细相关性判断
- 最终只取 Top-5，避免 Prompt 注入过多噪声

### 6.6 方案对比

**方案一：每次拼接最近 N 轮对话**

优点：简单。

缺点：上下文膨胀、噪声大、无法沉淀长期偏好。

**方案二：定期总结用户画像**

优点：Prompt 短。

缺点：画像更新不够细粒度，容易丢失具体事实。

**方案三：长期记忆条目化存储 + 检索增强**

优点：

- 每条记忆独立存储，可新增、更新、删除
- 检索时只取相关记忆
- 可结合向量、关键词、重要性、时间衰减

缺点：

- 实现复杂
- LLM 抽取可能产生错误记忆
- 需要去重、更新和补偿同步机制

项目采用方案三。

### 6.7 高频追问

**Q1：为什么不直接把历史对话都放进 Prompt？**

```text
因为历史对话会越来越长，成本高，而且很多对话只是短期上下文，不适合长期影响生成。我的做法是通过 LLM 从会话中抽取长期有效信息，比如技术背景、项目经历和写作偏好，再按当前草稿检索相关记忆注入 Prompt。
```

**Q2：记忆如何避免重复？**

```text
有三层去重。第一层是抽取时把已有记忆传给 LLM，让它避免重复抽取；第二层是对文本做 MD5 hash，完全相同的直接跳过；第三层是对候选记忆做 embedding，用 Qdrant 搜索 top-1，如果相似度大于 0.9，就更新已有记忆而不是新增。
```

**Q3：向量召回和 BM25 各自解决什么问题？**

```text
向量召回解决语义相似问题，比如表达不同但含义接近的偏好；BM25 解决关键词精确匹配问题，比如 Java、RocketMQ、Qdrant 这类技术词。两者融合能兼顾语义和关键词。
```

**Q4：Reranker 有必要吗？**

```text
有必要。向量和 BM25 是粗召回，可能拿到相关但不够贴合当前任务的记忆。Reranker 会重新评估 query 和候选记忆的相关性，最终只取 Top-5 注入 Prompt，减少噪声。
```

**Q5：错误记忆怎么办？**

```text
当前系统支持记忆列表、详情和逻辑删除，并有 memory_history 记录 ADD/UPDATE。后续可以增强为用户可编辑记忆，以及给记忆加置信度、来源会话和过期策略。面试中我会承认 LLM 抽取有误差，所以必须把记忆做成可观察、可删除、可追溯的独立数据，而不是直接写死到用户画像里。
```

### 6.8 相关八股文

- RAG 基本流程：切分、embedding、召回、重排、注入
- 向量数据库：collection、point、payload、topK、相似度
- 余弦相似度
- BM25 原理：TF、IDF、文档长度归一化
- Reranker 与 Embedding 模型区别
- 缓存命中率、缓存失效
- LLM 抽取结构化 JSON 的防御式解析
- 异步任务：`@Async`、线程池、异常处理

---

## 7. 简历点四：Redis 场景应用

### 7.1 需求背景

项目中 Redis 不只是缓存，而是覆盖多个业务场景：

- AI 调用频率限制
- AI 任务防重复提交
- AI 流式事件暂存
- 文章详情缓存
- 点赞、收藏关系和计数
- 热榜排序
- 记忆检索缓存

这部分很适合面试，因为能把 Redis 八股和真实业务结合起来。

### 7.2 已落地的 Redis 场景

核心代码：

- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ratelimit/RateLimitService.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AiWritingService.java`
- `sutone-agent-bok-infrastructure/src/main/java/cn/sutone/ai/infrastructure/redis/TaskEventPublisher.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/content/service/cache/ArticleCacheService.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/content/service/social/SocialService.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/memory/MemoryRetriever.java`

### 7.3 AI 接口限流

`RateLimitService` 使用 Redisson `RRateLimiter`：

```text
key: ai:limit:{userId}
rate: 5 次 / 分钟
```

选择原因：

- AI 接口成本高，必须限制滥用
- 用户级限流比全局限流更公平
- Redisson 封装了分布式令牌桶能力

### 7.4 AI 任务防重复提交

`AiWritingService.submitTask` 使用 Redisson 分布式锁：

```text
key: ai:task:lock:{userId}:{draftId}:{taskType}
```

防止用户短时间内连续点击同一个写作任务，造成多个重复任务进入 MQ。

### 7.5 Redis Stream 承接 AI 流式事件

`TaskEventPublisher` 使用 Redis Stream：

```text
key: ai:task:stream:{taskId}
TTL: 24h
```

写入字段：

```text
phase: analyzing / generating / reviewing / illustrating / done / error
type: status / token / result / done / error
content: 事件内容
```

选择 Redis Stream 的原因：

- Consumer 执行任务，SSE 只是读取事件，二者需要解耦
- Stream 有递增 ID，天然适合 lastEventId 续读
- 比 List 更适合按 ID 范围读取
- 比 Pub/Sub 更可靠，前端晚连接也能读到历史事件

### 7.6 文章详情缓存

`ArticleCacheService` 使用 Cache-Aside：

```text
先查 Redis
命中则返回
未命中则加分布式锁
再次检查缓存
查 DB
写 Redis
返回
```

缓存治理点：

- 缓存穿透：空值缓存 `__NULL__`，TTL 5 分钟
- 缓存雪崩：基础 TTL 30 分钟，加 0 到 10 分钟随机抖动
- 缓存击穿：`article:lock:{articleId}` 互斥重建
- 双重检查：拿锁后再查一次缓存，避免重复回源

注意：文章详情是静态信息缓存，浏览量、点赞数、收藏数、评论数等动态字段会在 `ArticleDomainService.patchDynamicFields` 中重新补齐，避免缓存中的动态计数过旧。

### 7.7 点赞、收藏和热榜

`SocialService` 中使用：

- `RSet`：文章点赞用户集合、用户点赞文章集合、收藏集合
- `RAtomicLong`：点赞数、收藏数计数器
- `RScoredSortedSet`：热榜排序

点赞流程：

```text
先写 MySQL 点赞关系
写成功说明不是重复点赞
更新文章 like_count
更新 Redis Set
更新 Redis AtomicLong
热榜分数 +3
```

浏览流程：

```text
文章详情查询
-> DB view_count +1
-> Redis ZSet 热榜分数 +1
```

热榜 key：

```text
leaderboard:view:daily:{yyyy-MM-dd}
```

### 7.8 方案对比

**为什么点赞先写 MySQL，再更新 Redis？**

因为 MySQL 是最终一致性的权威来源，点赞表可以通过唯一索引保证幂等。Redis 更适合作为读优化和计数加速。如果 Redis 更新失败，代码会删除相关 key，让后续从 DB 回源。

**为什么不用 Redis 作为点赞唯一真相？**

只用 Redis 性能高，但持久化、恢复、数据一致性和审计更麻烦。社区互动数据需要最终可靠落库，所以 MySQL 做主存储，Redis 做缓存和加速。

**为什么热榜用 ZSet？**

ZSet 天然支持 score 排序，浏览、点赞可以通过 `addScore` 增加热度，查询 TopN 可以用倒序范围查询，复杂度和实现都适合排行榜。

### 7.9 高频追问

**Q1：缓存穿透、雪崩、击穿分别是什么？你怎么处理？**

```text
穿透是大量请求访问不存在的数据，缓存和 DB 都查不到，我用空值缓存短时间缓存不存在结果。雪崩是大量 key 同时过期导致 DB 压力骤增，我用随机 TTL 打散过期时间。击穿是热点 key 过期后大量请求同时回源，我用 Redisson 分布式锁做互斥重建，并在拿锁后做双重检查。
```

**Q2：Redis 和 MySQL 数据不一致怎么办？**

```text
项目里 MySQL 是权威数据源，Redis 是缓存和加速层。点赞收藏先写 MySQL，成功后更新 Redis。如果 Redis 更新失败，就删除相关缓存 key，后续读取时从 DB 回源重建。文章详情更新后也应该清理详情缓存，避免旧数据长期存在。
```

**Q3：为什么不用本地缓存？**

```text
本地缓存访问更快，但多实例下会有一致性问题，也不适合记录点赞集合、热榜和流式事件。Redis 是集中式缓存，适合多实例共享状态。后续如果访问量更大，可以对文章详情增加本地 Caffeine + Redis 的二级缓存，但要处理失效通知。
```

**Q4：Redis Stream 和 RocketMQ 的区别？为什么两个都用？**

```text
RocketMQ 负责任务级异步执行，保证任务从提交到被 Worker 消费。Redis Stream 负责任务执行过程中的短期事件缓存，供 SSE 读取。一个是任务调度消息，一个是前端展示事件，它们解决的问题不一样。
```

### 7.10 相关八股文

- Redis 数据结构：String、Hash、Set、ZSet、Stream
- Redisson 分布式锁原理：watchdog、可重入、锁续期
- 令牌桶 vs 漏桶
- Cache-Aside 模式
- 缓存穿透、雪崩、击穿
- Redis 与 MySQL 一致性
- 热榜 ZSet 设计
- Redis Stream vs Pub/Sub vs List
- 缓存淘汰策略：LRU、LFU、TTL

---

## 8. 简历点五：RocketMQ 异步任务执行

### 8.1 需求背景

多 Agent 写作链路可能持续几十秒甚至更久，包括：

- 写作 Agent 串行执行
- 审校 Agent 修复格式
- 配图需求分析
- draw.io Agent 生成 XML
- 最终 Markdown 规范化
- 记忆异步抽取

如果全放在 HTTP 请求里，会有问题：

- 请求容易超时
- 用户关闭页面会影响任务感知
- 服务重启后任务状态难恢复
- 失败重试难做
- 多实例下重复执行难控制

所以项目把 AI 写作做成异步任务。

### 8.2 当前实现

核心代码：

- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AiWritingService.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/model/entity/AiTaskEntity.java`
- `sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/model/entity/OutboxEventEntity.java`
- `sutone-agent-bok-infrastructure/src/main/java/cn/sutone/ai/infrastructure/dao/IAiTaskDao.java`
- `sutone-agent-bok-infrastructure/src/main/java/cn/sutone/ai/infrastructure/dao/IOutboxEventDao.java`
- `sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/job/AiTaskOutboxPublisher.java`
- `sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/listener/AiTaskConsumer.java`
- `sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/job/AiTaskRecoveryJob.java`
- `sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/job/AiTaskRecoveryExecutor.java`

### 8.3 任务状态机

`AiTaskStatusVO`：

```text
PENDING  -> 待处理
RUNNING  -> 生成中
SUCCESS  -> 已完成
FAILED   -> 生成失败
RETRYING -> 重试中
```

可抢占状态：

```text
PENDING, RETRYING
```

Consumer 抢占 SQL：

```text
UPDATE ai_task
SET status = RUNNING, started_at = NOW(), heartbeat_at = NOW(), worker_id = ?
WHERE id = ?
AND status IN (PENDING, RETRYING)
AND (next_retry_at IS NULL OR next_retry_at <= NOW())
```

这个条件更新是防重复执行的关键。

### 8.4 Transactional Outbox

提交任务时：

```text
@Transactional
AiWritingService.submitTask
-> 保存 ai_task
-> 保存 outbox_event
-> 更新 outbox payload
-> 返回 taskId
```

为什么需要 Outbox：

如果直接在事务里保存任务后马上发 MQ，会出现两个不一致问题：

1. DB 保存成功，MQ 发送失败：任务永远没人执行。
2. MQ 发送成功，DB 事务回滚：Consumer 收到不存在的任务。

Outbox 的思路是：

```text
业务数据和待发送事件写入同一个本地事务
后台 Publisher 扫描 outbox_event
发送 MQ
发送成功后标记 PUBLISHED
失败则重试
```

这样至少能保证“只要任务落库成功，就一定有一条待投递事件可补偿”。

### 8.5 Outbox 发布可靠性

`AiTaskOutboxPublisher`：

```text
定时扫描 publishable 事件
-> claimPublishableBatch 批量标记 SENDING + publisherId
-> syncSend RocketMQ
-> 成功 markPublished
-> 失败 scheduleRetry
-> 超过最大重试 markFailed
```

`IOutboxEventDao.claimPublishableBatch` 使用 UPDATE 抢占，支持多实例 Publisher。

失败重试使用指数退避：

```text
min(10 * 2^retry, 600) 秒
```

还有 `recoverStaleSending`，用于恢复 Publisher 崩溃后卡在 SENDING 的事件。

### 8.6 Consumer 幂等执行

`AiTaskConsumer.onMessage`：

```text
收到 MQ 消息
-> aiTaskRepository.claimTask(taskId, workerId)
-> affectedRows == 0 说明已被抢占或不可执行，直接 ACK
-> 抢占成功才 executeTask
```

这样即使 RocketMQ 重复投递，同一个 taskId 也只有一个 Worker 能从 PENDING/RETRYING 改成 RUNNING。

### 8.7 心跳与补偿

`AiWritingService.executeTask` 执行时：

```text
每 5 秒 touchHeartbeat(taskId)
```

`AiTaskRecoveryJob` 每 30 秒扫描：

```text
RUNNING / RETRYING 且 heartbeat_at 超时的任务
```

`AiTaskRecoveryExecutor`：

```text
如果 retry_count 超限 -> FAILED
如果已有 pending outbox -> 跳过
否则 markRetryingImmediate
写新的 outbox_event
```

这解决 Worker 崩溃、进程重启、任务卡死等问题。

### 8.8 方案对比

**方案一：同步 HTTP 执行**

优点：简单。

缺点：超时、连接断开、失败恢复困难。

**方案二：本地线程池异步执行**

优点：比同步好，容易实现。

缺点：服务重启任务丢失，多实例扩展差，任务调度不可见。

**方案三：RocketMQ 异步任务**

优点：

- 任务提交和执行解耦
- 支持多 Worker 横向扩展
- MQ 可重试
- 配合任务表可以查询状态

缺点：

- 引入中间件复杂度
- 必须处理重复消费、幂等和一致性

**方案四：RocketMQ + Transactional Outbox**

优点：

- 解决 DB 任务记录和 MQ 事件之间的一致性
- 可以补偿投递失败
- 更适合本项目这种长耗时关键任务

缺点：

- 需要 outbox 表、Publisher、重试和清理机制

项目采用方案四。

### 8.9 高频追问

**Q1：为什么引入 MQ？**

```text
因为多 Agent 写作耗时长，同步 HTTP 请求容易超时，也很难做失败恢复。MQ 把任务提交和任务执行解耦，接口只需要落库并返回 taskId，后台 Consumer 异步执行。前端通过任务查询和 SSE 获取进度。
```

**Q2：如何避免 MQ 重复消费导致重复执行？**

```text
Consumer 收到消息后不会直接执行，而是先通过条件更新抢占任务，只有 PENDING 或 RETRYING 且到了 next_retry_at 的任务才能被更新为 RUNNING。如果 affectedRows 为 0，说明任务已经被其他 Worker 抢占或已经完成，当前消息直接 ACK，不执行。
```

**Q3：Outbox 和 RocketMQ 事务消息有什么区别？为什么用 Outbox？**

```text
RocketMQ 事务消息可以解决发送消息和本地事务的一致性，但会把本地事务检查逻辑和 MQ 强绑定。Outbox 是更通用的本地事务方案，任务和事件写在同一个数据库事务里，后台 Publisher 可靠投递。它对业务可观察性更好，也更容易做扫描、重试、告警和人工修复。
```

**Q4：任务执行一半 Worker 挂了怎么办？**

```text
执行过程中会定期更新 heartbeat_at。补偿 Job 会扫描心跳超时的 RUNNING 任务，如果没超过最大重试次数，就把任务标记为 RETRYING 并写入新的 Outbox 事件重新投递。如果超过最大重试次数，则标记 FAILED。
```

**Q5：MQ 能保证消息只消费一次吗？**

```text
不能。大多数 MQ 在工程上提供的是至少一次投递，所以业务必须做幂等。我的项目通过任务状态条件更新实现幂等抢占，通过任务状态机保证重复消息不会重复执行。
```

### 8.10 相关八股文

- MQ 解耦、削峰、异步化
- 至少一次、至多一次、恰好一次语义
- 消息重复消费与幂等
- 事务消息 vs Outbox
- 本地事务与分布式事务
- 消息重试、死信队列
- 消息积压处理
- 消费者水平扩展
- 定时补偿任务
- 状态机设计
- 数据库条件更新的并发控制

---

## 9. 面试重点总表

### 9.1 最应该讲深的 8 个点

1. **多 Agent 配置化装配**
   - 体现 AI Agent 工程能力。

2. **ADK Event 流 + author 分流**
   - 体现你理解 Agent 运行时事件，而不是只会调 API。

3. **Redis Stream + SSE 解耦流式展示**
   - 体现长任务实时反馈设计。

4. **Markdown 结构化治理**
   - 体现你处理了大模型输出不稳定问题。

5. **记忆抽取、去重、检索、注入链路**
   - 体现个性化和 RAG 能力。

6. **Qdrant + BM25 + Reranker 混合检索**
   - 体现 AI 应用质量优化。

7. **RocketMQ + Outbox 长任务可靠执行**
   - 体现后端工程可靠性。

8. **Redis 缓存、限流、Set/ZSet 场景化应用**
   - 体现中间件八股能落到业务。

### 9.2 项目开场白

```text
这个项目是一个面向技术创作者的 AI 写作工作台。和普通 ChatBot 不同，我把一次技术文章创作拆成需求分析、正文生成、格式审校和智能配图多个阶段，通过 Google ADK 的多 Agent 工作流进行编排。

工程上我重点解决了三个问题：第一，复杂 Agent 怎么配置化装配和执行；第二，长文本生成怎么通过事件流、Redis Stream 和 SSE 稳定返回给前端；第三，如何通过记忆系统和 MQ 任务系统，让生成内容更个性化，同时避免长请求超时和重复执行。

后端使用 Java 21、Spring Boot 3.4、RocketMQ、Redis、MySQL、Qdrant；AI 层使用 Google ADK 和 Spring AI 适配模型调用。
```

### 9.3 面试官可能的追问路径

**路径一：Agent 深挖**

```text
你说多 Agent，怎么编排的？
-> ADK SequentialAgent 怎么创建？
-> Prompt 和工具怎么配置？
-> outputKey 怎么传递上下文？
-> 如果新增一个 Agent，要改哪些地方？
-> ADK 和 Spring AI 分别做什么？
```

**路径二：流式响应深挖**

```text
模型输出是怎么实时到前端的？
-> Flowable<Event> 是什么？
-> author 分流怎么做？
-> Redis Stream 为什么放在中间？
-> SSE 断开怎么办？
-> 为什么不用 WebSocket？
```

**路径三：记忆系统深挖**

```text
记忆什么时候抽取？
-> LLM 怎么抽取结构化记忆？
-> 如何去重？
-> Qdrant 和 BM25 怎么融合？
-> Reranker 有什么作用？
-> 错误记忆怎么处理？
```

**路径四：MQ 可靠性深挖**

```text
为什么用 MQ？
-> 提交任务和发送消息如何保证一致？
-> Outbox 怎么工作？
-> 重复消费怎么办？
-> Worker 挂了怎么办？
-> 任务状态机怎么设计？
```

**路径五：Redis 八股深挖**

```text
Redis 用在哪些地方？
-> 文章缓存如何防穿透、雪崩、击穿？
-> 点赞收藏如何保证一致性？
-> 热榜怎么设计？
-> Redis Stream 和 MQ 的区别？
-> 分布式锁有什么问题？
```

---

## 10. 必背八股文清单

### 10.1 Java / Spring

- `@Transactional` 生效条件和失效场景
- Spring AOP 代理机制
- 为什么 `AiTaskRecoveryExecutor` 要独立 Component 才能让 `@Transactional` 生效
- `@Async` 原理和线程池配置
- Spring Boot 配置绑定
- Bean 生命周期和 `ApplicationReadyEvent`
- 动态注册 Bean 的原理
- 线程池核心参数
- 异常处理和日志设计

### 10.2 Redis

- Redis 常用数据结构及应用场景
- Set 为什么适合点赞去重
- ZSet 为什么适合排行榜
- Stream 为什么适合事件流
- 分布式锁原理、看门狗、锁续期、误删问题
- 令牌桶限流原理
- Cache-Aside 模式
- 缓存穿透、雪崩、击穿
- Redis 与 MySQL 一致性方案

### 10.3 RocketMQ / MQ

- MQ 的作用：异步、解耦、削峰
- RocketMQ 消息发送和消费流程
- 消息重复消费和幂等
- 消息丢失场景
- 事务消息原理
- Outbox 模式
- 消费失败重试和死信队列
- 消息积压排查
- Consumer Group 概念

### 10.4 AI / RAG / Agent

- Agent、Tool、Workflow、Memory 的概念
- Prompt 工程：角色、约束、输出格式、few-shot
- RAG 基本流程
- Embedding 模型和向量数据库
- 余弦相似度
- BM25 原理
- Reranker 的作用
- 混合检索为什么比单一检索更稳
- LLM 输出 JSON 的防御式解析
- 大模型幻觉和格式不稳定治理

### 10.5 HTTP / 流式通信

- SSE 原理
- SSE vs WebSocket
- HTTP 长连接
- EventSource 自动重连
- 流式响应如何处理异常
- 前端断开连接后服务端如何感知
- 轮询、长轮询、SSE、WebSocket 的对比

---

## 11. 风险点与修正表达

### 11.1 不要夸大 Google ADK

不要说：

```text
我自己实现了一个 Agent 框架。
```

建议说：

```text
我基于 Google ADK 的 Agent、Workflow、Runner 和 Event 流能力，实现了配置化装配、业务事件分流、流式返回和任务化执行。
```

### 11.2 不要把 Redis 说成只有缓存

建议说：

```text
Redis 在项目中有多种使用场景：限流、分布式锁、Redis Stream、文章缓存、点赞收藏集合、计数器、热榜 ZSet、记忆检索缓存。
```

### 11.3 不要把 MQ 说成只为削峰

建议说：

```text
这个项目引入 MQ 的核心原因不是单纯削峰，而是长耗时 AI 任务需要异步执行、状态可查询、失败可重试、Worker 挂掉可补偿。
```

### 11.4 不要把记忆系统说成简单 RAG

建议说：

```text
记忆系统不是简单文档 RAG，而是从用户对话中抽取长期偏好和背景，经过去重、更新、向量存储和混合检索后注入 Prompt。
```

### 11.5 注意旧链路和新链路

项目中存在 `generateStream` 和 `executeTask` 两套痕迹。面试主讲：

```text
提交任务 -> Outbox -> RocketMQ -> Consumer -> Redis Stream -> SSE
```

如果面试官看到旧代码，可以解释为：

```text
早期是同步流式执行，后续为了避免长请求超时和增强恢复能力，演进为 MQ 异步任务链路。
```

---

## 12. 最终复习路线

第一天：项目总链路

- 画出提交任务到 SSE 返回的完整链路。
- 能讲清每个模块的职责。

第二天：Agent 编排

- 看 `agent-writing.yml`。
- 看 `ArmoryService` 和各个 Node。
- 能讲清 YAML 如何变成 ADK Runner。

第三天：流式响应和 Markdown

- 看 `ChatService.handleMessageStream`。
- 看 `AgentWritingRunner.run`。
- 看 `MarkdownBlockRenderer` 和 `MarkdownNormalizer`。
- 能讲清 SSE、Redis Stream、CommonMark AST。

第四天：记忆系统

- 看 `MemoryManager`、`MemoryExtractor`、`MemoryRetriever`。
- 能讲清抽取、去重、召回、重排、注入。

第五天：Redis 和社区互动

- 看 `RateLimitService`、`ArticleCacheService`、`SocialService`、`TaskEventPublisher`。
- 能把缓存三大问题和业务代码对应起来。

第六天：RocketMQ 和可靠性

- 看 `AiWritingService.submitTask/executeTask`。
- 看 `AiTaskOutboxPublisher`、`AiTaskConsumer`、`AiTaskRecoveryJob`。
- 能讲清 Outbox、幂等、心跳补偿。

第七天：模拟面试

- 用本手册中的追问路径进行模拟。
- 每个问题控制在 1 到 2 分钟内回答。
- 先讲业务背景，再讲技术方案，再讲权衡和不足。

---

## 13. 你应该形成的项目能力标签

面试官听完这个项目后，应该感知到你的能力标签是：

```text
Java 后端基础扎实
熟悉 Spring Boot 分层和事务
理解 Redis/MQ 的真实业务落地
能做 AI Agent 工程化集成
理解 RAG 和个性化记忆系统
能处理长耗时任务的可靠执行
能把大模型输出不稳定问题工程化治理
具备从 Demo 到可用系统的演进意识
```

这就是你讲这个项目时最重要的目标。

---

## 14. 五个简历亮点的具体实现深挖

这一章用于解决一个核心问题：面试官不会只听你说“我用了某个技术”，他会继续问“具体怎么做的、为什么这么做、代码在哪里、异常怎么处理、还能怎么优化”。所以每个点都要按照以下结构准备：

```text
需求背景 -> 方案选型 -> 代码实现 -> 关键细节 -> 方案对比 -> 不足与优化
```

### 14.1 多 Agent 编排与配置化装配的具体实现

#### 14.1.1 这个方案在项目里解决什么问题

写作任务不是单次问答，而是一个多阶段工作流。用户输入一个草稿或写作诉求后，系统需要先理解用户到底想做什么，再生成内容，再审校格式和质量，还可能生成配图。

如果用一个 Agent 直接处理，Prompt 会非常长，而且职责混乱。模型可能一边分析一边生成，一边又尝试审校，最终输出不稳定。因此项目把一次写作拆成多个职责单一的 Agent：

```text
需求分析 Agent
-> 内容生成 Agent
-> 质量审校 Agent
-> 可选 draw.io 配图 Agent 子流程
```

#### 14.1.2 配置文件怎么定义 Agent

主写作配置在：

```text
sutone-agent-bok-app/src/main/resources/agent/agent-writing.yml
```

它大体分成几类配置：

```text
ai-api          定义模型服务地址、API Key、completion path
chat-model      定义模型名称、温度、超时时间、MCP 工具、Skills
agents          定义每个 LLM Agent 的 name、description、instruction、outputKey
agent-workflows 定义工作流类型和子 Agent 顺序
runner          定义最终入口 Agent
```

你面试时要强调：业务代码没有把三个 Agent 写死，而是通过 YAML 描述 Agent 拓扑。这样后续新增 Agent 或替换模型时，优先改配置，不需要大规模改业务代码。

#### 14.1.3 启动时怎么装配

启动入口：

```text
AiAgentAutoConfig
```

应用启动完成后监听 `ApplicationReadyEvent`，读取配置中的 Agent 应用表，然后交给：

```text
ArmoryService.acceptArmoryAgents
```

装配链路可以记成：

```text
配置对象 AiAgentConfigTableVO
-> ArmoryService
-> DefaultArmoryFactory
-> AiApiNode
-> ChatModelNode
-> AgentNode
-> AgentWorkflowNode
-> RunnerNode
-> 注册 AiAgentRegisterVO 到 Spring 容器
```

每个节点的职责：

```text
AiApiNode
  根据配置构建 OpenAiApi，负责模型服务地址、Key、接口路径。

ChatModelNode
  根据 OpenAiApi 构建 OpenAiChatModel，并挂载 MCP 工具和 Skills。

AgentNode
  把 YAML 中的 agents 配置转成 ADK LlmAgent。
  每个 Agent 都有自己的 instruction、description、outputKey。

AgentWorkflowNode
  根据 workflow type 选择 sequential、parallel 或 loop。
  写作主链路使用 sequential。

SequentialAgentNode
  把需求分析、内容生成、质量审校三个 Agent 串起来。

RunnerNode
  根据 runner.agentName 找到入口 Agent，创建 ADK Runner，并注册到 Spring。
```

#### 14.1.4 运行时业务代码怎么调用

业务侧并不直接 new Agent，而是通过 `agentId` 获取已注册的 Runner。核心入口在：

```text
ChatService.handleMessageStream(agentId, userId, sessionId, message)
```

执行过程：

```text
根据 agentId 获取 AiAgentRegisterVO
-> 从 registerVO 中拿到 Runner
-> 创建或获取 ADK Session
-> 构造 Content userMsg
-> runner.runAsync(..., RunConfig.StreamingMode.SSE)
-> 返回 Flowable<Event>
```

这就是简历里“配置驱动的 Agent 装配机制”的代码依据。

#### 14.1.5 draw.io 子流程怎么接入

配图不是主写作链路里的固定一步，而是由写作结果触发的子流程。

主链路生成/审校出 Markdown 后，`AgentWritingRunner` 会判断是否启用配图：

```text
enableIllustration = true
-> 调用 illustration analysis Agent 分析哪些段落适合配图
-> 对每个配图点调用 draw.io Agent
-> draw.io Agent 输出 XML
-> 把 XML 以 draw.io 代码块形式插回 Markdown
```

这点面试中要讲成“子流程”，不要讲成“主 Agent 里顺手生成图片”。因为它体现了工作流拆分思想：正文生成和配图生成职责分离。

#### 14.1.6 方案优点

```text
扩展性好：新增 Agent 应用主要改 YAML。
职责清晰：分析、生成、审校、配图各自独立。
可观测性强：事件流里可以通过 author 区分当前阶段。
复用性好：装配树支持 sequential、parallel、loop，不只服务写作场景。
```

#### 14.1.7 面试口述版

```text
这个项目里我没有把 Agent 写死在业务代码里，而是做了一个配置化装配层。比如写作应用的 YAML 里会定义模型 API、ChatModel、多个 LLM Agent、工作流以及 Runner。应用启动后，ArmoryService 会读取这些配置，经过 AiApiNode、ChatModelNode、AgentNode、AgentWorkflowNode 和 RunnerNode，依次构建 OpenAiApi、OpenAiChatModel、ADK LlmAgent、SequentialAgent 和 Runner，最后注册到 Spring 容器。

业务执行时只需要根据 agentId 拿到 Runner，调用 ADK 的 runAsync 获取事件流。写作主链路里，我把需求分析、正文生成和质量审校拆成三个 Agent，并通过 SequentialAgent 串起来。配图能力是一个独立子流程，先分析文章中适合配图的位置，再调用 draw.io Agent 生成 XML 并回插到 Markdown。这样做的好处是职责清晰，后续替换模型、新增 Agent 或调整流程主要改配置，不需要大规模改业务代码。
```

### 14.2 响应式流处理与 Markdown 治理的具体实现

#### 14.2.1 为什么要做流式处理

AI 长文本生成可能持续几十秒。用户如果只能等完整结果，体验很差，而且 HTTP 请求也容易超时。因此项目用流式处理把生成过程拆成连续事件：

```text
分析阶段事件
生成阶段 token
审校阶段内容
配图阶段进度
完成事件
错误事件
```

#### 14.2.2 ADK 事件流怎么进入项目

核心代码：

```text
ChatService.handleMessageStream
```

这个方法最终调用：

```text
runner.runAsync(userId, sessionId, userMsg, runConfig)
```

其中 `runConfig` 设置了：

```text
RunConfig.StreamingMode.SSE
```

返回值是：

```text
Flowable<Event>
```

这说明底层 ADK 会持续产生事件，项目用 RxJava Flowable 来消费这些事件。

#### 14.2.3 AgentWritingRunner 如何处理事件

`AgentWritingRunner.run` 是 AI 写作流处理的核心。它拿到 `Flowable<Event>` 后使用：

```text
events.blockingForEach(event -> { ... })
```

对每个事件做以下处理：

```text
1. 过滤空事件和无内容事件
2. 通过 event.author() 判断来自哪个 Agent
3. 通过 event.partial() 判断是否是流式片段
4. 从 event.content().parts() 提取文本
5. 根据阶段构造 AiWritingStreamEventVO
6. 调用 eventConsumer 推送出去
7. 生成完整最终内容后返回 AiWritingResultVO
```

#### 14.2.4 author 分流怎么做

项目把 ADK 事件里的 `author` 映射成业务阶段：

```text
agent_writing_analyst   -> analyzing
agent_writing_generator -> generating
agent_writing_reviewer  -> reviewing
agent_draw_io           -> illustrating
```

这样前端不是只能看到一串 token，而是能知道当前 AI 正在分析、生成、审校还是配图。

#### 14.2.5 为什么不同阶段处理方式不同

```text
分析阶段
  主要用于让模型理解任务，不作为最终正文。
  所以更多推送进度状态。

生成阶段
  用户希望实时看到正文增长。
  所以 token 可以增量推送。

审校阶段
  输出更接近最终落库内容，需要结构完整。
  所以按行缓冲，避免半行 JSON 或半个 Markdown 块被提前解析。

配图阶段
  draw.io XML 通常较长且结构敏感。
  所以更适合完成后作为代码块插入。
```

#### 14.2.6 Redis Stream + SSE 怎么配合

MQ 链路中，执行任务的是 Consumer，不是 HTTP 请求线程。因此需要一个中间层保存生成过程事件。

项目使用：

```text
TaskEventPublisher -> Redis Stream -> AiWritingController.stream -> SSE
```

执行线程写入 Redis Stream：

```text
ai:task:stream:{taskId}
```

SSE 接口读取 Redis Stream，并通过 `ResponseBodyEmitter` 推给前端。

这种设计的关键价值：

```text
任务执行和前端连接解耦。
前端断开后，任务仍然继续执行。
前端重连后，可以通过 lastEventId 继续读取后续事件。
Redis Stream 比 Pub/Sub 更适合这个场景，因为它能保存短期历史事件。
```

#### 14.2.7 MarkdownBlockRenderer 具体做什么

有些模型输出被约束成“一行一个 JSON 块”，例如：

```text
{"type":"md_heading","level":2,"text":"标题"}
{"type":"md_paragraph","text":"段落"}
{"type":"md_code","lang":"java","text":"System.out.println();"}
```

`MarkdownBlockRenderer` 会把这些结构化块渲染成标准 Markdown：

```text
md_heading -> ## 标题
md_paragraph -> 普通段落
md_list -> - item 或 1. item
md_table -> Markdown 表格
md_code -> ```java 代码块
md_quote -> > 引用
```

这个设计的价值是：让关键格式由代码确定，而不是完全依赖模型自由发挥。

#### 14.2.8 MarkdownNormalizer 具体做什么

`MarkdownNormalizer` 是最终落库前的格式治理器。它大致分三步：

```text
第一步：预处理
  去掉全文 ```markdown 包裹
  修复标题粘连
  修复列表缺空格
  修复表格和标题粘连
  修复代码块外错误转义

第二步：CommonMark AST 解析
  使用 Parser 把 Markdown 解析成文档树
  通过 AST 识别标题、列表、表格、代码块等结构

第三步：重新渲染
  用 MarkdownRenderer 输出规范 Markdown
  如果解析失败，则降级返回预处理结果
```

#### 14.2.9 面试口述版

```text
写作链路里 ADK Runner 会返回 Flowable<Event>，我在 AgentWritingRunner 里逐个消费事件。每个事件都有 author、partial 和 content，我根据 author 判断当前来自分析 Agent、生成 Agent 还是审校 Agent。分析阶段主要推进度，生成阶段把 token 增量推给前端，审校阶段为了保证结构完整会先按行缓冲，最终再落库。

因为执行任务的线程是 MQ Consumer，而不是 SSE 请求线程，所以我没有让 Consumer 直接持有前端连接，而是把过程事件写入 Redis Stream。前端通过 SSE 接口读取 Redis Stream，这样前端断开后任务仍然能继续执行，重连后也可以基于 lastEventId 继续消费事件。

Markdown 治理上，我做了两层处理：第一层是结构化块渲染，让模型输出 md_heading、md_table、md_code 这类 JSON 行，后端确定性渲染成 Markdown；第二层是 CommonMark AST 规范化，对标题粘连、列表缩进、表格断裂、代码块转义等问题做最终修复，提升前端渲染和落库内容的稳定性。
```

### 14.3 个性化记忆系统的具体实现

#### 14.3.1 记忆系统的核心目标

记忆系统要解决的是“长期个性化上下文”问题。它不是简单保存聊天记录，而是把聊天记录中长期有效的信息抽取出来，例如：

```text
用户的技术背景
用户的项目经历
用户的写作偏好
用户正在准备的目标
用户对内容风格的要求
```

#### 14.3.2 为什么不能直接拼历史对话

直接拼历史对话的问题：

```text
上下文长度不可控。
历史消息噪声大。
很多短期对话不应该长期影响后续生成。
无法对单条记忆做删除、更新、追踪。
```

所以项目实现的是条目化记忆，而不是历史消息拼接。

#### 14.3.3 记忆什么时候写入

写入入口在：

```text
AiWritingService.executeTask
```

任务成功后调用：

```text
memoryManager.addAsync(userId, agentId, sessionId, messages)
```

也就是说，记忆抽取不是阻塞主写作链路的同步步骤，而是在任务成功后异步执行。这样可以避免 LLM 抽取记忆影响用户拿到写作结果的速度。

#### 14.3.4 记忆写入具体步骤

完整链路：

```text
1. 收集当前会话中的 user / assistant 消息
2. 查询最近历史消息，补足上下文
3. 对当前会话内容做 embedding
4. 使用 Qdrant 搜索已有相似记忆
5. 把当前消息和已有记忆一起交给 LLM
6. LLM 输出候选记忆 JSON
7. 对候选记忆做文本 hash 去重
8. 对候选记忆做 embedding
9. 用向量相似度判断是新增还是更新
10. 写入 MySQL memory_record
11. 写入 Qdrant 向量点
12. 写 memory_history 记录变更历史
```

#### 14.3.5 三层去重机制

第一层：抽取前去重。

```text
先召回已有相似记忆，把它们作为 existing memories 提供给 LLM。
让 LLM 在抽取时避免重复生成已有记忆。
```

第二层：hash 去重。

```text
对候选记忆内容做 MD5。
如果完全相同的 hash 已存在，直接跳过。
```

第三层：向量相似度更新。

```text
对候选记忆 embedding。
到 Qdrant 中搜索相似记忆。
如果 top-1 相似度超过阈值，例如 0.9，则认为是已有记忆的更新。
否则插入新记忆。
```

#### 14.3.6 记忆检索具体步骤

记忆注入发生在构造写作 Prompt 时：

```text
AiWritingService.buildPrompt
-> memoryManager.retrieveContext(userId, draftContent, 5)
```

检索链路：

```text
1. 根据 query hash 查询 Redis 搜索缓存
2. 缓存未命中则对 query 做 embedding
3. 使用 Qdrant 做语义召回
4. 使用 MySQL FULLTEXT / BM25 做关键词召回
5. 读取用户画像缓存作为补充候选
6. 按 semanticScore、bm25Score、importance、recency 融合打分
7. 调用 Reranker 对候选记忆精排
8. 取 Top-5 格式化成 Prompt 上下文
```

#### 14.3.7 为什么需要混合检索

```text
Qdrant 向量召回
  优点是语义召回强，表达不同但含义相似也能召回。
  缺点是对 Java、RocketMQ、Qdrant 这种精确技术词不一定足够敏感。

BM25 关键词召回
  优点是关键词命中强，适合技术名词和项目名。
  缺点是同义表达召回弱。

Reranker
  粗召回阶段可能召回相关但不够贴合的记忆。
  Reranker 会重新判断 query 和候选记忆的相关性，减少注入噪声。
```

#### 14.3.8 面试口述版

```text
我的记忆系统不是简单保存历史聊天记录，而是做成了抽取式长期记忆。AI 写作任务成功后，我会异步把本轮 user 和 assistant 消息交给 MemoryManager。它会先补最近历史上下文，然后对当前会话做 embedding，到 Qdrant 里召回已有相似记忆，再把当前消息和已有记忆一起交给 LLM，让 LLM 抽取长期有效的信息，比如用户技术背景、写作偏好、项目经历。

抽取后我做了三层去重：先让 LLM 参考已有记忆避免重复；再用内容 MD5 hash 过滤完全重复；最后对候选记忆做 embedding，用向量相似度判断是否应该更新已有记忆。最终记忆会同时写 MySQL 和 Qdrant，并记录 memory_history。

检索时，我会根据当前草稿内容去取 Top-5 相关记忆。召回不是只用向量，而是 Qdrant 语义召回加 BM25 关键词召回，再融合重要性和时间衰减，最后用 Reranker 精排。这样可以兼顾语义相似和技术关键词精确命中，避免把无关记忆注入 Prompt。
```

### 14.4 Redis 场景应用的具体实现

#### 14.4.1 Redis 在项目里的角色

项目里 Redis 不是单纯缓存，而是按业务场景使用多种数据结构：

```text
RRateLimiter     用户级 AI 接口限流
RLock            AI 任务防重复提交、文章缓存互斥重建
Redis Stream     AI 任务过程事件缓冲
String           文章详情 JSON 缓存、空值缓存
RSet             点赞、收藏、关注关系
RAtomicLong      点赞数、收藏数计数器
RScoredSortedSet 热榜排序
普通缓存         记忆检索结果缓存、用户画像缓存
```

#### 14.4.2 AI 接口限流怎么实现

核心代码：

```text
RateLimitService
```

逻辑：

```text
1. 根据 userId 构造 key：ai:limit:{userId}
2. 获取 Redisson RRateLimiter
3. 初始化令牌桶规则，例如每分钟 5 次
4. 每次请求调用 tryAcquire
5. 获取令牌成功则放行，失败则拒绝
```

面试表达：

```text
AI 接口有成本和延迟，所以不能无限调用。我用 Redisson 的 RRateLimiter 做用户级令牌桶限流，避免单个用户短时间内大量提交写作任务。
```

#### 14.4.3 AI 任务防重复提交怎么实现

核心代码：

```text
AiWritingService.submitTask
```

逻辑：

```text
1. 根据 userId、draftId、taskType 构造锁 key
2. 使用 Redisson RLock 尝试加锁
3. 拿不到锁说明同一草稿同一任务正在提交，直接拒绝或提示稍后重试
4. 拿到锁后再创建 ai_task 和 outbox_event
5. finally 中释放锁
```

这个锁解决的是“用户连续点击提交按钮导致重复任务入队”的问题。

#### 14.4.4 文章详情缓存怎么实现

核心代码：

```text
ArticleCacheService.getArticleDetail
```

流程：

```text
1. 根据 articleId 构造缓存 key
2. 查 Redis
3. 如果命中正常文章 JSON，反序列化返回
4. 如果命中空值标记 __NULL__，直接返回 null
5. 如果未命中，获取 article:lock:{articleId} 分布式锁
6. 拿到锁后再次查 Redis，做双重检查
7. 仍未命中则查 MySQL
8. MySQL 不存在，写空值缓存，短 TTL
9. MySQL 存在，写文章 JSON，TTL 加随机抖动
10. 释放锁
```

缓存三大问题对应：

```text
缓存穿透：空值缓存。
缓存雪崩：随机过期时间。
缓存击穿：互斥锁重建热点缓存。
```

#### 14.4.5 点赞收藏怎么实现

核心代码：

```text
SocialService.like
SocialService.unlike
SocialService.favorite
SocialService.unfavorite
```

点赞流程：

```text
1. 先写 MySQL 点赞关系表
2. 如果 saveLike 返回 false，说明已经点过赞，直接返回
3. MySQL 文章 like_count +1
4. Redis 文章点赞用户 Set 加 userId
5. Redis 用户点赞文章 Set 加 articleId
6. Redis AtomicLong 点赞数 +1
7. 热榜分数 +3
8. 如果 Redis 更新失败，删除相关 Redis key，后续从 DB 回源
```

为什么先写 MySQL：

```text
MySQL 是权威数据源，可以通过唯一索引保证幂等。
Redis 是缓存和加速层，失败时可以删除 key，让后续回源重建。
```

#### 14.4.6 热榜怎么实现

核心代码：

```text
SocialService.recordView
SocialService.incrHeatScore
SocialService.getTopN
```

数据结构：

```text
RScoredSortedSet<Long>
```

key：

```text
leaderboard:view:daily:{yyyy-MM-dd}
```

计分规则：

```text
浏览 +1
点赞 +3
```

查询 TopN：

```text
valueRangeReversed(0, n - 1)
```

#### 14.4.7 面试口述版

```text
Redis 在我的项目中不是只做缓存，而是结合业务用了多种结构。AI 写作入口用 RRateLimiter 做用户级令牌桶限流，用 RLock 防止同一用户同一草稿重复提交任务。AI 任务执行过程中的生成事件用 Redis Stream 缓冲，SSE 接口从 Stream 读取并推给前端。

内容社区侧，文章详情使用 Cache-Aside，先查 Redis，未命中后用分布式锁互斥重建，空值缓存防穿透，随机 TTL 防雪崩。点赞收藏使用 MySQL 作为权威数据源，Redis Set 缓存用户关系，AtomicLong 缓存计数，ZSet 做热榜排序。Redis 更新失败时删除相关 key，让后续读取从 DB 回源，保证最终一致性。
```

### 14.5 RocketMQ 异步任务执行的具体实现

#### 14.5.1 为什么写作任务要异步化

多 Agent 写作是长耗时任务。同步 HTTP 执行会有几个问题：

```text
请求可能超时。
用户关闭页面后链路不好恢复。
服务重启可能导致任务中断且不可见。
失败重试难做。
多实例下重复执行难控制。
```

所以项目采用：

```text
HTTP 提交任务
-> DB 记录任务
-> Outbox 记录待发送事件
-> RocketMQ 投递执行消息
-> Consumer 后台执行
-> Redis Stream 记录过程事件
-> SSE 读取事件展示
```

#### 14.5.2 提交任务怎么实现

核心代码：

```text
AiWritingService.submitTask
```

流程：

```text
1. 用户提交 draftId、taskType、enableIllustration
2. RateLimitService 做用户级限流
3. Redisson 锁防止重复提交
4. 校验草稿存在且归属当前用户
5. buildPrompt 构造最终 Prompt，并注入记忆上下文
6. 创建 AiTaskEntity，状态 PENDING
7. 插入 ai_task 表
8. 创建 OutboxEventEntity，状态 NEW
9. 插入 outbox_event 表
10. 更新 outbox payload，携带 taskId 等信息
11. 提交事务
12. 返回 taskId
```

这里 `@Transactional` 很关键：任务记录和 outbox 事件必须在同一个本地事务里保存。

#### 14.5.3 Outbox Publisher 怎么实现

核心代码：

```text
AiTaskOutboxPublisher
IOutboxEventDao
```

流程：

```text
1. 定时任务触发 publishPendingEvents
2. recoverStaleSending 恢复长时间卡在 SENDING 的事件
3. claimPublishableBatch 用 UPDATE 抢占一批 NEW/RETRYING 事件
4. findClaimedByPublisherId 查询当前 Publisher 抢到的事件
5. RocketMQTemplate.syncSend 同步发送消息
6. 成功则 markPublished
7. 失败则 scheduleRetry
8. 超过最大重试次数则 markFailed
```

为什么要抢占 Outbox 事件：

```text
如果部署多个应用实例，多个 Publisher 都可能扫描 outbox_event。
通过 UPDATE status = SENDING, publisher_id = 当前实例 ID 的方式，可以让一条事件只被一个 Publisher 负责发送。
```

#### 14.5.4 Consumer 怎么防重复执行

核心代码：

```text
AiTaskConsumer
IAiTaskDao.claimTask
```

Consumer 收到 MQ 消息后，不直接执行，而是先抢占任务：

```text
UPDATE ai_task
SET status = RUNNING, worker_id = ?, heartbeat_at = NOW()
WHERE id = ?
AND status IN (PENDING, RETRYING)
AND (next_retry_at IS NULL OR next_retry_at <= NOW())
```

如果影响行数为 1，说明抢占成功，可以执行。

如果影响行数为 0，说明任务已经被其他 Worker 抢占、已经完成、已经失败，或者还没到重试时间，当前消息直接 ACK。

这就是幂等的核心。

#### 14.5.5 executeTask 怎么执行 AI 写作

核心代码：

```text
AiWritingService.executeTask
AgentWritingRunner.run
```

执行流程：

```text
1. 查询任务详情
2. 创建 heartbeat 定时任务，每 5 秒 touchHeartbeat
3. 调用 AgentWritingRunner.run
4. Runner 内部消费 ADK Flowable<Event>
5. 每个事件通过 TaskEventPublisher 写 Redis Stream
6. 如果成功，markSuccess 保存最终内容
7. 发布 done 事件
8. 任务成功后异步抽取记忆
9. 如果失败，根据异常类型 markRetryingImmediate 或 markFailed
10. 停止 heartbeat
```

#### 14.5.6 心跳补偿怎么实现

核心代码：

```text
AiTaskRecoveryJob
AiTaskRecoveryExecutor
```

流程：

```text
1. 定时扫描 heartbeat_at 超时的 RUNNING / RETRYING 任务
2. 查询任务 retry_count
3. 如果超过最大重试次数，标记 FAILED
4. 如果还存在 pending outbox，说明已经有重投事件，跳过
5. 否则 markRetryingImmediate
6. 创建新的 outbox_event
7. 后续由 Outbox Publisher 再次投递 MQ
```

这个机制解决 Worker 崩溃或进程重启导致任务卡死的问题。

#### 14.5.7 面试口述版

```text
多 Agent 写作任务比较耗时，所以我把它设计成异步任务。提交接口只做参数校验、限流、防重复提交、构造 Prompt，然后在一个本地事务里保存 ai_task 和 outbox_event，任务状态是 PENDING，接口立即返回 taskId。

之后 Outbox Publisher 定时扫描 outbox_event，通过 UPDATE 把可发布事件抢占为 SENDING，再发送 RocketMQ，成功后标记 PUBLISHED，失败就按退避策略重试。Consumer 收到消息后也不会直接执行，而是通过条件更新把 PENDING 或 RETRYING 任务抢占为 RUNNING，只有抢占成功的 Worker 才执行任务，这样可以处理 MQ 重复投递。

执行过程中，Consumer 调用 AgentWritingRunner 消费 ADK 事件流，并把过程事件写入 Redis Stream，前端 SSE 从 Redis Stream 读取。任务执行期间每 5 秒更新 heartbeat，如果 Worker 挂掉，补偿 Job 会扫描心跳超时任务，重新标记为 RETRYING 并写入新的 Outbox 事件，最终再次投递执行。
```

---

## 15. 面试口述稿汇总

### 15.1 项目 1 分钟介绍

```text
AgentWrite 是我独立开发的一个多智能体个性化创作工作台，主要面向技术创作者。它支持基于草稿进行续写、润色、摘要、质量审校和智能配图。后端使用 Java 21 和 Spring Boot 3.4，Agent 层基于 Google ADK。

这个项目的重点不是简单调用大模型，而是把复杂写作任务工程化。我把一次写作拆成需求分析、正文生成、质量审校和配图子流程，通过配置化多 Agent 工作流编排；生成过程通过 ADK Event 流、Flowable、Redis Stream 和 SSE 实时返回给前端；同时实现了个性化记忆系统，从历史会话中抽取用户背景和写作偏好，结合 Qdrant、BM25 和 Reranker 检索后注入 Prompt；长耗时任务则通过 RocketMQ 和 Transactional Outbox 异步执行，避免 HTTP 超时并支持重试补偿。
```

### 15.2 项目 3 分钟介绍

```text
我的项目叫 AgentWrite，是一个面向技术创作者的 AI 写作工作台。用户可以输入草稿，然后让系统续写、润色、生成摘要、做质量审校，也可以根据文章内容生成 draw.io 配图。后端采用 DDD 风格分层，主要分为 trigger、domain、infrastructure 几层，trigger 负责 Controller、MQ Consumer 和定时任务，domain 负责 Agent 编排、写作、记忆、文章和社交业务，infrastructure 负责 MySQL、Redis、Qdrant、Reranker 和 RocketMQ 的适配。

第一个核心点是多 Agent 编排。我基于 Google ADK 做了配置化装配机制，在 YAML 里配置模型、Prompt、MCP 工具、Agent 和工作流。应用启动后，ArmoryService 会经过 AiApiNode、ChatModelNode、AgentNode、AgentWorkflowNode 和 RunnerNode，把配置装配成 ADK Runner。写作主链路是 SequentialAgent，包含需求分析、内容生成和质量审校三个 Agent。配图是独立子流程，会先分析适合配图的位置，再调用 draw.io Agent 生成 XML 回插到 Markdown。

第二个核心点是流式处理。ADK Runner 返回 Flowable<Event>，我在 AgentWritingRunner 里按 event.author 做阶段分流。分析阶段推送进度，生成阶段通过 token 增量展示，审校阶段先缓冲再作为最终内容落库。因为任务是 MQ Consumer 后台执行，不直接持有前端连接，所以过程事件会先写 Redis Stream，前端 SSE 接口读取 Stream 后推送给浏览器。

第三个核心点是个性化记忆。我没有简单拼接历史对话，而是任务成功后异步抽取长期记忆。MemoryManager 会收集当前会话和最近历史消息，调用 LLM 抽取用户技术背景、项目经历和写作偏好，再通过 hash 和向量相似度去重，最后写 MySQL 和 Qdrant。检索时结合 Qdrant 向量召回、BM25 关键词召回和 Reranker 精排，取 Top-5 注入 Prompt。

第四个核心点是可靠异步任务。提交写作任务时，系统在同一个事务中写 ai_task 和 outbox_event，然后返回 taskId。Outbox Publisher 扫描事件发送 RocketMQ。Consumer 收到消息后先用条件更新抢占任务，只有抢占成功才执行，从而避免重复消费导致重复执行。执行过程中写 heartbeat，如果 Worker 挂掉，补偿 Job 会把超时任务重新投递。
```

### 15.3 项目 5 分钟深度介绍

```text
如果展开讲，我会把这个项目总结为“AI 写作任务从 Demo 到工程化”的过程。最开始一个 AI 写作功能可能只是前端传草稿，后端拼 Prompt 调模型，然后返回结果。但真正做成工作台后，会遇到复杂流程编排、长文本流式返回、输出格式治理、个性化上下文、长任务可靠执行这些问题。

在编排上，我没有用单一大 Prompt，而是基于 Google ADK 拆成多个 Agent。写作主流程包括 analyst、generator、reviewer 三个 Agent，分别负责需求分析、内容生成和质量审校。为了避免 Agent 写死在代码里，我做了一个配置化装配层。YAML 中描述 ai-api、chat-model、agents、agent-workflows 和 runner，应用启动后由 ArmoryService 的装配树依次创建模型 API、ChatModel、LlmAgent、WorkflowAgent 和 Runner。这样未来新增 PPT 生成、简历优化、技术文章审校等场景，可以复用同一套装配框架。

在流式处理上，ADK Runner 会返回 Flowable<Event>。我在 AgentWritingRunner 中逐个消费事件，根据 event.author 判断当前来自哪个 Agent，根据 event.partial 判断是否是增量片段。生成阶段 token 可以直接推给前端，审校阶段需要保证结构完整，所以会按行缓冲。由于任务执行被放到了 RocketMQ Consumer 中，Consumer 不能直接依赖前端连接，所以我引入 Redis Stream 作为过程事件缓冲，SSE 接口只负责从 Stream 读事件并推送。这样前端断线不会影响任务执行，重连后也可以继续读取后续事件。

在输出治理上，我主要解决 Markdown 不稳定问题。技术文章对 Markdown 格式要求比较高，模型容易输出列表缩进异常、标题和正文粘连、表格断裂、代码块转义错误。项目里一方面支持结构化 Markdown 块，比如 md_heading、md_table、md_code，由后端确定性渲染；另一方面用 CommonMark Parser 把最终内容解析成 AST，再重新渲染成规范 Markdown。如果 AST 解析失败，也会降级返回预处理结果，避免格式治理影响主流程。

在个性化上，我实现了一个参考 Mem0 思路的记忆系统。它不是保存全部历史消息，而是在任务成功后异步从会话中抽取长期有效信息，比如用户技术背景、写作偏好、项目经历。写入时先召回已有相似记忆给 LLM 做参考，再通过 MD5 hash 和向量相似度去重，最后写 MySQL 和 Qdrant。检索时根据当前草稿融合 Qdrant 向量召回、BM25 关键词召回、重要性和时间衰减，再用 Reranker 精排 Top-5 注入 Prompt。这样可以让生成内容更贴合用户背景，而不是每次都重新解释。

在任务可靠性上，因为多 Agent 链路很长，所以我用 RocketMQ 做异步执行。但我没有直接在提交接口里发 MQ，因为这会有 DB 和 MQ 不一致问题。我的做法是 Transactional Outbox：提交接口在同一个事务里写 ai_task 和 outbox_event，然后立即返回 taskId；后台 Publisher 抢占 outbox_event 并发送 RocketMQ；Consumer 收到消息后用条件更新把 PENDING/RETRYING 任务抢占为 RUNNING，抢占成功才执行。执行过程中定时更新 heartbeat，补偿 Job 扫描心跳超时任务并重新投递。这样可以处理 MQ 重复投递、Worker 崩溃和任务卡死。
```

---

## 16. 简历逐句解释与面试拆解

这一章按你简历中的句子逐条解释。每一句都要知道：它想表达什么、项目代码如何支撑、面试官可能追问什么、你应该怎么答。

### 16.1 `AgentWrite | 多智能体个性化创作工作台 | 个人项目 · 独立开发`

这句话表达三层信息：

```text
AgentWrite
  项目名称，暗示 Agent + Writing。

多智能体个性化创作工作台
  核心定位，不是普通 ChatBot。
  多智能体对应 ADK Agent 编排。
  个性化对应 Memory 系统。
  创作工作台对应草稿、文章、配图、审校、发布等完整产品能力。

个人项目 · 独立开发
  表示你需要对架构、后端、前端、数据库、中间件、AI 接入都有掌握。
```

面试官可能问：

```text
为什么叫工作台，不叫聊天机器人？
```

回答：

```text
因为它不是围绕单轮问答设计的，而是围绕技术文章创作流程设计的。用户有草稿、有文章状态、有发布流程、有社区互动，还有 AI 写作任务、智能配图、质量审校和记忆增强，所以更像一个创作工作台。
```

### 16.2 `项目时间：2026.06 – 至今`

这句话说明项目是近期项目。面试官可能会关注：

```text
项目是否真实持续开发？
哪些功能已经完成？
哪些是演进中的？
```

你要准备：

```text
目前已完成核心写作链路、多 Agent 装配、流式返回、记忆系统、Redis 场景、MQ 异步任务。后续还可以增强记忆编辑、Outbox 监控、死信告警、Agent 配置校验和更完整的前端体验。
```

### 16.3 `技术栈：Java 21 / Spring Boot 3.4 / Google ADK / Qdrant / Redis / MySQL / RocketMQ / React`

逐项解释：

```text
Java 21
  后端主语言。可讲现代 Java 版本、虚拟线程可以作为后续优化方向，但当前项目重点不是虚拟线程。

Spring Boot 3.4
  Web、配置、事务、依赖注入、定时任务、整合 Redis/MQ/MyBatis。

Google ADK
  Agent 运行框架，提供 LlmAgent、SequentialAgent、Runner、Session、Event 流。

Qdrant
  向量数据库，用于存储和召回用户记忆向量。

Redis
  用于限流、分布式锁、Stream、文章缓存、点赞收藏集合、热榜、记忆检索缓存。

MySQL
  权威数据源，保存用户、草稿、文章、任务、Outbox、记忆记录等。

RocketMQ
  AI 写作长任务异步执行。

React
  前端工作台，实现草稿编辑、SSE 展示、文章管理等交互。
```

面试注意：不要只背技术栈，要能说每个技术为什么出现。

### 16.4 `项目背景：面向技术创作者的 AI 写作工作台，支持草稿续写、智能配图与质量审校；`

解释：

```text
面向技术创作者
  用户不是泛泛写作用户，而是写技术文章、项目总结、面试内容、社区博客的人。
  所以输出需要结构清晰、Markdown 稳定、技术表达准确。

AI 写作工作台
  不是一个聊天框，而是以草稿和文章为中心。

草稿续写
  对应草稿实体、AI 写作任务、Prompt 构造。

智能配图
  对应 illustration analysis Agent 和 draw.io Agent。

质量审校
  对应 reviewer Agent 和 MarkdownNormalizer。
```

面试官可能问：

```text
为什么技术文章场景需要质量审校？
```

回答：

```text
技术文章对结构和格式要求更高，比如标题层级、代码块、列表、表格都要稳定。模型直接生成的内容可能能看，但落到编辑器里容易格式错乱，所以我单独设计 reviewer Agent 和 Markdown 治理链路。
```

### 16.5 `记忆系统从历史会话中抽取并沉淀用户的技术背景与写作偏好，在后续创作中检索并注入个性化上下文。`

解释：

```text
从历史会话中抽取
  不是直接保存历史消息，而是 LLM 抽取长期有效信息。

沉淀用户的技术背景与写作偏好
  技术背景例如 Java、Redis、MQ、Agent 项目。
  写作偏好例如面试导向、工程化表达、喜欢分点说明。

后续创作中检索
  根据当前草稿或任务 query 去 MemoryRetriever 搜索相关记忆。

注入个性化上下文
  把 Top-5 记忆格式化放进 Prompt，而不是全部历史对话。
```

代码对应：

```text
MemoryManager.addAsync
MemoryExtractor.extract
MemoryRetriever.retrieveFormattedContext
AiWritingService.buildPrompt
```

面试官可能问：

```text
这和 RAG 有什么区别？
```

回答：

```text
传统 RAG 更多是检索外部文档知识，我这里检索的是用户长期记忆。它的数据来源是用户历史会话，写入时经过 LLM 抽取、去重和更新，检索时结合当前写作任务召回并注入 Prompt。所以它更偏 personalized memory，而不是普通知识库问答。
```

### 16.6 `后端采用 DDD 风格分层，前端基于 React。`

解释：

```text
DDD 风格分层
  api 定义契约。
  trigger 处理外部入口。
  domain 承载核心业务规则。
  infrastructure 适配数据库、Redis、MQ、Qdrant 等外部资源。

React
  前端用于工作台式交互，例如草稿编辑、流式展示、文章管理。
```

面试官可能问：

```text
你这个项目哪里体现 DDD？
```

回答：

```text
我没有把所有逻辑都写在 Controller。比如 AI 写作核心在 AiWritingService 和 AgentWritingRunner，记忆核心在 MemoryManager、MemoryExtractor、MemoryRetriever，文章缓存和互动也在 domain service 中。infrastructure 层只负责 Repository、DAO、Qdrant、Redis、MQ 等适配。这样业务规则和技术实现有边界。
```

### 16.7 `多 Agent 编排与装配：基于 Google ADK 构建配置驱动的 Agent 装配机制，将模型、Prompt、MCP 工具及工作流配置化；`

逐句解释：

```text
基于 Google ADK
  ADK 是 Agent 运行框架，提供 LlmAgent、WorkflowAgent、Runner 和 Event。

配置驱动的 Agent 装配机制
  Agent 不写死在代码中，而是 YAML 配置，启动时装配。

模型配置化
  ai-api 和 chat-model 配置模型服务、模型名、参数。

Prompt 配置化
  agents 中的 instruction 定义每个 Agent 的职责和输出约束。

MCP 工具配置化
  chat-model 可以配置 MCP client 和 tools，让 Agent 调用外部工具。

工作流配置化
  agent-workflows 可以配置 sequential、parallel、loop。
```

代码对应：

```text
agent-writing.yml
AiAgentAutoConfig
ArmoryService
AiApiNode
ChatModelNode
AgentNode
AgentWorkflowNode
RunnerNode
```

面试官可能问：

```text
配置化和硬编码相比，代价是什么？
```

回答：

```text
配置化提高扩展性，但代价是启动期校验复杂。比如 Agent 名称、outputKey、workflow subAgents 都可能配置错。所以需要在装配阶段做校验，失败时尽早暴露，而不是执行到运行时才报错。
```

### 16.8 `串联需求分析、内容生成、质量审校三个 Agent，并调度 draw.io 子流程生成 XML 配图并回插草稿。`

解释：

```text
需求分析 Agent
  分析用户任务类型、草稿状态、读者、输出约束。

内容生成 Agent
  根据分析结果和草稿生成正文、摘要、标题、标签等。

质量审校 Agent
  检查技术文章格式、结构、Markdown 稳定性。

draw.io 子流程
  不是直接生成图片，而是生成 draw.io XML。
  XML 可以作为可编辑图表插入 Markdown。

回插草稿
  生成后的图表代码块会插入到适合的位置，形成最终 Markdown。
```

面试官可能问：

```text
三个 Agent 之间怎么传递上下文？
```

回答：

```text
在 ADK 工作流中，每个 Agent 可以通过 outputKey 把结果写入 Session 状态，后续 Agent 的 Prompt 可以引用前面 Agent 的输出。比如 analyst 输出 writing_analysis，generator 基于它生成 draft_content，reviewer 再基于 draft_content 做审校。
```

### 16.9 `响应式流处理：使用 RxJava Flowable 订阅 ADK 事件流并按 author 分流，分析阶段推送进度，生成阶段通过 SSE 增量渲染，审校阶段缓冲后统一落库；`

逐句解释：

```text
RxJava Flowable
  ADK runAsync 返回的是持续事件流，用 Flowable 表达。

订阅 ADK 事件流
  ChatService 调 runner.runAsync，AgentWritingRunner 消费 Flowable<Event>。

按 author 分流
  event.author 标识事件来自哪个 Agent。
  根据 author 映射 analyzing/generating/reviewing。

分析阶段推送进度
  analyst 输出不直接作为正文，而是让前端知道正在分析。

生成阶段 SSE 增量渲染
  generator 的 token 适合实时展示。

审校阶段缓冲后统一落库
  reviewer 输出接近最终结果，需要结构完整，所以缓冲处理后保存。
```

代码对应：

```text
ChatService.handleMessageStream
AgentWritingRunner.run
TaskEventPublisher
AiWritingController.stream
```

面试官可能问：

```text
SSE 是谁推的？Consumer 直接推吗？
```

回答：

```text
Consumer 不直接持有前端 SSE 连接。Consumer 执行任务时把过程事件写入 Redis Stream，SSE Controller 从 Redis Stream 读取并推给前端。这样任务执行和前端连接解耦。
```

### 16.10 `结合规则归一化与 CommonMark AST 修复代码块、列表缩进等 Markdown 格式问题。`

解释：

```text
规则归一化
  用正则和字符串处理修复一些 AST 解析前的问题，例如标题粘连、列表缺空格、错误转义。

CommonMark AST
  用 CommonMark Parser 把 Markdown 解析成结构化文档树。

修复代码块
  避免代码块外转义误处理，保留 fenced code block。

修复列表缩进
  让无序列表、有序列表符合 Markdown 规范。

最终目标
  让前端编辑器和文章详情页稳定渲染。
```

面试官可能问：

```text
AST 修复失败怎么办？
```

回答：

```text
代码里做了降级处理。如果 CommonMark 解析或渲染失败，不会让主写作任务失败，而是返回预处理后的 Markdown。因为格式治理是增强能力，不能影响主流程可用性。
```

### 16.11 `个性化记忆系统：参考 Mem0 独立实现用户记忆链路，通过 LLM 从会话中抽取技术背景与写作偏好，并以内容哈希和向量相似度去重；`

逐句解释：

```text
参考 Mem0
  思路是把历史对话中的长期有效信息抽取成 memory，而不是保存所有消息。

独立实现用户记忆链路
  项目自己实现 MemoryManager、MemoryExtractor、MemoryRetriever、Repository。

通过 LLM 从会话中抽取
  抽取不是规则写死，而是让 LLM 根据 user/assistant 消息输出结构化记忆。

技术背景与写作偏好
  记忆内容聚焦长期个性化信息。

内容哈希去重
  完全相同文本通过 MD5 跳过。

向量相似度去重
  语义相近但文本不同的记忆，通过 Qdrant 相似度判断更新还是新增。
```

代码对应：

```text
MemoryManager.add
MemoryExtractor.extract
QdrantVectorStore
MemoryEmbeddingClient
MemoryRepository
```

面试官可能问：

```text
为什么既要 hash 又要向量去重？
```

回答：

```text
hash 只能识别完全相同的文本，成本低但覆盖面窄。向量相似度可以识别表达不同但语义相近的记忆，比如“用户熟悉 Java 后端”和“用户有 Java 后端项目经验”。两者结合可以兼顾效率和语义去重。
```

### 16.12 `融合 Qdrant 向量召回与 BM25 关键词召回，经 Reranker 精排 Top-5 后注入 Prompt。`

逐句解释：

```text
Qdrant 向量召回
  根据当前草稿或任务 query 的 embedding 搜索语义相关记忆。

BM25 关键词召回
  用 MySQL FULLTEXT 或关键词检索补充精确技术词匹配。

融合
  把两个召回源的候选合并，并融合语义分、关键词分、重要性、时间衰减。

Reranker 精排
  对候选记忆重新排序，减少噪声。

Top-5
  控制注入 Prompt 的数量，避免上下文过长。

注入 Prompt
  在 buildPrompt 中把记忆格式化成用户记忆上下文。
```

面试官可能问：

```text
Top-5 为什么不是 Top-20？
```

回答：

```text
记忆注入不是越多越好。太多会增加 token 成本，也会引入噪声，影响模型关注当前草稿。我选择 Top-5 是为了在个性化和上下文简洁之间做平衡。
```

### 16.13 `Redis 场景治理：面向 AI 接口、文章详情和社区互动场景，使用 Redisson 令牌桶限流；`

解释：

```text
AI 接口
  对应 AI 写作任务提交，需要限流和防刷。

文章详情
  对应 ArticleCacheService 缓存。

社区互动
  对应点赞、收藏、关注、评论点赞、热榜。

Redisson 令牌桶限流
  对应 RateLimitService 的 RRateLimiter。
```

更准确的面试表达：

```text
限流主要用在 AI 接口，文章详情和社区互动更多使用 Redis 缓存、Set、AtomicLong 和 ZSet。面试时不要说文章详情也用了令牌桶限流，而要说 Redis 在不同场景使用了不同结构。
```

### 16.14 `通过 Cache-Aside、空值缓存、随机过期和互斥重建治理缓存穿透、雪崩与击穿，并以 SET、ZSET 实现点赞去重与热榜排序。`

逐句解释：

```text
Cache-Aside
  业务代码先查缓存，未命中查 DB，再写缓存。

空值缓存
  DB 查不到文章时写 __NULL__，短 TTL，防止不存在 ID 反复打 DB。

随机过期
  正常文章缓存 TTL 加随机抖动，避免大量 key 同时过期。

互斥重建
  热点文章缓存过期时，用 Redisson Lock 只允许一个线程回源重建。

缓存穿透
  查询不存在数据。

缓存雪崩
  大量缓存同一时间失效。

缓存击穿
  热点 key 失效，大量请求同时打 DB。

SET 点赞去重
  Redis RSet 保存文章被哪些用户点赞、用户点赞过哪些文章。

ZSET 热榜排序
  Redis RScoredSortedSet 按浏览和点赞累积分数排序。
```

面试官可能问：

```text
点赞去重到底靠 Redis 还是 MySQL？
```

回答：

```text
最终幂等靠 MySQL 点赞关系表，saveLike 返回 false 说明已经点过赞。Redis Set 主要用于读加速，例如快速判断用户是否点过赞、获取用户点赞集合。这样 MySQL 是权威数据源，Redis 是缓存层。
```

### 16.15 `异步任务执行：针对多 Agent 写作耗时长、同步 HTTP 请求易超时的问题，引入 RocketMQ 解耦任务提交与执行，任务落库后即快速返回 taskId；`

逐句解释：

```text
多 Agent 写作耗时长
  分析、生成、审校、配图串起来，耗时不可控。

同步 HTTP 请求易超时
  如果一次请求里直接等模型完整生成，容易受网关、浏览器、服务端超时限制影响。

RocketMQ 解耦任务提交与执行
  HTTP 只负责提交任务，Consumer 后台执行。

任务落库
  写 ai_task 表，状态 PENDING。

快速返回 taskId
  前端拿 taskId 后通过任务查询和 SSE 获取进度。
```

面试官可能问：

```text
为什么不用线程池？
```

回答：

```text
本地线程池实现简单，但服务重启任务会丢，多实例下任务调度不可控，也不好做状态查询和失败补偿。RocketMQ 可以把任务分发给多个 Consumer，配合任务表和状态机可以实现可查询、可重试、可补偿的长任务执行。
```

### 16.16 `通过 Transactional Outbox 同事务保存任务与事件，Consumer 条件更新抢占任务，降低重复执行风险。`

逐句解释：

```text
Transactional Outbox
  任务记录和待发送消息事件写入同一个本地事务。

同事务保存任务与事件
  ai_task 和 outbox_event 要么都成功，要么都失败。

Consumer 条件更新抢占任务
  Consumer 不直接执行，而是 UPDATE ai_task WHERE status IN (PENDING, RETRYING)。

降低重复执行风险
  RocketMQ 可能重复投递，但同一任务只有一个 Worker 能从可执行状态改成 RUNNING。
```

面试官可能问：

```text
Outbox 能保证消息不重复吗？
```

回答：

```text
Outbox 主要保证业务数据和待发送事件的一致性，不保证消息只投递一次。发送失败重试、Publisher 崩溃恢复都可能导致重复消息。所以 Consumer 仍然必须做幂等，我这里用任务状态条件更新做执行幂等。
```

---

## 17. 针对五个简历点的追问训练清单

### 17.1 多 Agent 编排

你需要能回答：

```text
1. 为什么不用单 Agent？
2. 三个 Agent 的职责边界是什么？
3. Agent 之间如何传递上下文？
4. YAML 配置如何变成 ADK Runner？
5. MCP 工具是怎么挂载到模型上的？
6. draw.io 子流程为什么不放在主链路里？
7. 如果新增一个“SEO 优化 Agent”，你会怎么做？
8. 如果某个 Agent 执行失败，链路怎么处理？
```

### 17.2 响应式流处理

你需要能回答：

```text
1. ADK Event 里有哪些关键信息？
2. Flowable 和普通同步返回有什么区别？
3. author 分流具体解决什么问题？
4. 为什么生成阶段实时推，审校阶段缓冲？
5. Redis Stream 为什么比 Pub/Sub 更适合？
6. SSE 断开重连怎么处理？
7. Markdown AST 比正则强在哪里？
8. 格式治理失败是否影响主流程？
```

### 17.3 个性化记忆

你需要能回答：

```text
1. 为什么不拼接全部历史对话？
2. 记忆什么时候抽取？
3. LLM 抽取的输出格式如何保证？
4. 如何做完全重复和语义重复去重？
5. Qdrant、BM25、Reranker 各自作用是什么？
6. Top-5 怎么确定？
7. 错误记忆如何删除或修正？
8. 记忆系统和普通 RAG 有什么区别？
```

### 17.4 Redis

你需要能回答：

```text
1. Redis 在项目里用了哪些数据结构？
2. AI 限流为什么用令牌桶？
3. 分布式锁解决什么问题？
4. Cache-Aside 流程是什么？
5. 穿透、雪崩、击穿分别如何治理？
6. 点赞幂等靠 MySQL 还是 Redis？
7. ZSet 热榜如何设计分数？
8. Redis Stream 和 RocketMQ 的职责边界是什么？
```

### 17.5 RocketMQ

你需要能回答：

```text
1. 为什么同步 HTTP 不适合？
2. 为什么不用本地线程池？
3. Outbox 解决什么一致性问题？
4. Outbox 和 RocketMQ 事务消息有什么区别？
5. Consumer 如何防重复执行？
6. Worker 挂了怎么恢复？
7. 任务状态机有哪些状态？
8. 消息积压怎么排查和优化？
```

---

## 18. 最后背诵策略

不要按技术栈背，要按问题背。

推荐背诵模板：

```text
这个问题的背景是：...
如果简单做会有什么问题：...
我在项目里采用的方案是：...
代码上主要落在：...
这个方案的优点是：...
它也有不足，后续可以优化：...
```

示例：

```text
以 MQ 异步任务为例，背景是多 Agent 写作耗时长，同步 HTTP 容易超时。简单用本地线程池虽然能异步，但服务重启任务会丢，多实例下也不好调度。所以我采用 RocketMQ + 任务表 + Outbox。提交接口在事务里保存 ai_task 和 outbox_event，然后返回 taskId；后台 Publisher 发送 MQ；Consumer 通过条件更新抢占任务，执行时写 heartbeat；补偿 Job 处理超时任务。这个方案提升了可靠性，但也带来了 Outbox 表维护、重试策略和监控告警的复杂度。
```

如果你每个简历点都能按这个模板讲清楚，面试官继续追问时，你就不会只停留在“我用了某某技术”。
