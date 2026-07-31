# 核心工作第一点：Multi-Agent 编排框架 面试深度解析

## 简历原文

> 基于 Google ADK 构建 Multi-Agent 编排框架，将模型、Prompt、Skill、MCP 工具及工作流配置化，串联需求分析、内容生成、质量审校等多个 Agent，并集成 draw.io 自动生成流程图，实现文章创作全流程自动执行。

---

## 一、「基于 Google ADK 构建 Multi-Agent 编排框架」

### 代码对应

- Google ADK (`com.google.adk`) 是 Google 开源的 Agent 开发框架，提供 `LlmAgent`、`SequentialAgent`、`ParallelAgent`、`LoopAgent`、`InMemoryRunner` 等核心组件
- 基于它设计了一套**策略树装配链路**（`AbstractArmorySupport` → 各节点），把"如何组装一个 Agent 应用"变成了一棵可扩展的节点树

### 面试怎么说

> 我调研了 LangChain4j、Spring AI Agent Utils、Google ADK 等框架。选择 Google ADK 是因为它原生支持 Sequential/Parallel/Loop 三种编排模式，且与 Spring AI 的 ChatModel 能无缝集成（通过 `SpringAI` 适配器桥接）。我在此之上设计了一套策略树装配模式，将 Agent 的组装过程节点化。

### 💡 面试建议

- 面试官大概率会追问 **"Google ADK 和 LangChain 的区别是什么？"** 准备好：ADK 侧重多 Agent 编排（workflow-first），LangChain 侧重 Chain/Tool 单 Agent 调用
- 如果问"为什么不用 LangChain4j"：pom 里其实引了 LangChain4j，可以说"LangChain4j 作为模型调用层的备选，Google ADK 作为编排层"

---

## 二、「将模型、Prompt、Skill、MCP 工具及工作流配置化」

### 代码对应

所有配置集中在 `AiAgentAutoConfigProperties`，对应的 YAML 结构是 `AiAgentConfigTableVO`：

```yaml
# 伪代码表示配置结构
tables:
  writingAgent:
    appName: "writing-app"
    agent: { agentId: "300002", agentName: "...", agentDesc: "..." }
    module:
      aiApi: { baseUrl: "...", apiKey: "...", completionsPath: "..." }    # ← 模型
      chatModel: { model: "deepseek-chat" }
      agents:                                                             # ← Prompt
        - { name: "agent_writing_analyst", instruction: "...", outputKey: "..." }
        - { name: "agent_writing_generator", instruction: "..." }
        - { name: "agent_writing_reviewer", instruction: "..." }
      chatModel.toolMcpList:                                              # ← MCP 工具
        - sse: { name: "search", baseUri: "..." }
        - stdio: { name: "fs", serverParameters: { command: "npx", args: [...] } }
        - local: { name: "myTool" }
      chatModel.toolSkillsList:                                           # ← Skill
        - { type: "directory", path: "/skills/drawio" }
      agentWorkflows:                                                     # ← 工作流
        - { type: "sequential", name: "writing_flow", subAgents: [...] }
      runner: { agentName: "writing_flow", pluginNameList: ["myLogPlugin"] }
```

### 装配链路（策略树）执行顺序

```
RootNode → AiApiNode（构建 OpenAiApi）
         → ChatModelNode（构建 Spring AI ChatModel + MCP + Skills）
         → AgentNode（遍历 agents 列表，为每个创建 LlmAgent）
         → AgentWorkflowNode（根据 type 分派到 Sequential/Parallel/Loop Node）
         → RunnerNode（创建 InMemoryRunner + 注册为 Spring Bean）
```

### 关键节点职责

| 节点 | 职责 |
|------|------|
| `RootNode` | 入口，解析配置表 |
| `AiApiNode` | 构建 OpenAiApi（base_url + apiKey） |
| `ChatModelNode` | 构建 Spring AI ChatModel + 加载 MCP/Skills |
| `AgentNode` | 构建单个 Google ADK LlmAgent（含 Prompt、Tools） |
| `AgentWorkflowNode` | 按类型分派 Sequential/Parallel/Loop |
| `SequentialAgentNode` | 构建 SequentialAgent（串行多 Agent） |
| `ParallelAgentNode` | 构建 ParallelAgent（并行多 Agent） |
| `LoopAgentNode` | 构建循环 Agent |
| `RunnerNode` | 创建 InMemoryRunner + 注册为 Spring Bean |

### 面试怎么说

> 我将 Agent 所需的五要素——模型端点、Prompt 指令、Skills 函数工具、MCP 外部工具、Workflow 编排方式——全部外化到 YAML 配置文件。应用启动时，通过一棵策略树逐级解析配置并装配：先创建 API 客户端，再构建 ChatModel，然后为每个子 Agent 注入对应的 Prompt 和工具，最后按 Workflow 配置组装成 SequentialAgent/ParallelAgent，包装进 InMemoryRunner 注册为 Spring Bean。新增一个 Agent 只需加配置，不需要改代码。

### 💡 面试建议

- 这里体现了**开闭原则**（OCP）：对扩展开放，对修改关闭。一定要说出来
- 面试官可能追问：**"MCP 是什么？和普通的 Function Call 有什么区别？"**
  - MCP (Model Context Protocol) 是标准化的工具协议，支持 SSE/Stdio/Local 三种传输方式，让 Agent 能调用外部工具服务（如搜索引擎、文件系统）
  - 与 OpenAI Function Calling 的区别：MCP 是协议层标准，Function Calling 是 API 层约定
- 面试官可能追问：**"策略树模式是什么设计模式？"**
  - 本质是**策略模式 + 责任链模式**的组合。每个节点实现 `StrategyHandler` 接口，执行完当前逻辑后通过 `get()` 方法决定路由到哪个下一节点（类似责任链），而 `AgentWorkflowNode` 根据 type 做策略分派

---

## 三、「串联需求分析、内容生成、质量审校等多个 Agent」

### 代码对应

在 `AgentWritingRunner.runWorkflow()` 中：

```java
// 三个子 Agent 按 SequentialAgent 串行执行
// Google ADK 会依次调用：analyst → generator → reviewer
Flowable<Event> events = chatService.handleMessageStream(agentId, userId, sessionId, prompt);

events.blockingForEach(event -> {
    String author = event.author();  // 区分当前是哪个 Agent 在说话
    if (AUTHOR_ANALYST.equals(author)) return;        // 分析阶段：不输出
    if (AUTHOR_GENERATOR.equals(author)) { ... }      // 生成阶段：推 token
    // reviewer 阶段：缓冲，最后统一渲染
});
```

### 三个 Agent 的职责

| Agent | 作用 | 输出处理 |
|-------|------|---------|
| `agent_writing_analyst` | 分析草稿上下文、理解用户意图 | 仅供下游 Agent 参考，不对用户展示 |
| `agent_writing_generator` | 基于分析结果生成正文 | 逐 token SSE 推送给前端 |
| `agent_writing_reviewer` | 质量审校，输出结构化 JSON 块 | 缓冲后用 MarkdownBlockRenderer 渲染 |

### 面试怎么说

> 文章创作流程用 SequentialAgent 串联三个子 Agent：第一个是需求分析 Agent，它解读草稿标题、摘要和已有正文，输出写作方向分析（不展示给用户，仅作为下游上下文）；第二个是内容生成 Agent，基于分析结果续写正文，输出逐 token 流式推送；第三个是质量审校 Agent，对生成内容做格式、逻辑、专业性检查，输出结构化的修改建议。SequentialAgent 保证执行顺序，后一个 Agent 能看到前一个的输出。

### 💡 面试建议

- 面试官可能问：**"为什么 analyst 的输出不展示给用户？"** — 因为它是"思考过程"，类似 CoT (Chain of Thought)，帮助后续 Agent 理解意图但对用户无价值
- 面试官可能问：**"SequentialAgent 内部 Agent 之间怎么传递上下文？"** — Google ADK 的 SequentialAgent 共享同一个 Session，后面的 Agent 能看到前面 Agent 写入 Session 的所有 content（通过 `outputKey`）
- 面试官可能问：**"为什么不用一个大 Prompt 让一个 Agent 做完？"** — 多 Agent 拆分职责清晰，每个 Agent 的 Prompt 更聚焦，减少幻觉；且可以独立替换某个环节（比如换一个更强的 reviewer）

---

## 四、「并集成 draw.io 自动生成流程图」

### 代码对应

在 `AgentWritingRunner` 中：

```java
// 1. 先用 illustration_agent 分析文章哪些段落需要配图
List<IllustrationRequest> requests = analyzeIllustrations(userId, responseBuilder.toString());
// 返回：[{anchor:"系统架构", diagramType:"architecture", requirement:"画 MQ 消费流程"}]

// 2. 对每个配图请求，调用 drawio_agent 生成 draw.io XML
String drawXml = generateIllustration(userId, req);
// drawio_agent 输出 JSON: {"type":"drawio_done","content":"<mxGraphModel>...</mxGraphModel>"}

// 3. 找到文章中的锚点位置，注入 ```drawio 代码块
injectIllustration(responseBuilder, req.anchor(), drawXml, eventConsumer);
```

### 配图流程

```
文章生成完成
  → IllustrationAgent 分析（输出 JSON：anchor + diagramType + requirement）
  → 对每个请求调用 DrawioAgent 生成 XML
  → findAnchor() 定位插入位置
  → 注入 ```drawio 代码块到文章中
  → 前端用 mxGraph 渲染为可编辑流程图
```

### 面试怎么说

> 文章生成完成后，我会调用一个配图分析 Agent 判断哪些段落适合配图（最多 3 处），它输出锚点位置、图表类型（架构图/流程图/时序图）和需求描述。然后对每个配图请求调用 draw.io Agent 生成 XML 格式的图表，最后在文章中找到对应锚点位置插入 `drawio` 代码块。前端渲染时解析这个代码块展示为可编辑的流程图。

### 💡 面试建议

- 这是一个**差异化亮点**，大部分 AI 写作产品没有自动配图能力
- 面试官可能问：**"锚点匹配怎么实现的？"** — 先精确匹配标题文本，匹配不到则用最长单词做模糊匹配（`findAnchor` 方法）
- 面试官可能问：**"为什么选 draw.io 格式？"** — draw.io 是开源标准格式，前端可直接用 mxGraph 渲染和二次编辑，生态成熟

---

## 五、「实现文章创作全流程自动执行」

### 代码对应

完整的端到端流程：

```
用户点击"生成文章"
  → Controller 接收请求
  → AiWritingService.submitTask()（限流 + 防重 + 创建任务 + Outbox 事件）
  → AiTaskOutboxPublisher 异步投递到 RocketMQ
  → AiTaskConsumer 消费消息，CAS 抢占任务
  → AgentWritingRunner.run()
      → 策略解析（根据任务类型选择 workflow 还是 single-agent）
      → 执行 Agent 编排（分析 → 生成 → 审校 → 配图）
      → MarkdownNormalizer 格式化输出
  → 结果存 DB + Redis Pub/Sub 推送前端
  → MemoryManager.addAsync() 异步抽取记忆
```

### 面试怎么说

> 用户只需输入标题和简要描述，系统自动完成从需求理解到内容生成、质量审校、配图插入、格式修正的全流程。任务通过 Outbox + MQ 异步执行，支持长耗时（通常 30s~2min），执行过程中通过 Redis Pub/Sub + SSE 实时推送进度和内容片段给前端。

### 💡 面试建议

- 面试官可能问：**"全流程大概耗时多长？怎么保证用户体验？"** — 30s~2min，通过分阶段推送进度（"正在分析..."、"正在生成..."）+ token 级流式输出，用户能实时看到内容生成过程，体验类似 ChatGPT 打字效果

---

## 六、面试回答最佳结构

建议用 **"架构选型 → 设计决策 → 执行流程 → 技术细节"** 四层来回答：

1. **架构选型**（10s）：选了 Google ADK，理由是原生多 Agent 编排 + Spring AI 集成
2. **设计决策**（20s）：策略树模式 + 配置化五要素 + 开闭原则
3. **执行流程**（20s）：analyst → generator → reviewer → illustration，SequentialAgent 串行
4. **技术细节**（准备好被追问）：MCP 三种传输、Session 上下文传递、锚点匹配策略

这样回答既有高度又有深度，面试官想深挖随时可以给出更多细节。

---

## 七、核心源码文件索引

| 文件 | 职责 |
|------|------|
| `domain/agent/service/armory/ArmoryService.java` | 装配入口 |
| `domain/agent/service/armory/factory/DefaultArmoryFactory.java` | 工厂 + 上下文 |
| `domain/agent/service/armory/AbstractArmorySupport.java` | 节点基类（含 Bean 注册） |
| `domain/agent/service/armory/node/RootNode.java` | 根节点 |
| `domain/agent/service/armory/node/AiApiNode.java` | API 构建 |
| `domain/agent/service/armory/node/ChatModelNode.java` | ChatModel 构建 |
| `domain/agent/service/armory/node/AgentNode.java` | LlmAgent 构建 |
| `domain/agent/service/armory/node/AgentWorkflowNode.java` | 工作流分派 |
| `domain/agent/service/armory/node/workflow/SequentialAgentNode.java` | 串行编排 |
| `domain/agent/service/armory/node/RunnerNode.java` | Runner 注册 |
| `domain/agent/service/ai_writing/AgentWritingRunner.java` | 写作执行器 |
| `domain/agent/service/chat/ChatService.java` | Chat 核心服务 |
| `domain/agent/model/valobj/AiAgentConfigTableVO.java` | 配置模型 |
