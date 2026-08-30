# AI 写作快捷操作意图守卫设计方案

## 1. 背景

当前项目中的快捷写作链路采用 `taskType` 强驱动模式：

- 前端在快捷操作面板中选择 `GENERATE_BODY`、`POLISH_TEXT`、`SUMMARIZE` 等任务；
- 后端直接进入 [`AiWritingController.submitTask`](file:///d:/java/scaffold/AgentWrite/sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/http/AiWritingController.java#L70-L91)；
- 之后通过 [`AiWritingService.submitTask`](file:///d:/java/scaffold/AgentWrite/sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AiWritingService.java#L113-L139) 创建 `ai_task` 与 `outbox_event`，进入 RocketMQ 异步执行链路。

当前实现没有在建任务前判断“当前用户请求是否真的适合进入文章写作任务”。这会带来以下问题：

1. 用户在快捷操作中输入明显闲聊内容，例如“你好”“你是谁”，仍可能创建写作任务。
2. 用户实际想要的是画图请求，但误用快捷写作入口，也会进入写作任务链路。
3. 无效请求进入 MQ，会浪费模型调用、任务资源与监控成本。
4. 用户意图模糊时，系统没有确认机制，容易出现误拦截或误放行。

本方案目标是在**不破坏现有异步写作执行架构**的前提下，引入一层“写作意图守卫”。

---

## 2. 目标与非目标

### 2.1 目标

本次改造目标如下：

1. 在快捷写作任务创建前，同步判断当前请求是否适合进入文章写作链路。
2. 对明显非写作请求进行拦截，并给出建议去向，例如“对话写作”或“Draw.io 工作台”。
3. 对边界模糊请求采用“规则预筛 + 模型二判 + 用户确认”机制，尽量平衡误杀与误放行。
4. 对已预检的请求发放 `precheckToken`（`PASS` 与 `CONFIRM` 两种类型），避免重复判断、前后端状态不一致与被绕过。
5. 保持现有 `ai_task -> outbox_event -> RocketMQ -> Consumer -> Agent` 主链路不变。

### 2.2 非目标

本次方案明确不做以下事项：

1. 不重构现有 MQ 异步写作主链路。
2. 不统一重写“快捷写作 / 对话写作 / Draw.io”三条产品入口。
3. 不实现一个全站级别的统一意图路由中心。
4. 不要求第一期就把所有分类都交给模型处理。

---

## 3. 现状分析

### 3.1 当前快捷写作入口

前端快捷操作入口位于 `AgentWrite-front` 的 `AiWritingPanel`。当前交互是：

1. 用户选择一个 `taskType`。
2. 用户可填写 `customInstruction`。
3. 前端直接调用 `submitTask`。

这意味着当前入口设计默认用户一定要执行写作任务，而不是先验证意图是否成立。

### 3.2 当前后端写作主链路

后端写作链路关键节点如下：

1. [`AiWritingController.submitTask`](file:///d:/java/scaffold/AgentWrite/sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/http/AiWritingController.java#L70-L91)
2. [`AiWritingService.submitTask`](file:///d:/java/scaffold/AgentWrite/sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AiWritingService.java#L113-L139)
3. [`AiWritingService.doSubmitInTransaction`](file:///d:/java/scaffold/AgentWrite/sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AiWritingService.java#L146-L173)
4. `ai_task + outbox_event` 落库
5. Outbox 即时投递与定时补发
6. RocketMQ Consumer 执行任务

因此，“意图守卫”最合适的切入点必须位于 `submitTask` 之前或其最前置位置，而不是 MQ 之后。

### 3.3 当前为什么不能完全照搬独立 Draw.io 链路

独立 Draw.io 链路中，用户已经显式进入画图工作台，系统默认用户场景就是“画图”，后端只负责对模型输出协议做识别，如：

- `drawio_node`
- `drawio_edge`
- `drawio_done`

但快捷写作链路的核心问题不是“输出协议约束”，而是“**入口路由是否正确**”。因此不能简单照搬 Draw.io 的协议式约束。

---

## 4. 设计原则

本方案遵循以下原则：

1. **入口质量控制优先**  
   在建任务前做判断，避免无效请求进入异步链路。

2. **高确定性规则优先，低确定性语义交给模型**  
   明显闲聊和明显画图不需要每次都调用模型。

3. **不确定场景不武断处理**  
   通过用户确认作为兜底，降低误杀成本。

4. **主链路最小侵入**  
   尽量不改现有 `AiWritingService.submitTask` 之后的架构。

5. **前端交互友好，后端决策权威**  
   前端负责体验，后端负责最终判定与安全约束。

6. **可观测、可迭代**  
   后续要能从日志和指标中分析误判与收益。

---

## 5. 总体方案

本次采用：

> **前端预检 + 后端权威校验 + precheckToken**

并在预检内部采用：

> **规则预筛 + 模型二判 + 用户确认兜底**

### 5.1 总体流程

```text
AiWritingPanel
  -> POST /ai-writing/task/precheck
      -> WritingIntentGuardService
          -> WritingIntentRuleEngine
          -> WritingIntentModelClassifier
          -> PrecheckTokenService
      <- PASS / BLOCK / CONFIRM_REQUIRED

  -> PASS: 携带 precheckToken(PASS) 直接 POST /ai-writing/task/submit
  -> BLOCK: 提示用户切换到对话写作 Tab 或前往 Draw.io
  -> CONFIRM_REQUIRED: 用户确认后携带 precheckToken(CONFIRM) 再次 submit

submitTask
  -> 现有 ai_task / outbox_event / MQ 主链路
```

### 5.2 决策结果

预检结果统一为三类：

- `PASS`：允许直接进入写作任务链路
- `BLOCK`：明确不适合进入写作任务链路
- `CONFIRM_REQUIRED`：边界不清，需要用户确认后继续

---

## 6. 模块划分

### 6.1 后端 Trigger 层

在 [`AiWritingController`](file:///d:/java/scaffold/AgentWrite/sutone-agent-bok-trigger/src/main/java/cn/sutone/ai/trigger/http/AiWritingController.java) 中新增：

- `POST /api/v1/ai-writing/task/precheck`

职责：

1. 获取当前登录用户 `userId`
2. 接收预检请求 DTO
3. 调用 `WritingIntentGuardService`
4. 返回统一的预检结果 DTO

### 6.2 后端 Domain 层

新增包建议：

`cn.sutone.ai.domain.agent.service.ai_writing.intent`

新增核心类：

1. `WritingIntentGuardService`
2. `WritingIntentRuleEngine`
3. `WritingIntentModelClassifier`
4. `PrecheckTokenService`

### 6.3 后端类型对象

建议新增以下 VO/DTO：

- `WritingIntentTypeVO`
- `WritingIntentDecisionVO`
- `WritingIntentPrecheckContextVO`
- `WritingIntentPrecheckResultVO`
- `PrecheckAiTaskRequestDTO`
- `PrecheckAiTaskResponseDTO`

### 6.4 前端

前端改造点：

1. `src/api/ai-writing.ts` 新增 `precheckTask()`
2. `AiWritingPanel.handleAiTask()` 由“直接 submit”改成“先 precheck，再 submit”
3. 新增确认弹窗与拦截提示

---

## 7. 上下文建模

预检不能只看 `customInstruction`，必须结合当前任务上下文。

### 7.1 预检上下文字段

`WritingIntentPrecheckContextVO` 建议包含：

- `userId`
- `draftId`
- `taskType`
- `enableIllustration`
- `draftTitle`
- `draftSummary`
- `draftContent`
- `draftContentLength`
- `selectedText`
- `selectedTextLength`
- `customInstruction`
- `formatInstruction`

### 7.2 为什么要做上下文化判断

因为同一句话在不同上下文下语义不同，例如：

- `customInstruction = "你好"`  
  若当前 `taskType = GENERATE_BODY` 且草稿正文已有 3000 字，不应直接拦截。

- `customInstruction = "帮我画一个登录时序图"`  
  即使用户点了 `GENERATE_BODY`，也更像画图请求。

- `taskType = POLISH_TEXT` 且存在 `selectedText`  
  即使自定义指令很短，也通常仍是明确的局部润色任务。

因此，预检应是“任务上下文感知的意图守卫”，而不是单句分类。

---

## 8. 规则预筛设计

### 8.1 规则层职责

`WritingIntentRuleEngine` 只做高确定性规则判断，输出：

- `PASS`
- `BLOCK`
- `UNCERTAIN`

规则层不负责最终决定所有边界场景。

#### 8.1.1 规则执行顺序与优先级

规则引擎必须按**固定顺序**执行、命中即短路返回，避免同一请求命中多条规则时结果不确定：

1. **空/无效输入检测**（最高优先）→ `BLOCK`
2. **明显闲聊 / 问答 / 画图关键词** → `BLOCK`
3. **局部润色**（`POLISH_TEXT` 且 `selectedText` 非空）→ `PASS`
4. **明确摘要/标题/标签/质检任务**（`SUMMARIZE` / `GENERATE_TITLE` / `GENERATE_TAGS` / `QUALITY_CHECK`，草稿内容足够）→ `PASS`
5. **生成大纲**（`GENERATE_OUTLINE`，未命中闲聊/画图/问答）→ `PASS`
6. **正文续写**（`GENERATE_BODY`，草稿正文长度超阈值，未命中闲聊/画图）→ `PASS`
7. 其余情况 → `UNCERTAIN`

核心约定：**BLOCK 类规则优先级高于 PASS 类规则**（先拦截、再放行）。例如用户在已有 3000 字正文的草稿上输入 `customInstruction = "你好"` 并点击 `GENERATE_BODY`，同时命中"闲聊 BLOCK"与"正文充分 PASS"，按顺序先命中闲聊规则，结果为 `BLOCK`，从而闭环 7.2 节的冲突示例。

#### 8.1.2 大纲任务特殊处理

`GENERATE_OUTLINE` 通常在正文为空或较少时使用，因此**不要求"草稿正文充分"**。只要 `customInstruction` 未命中闲聊/画图/问答关键词，即返回 `PASS`，避免大纲任务因正文为空被误判为"无效输入"而拦截。

### 8.2 建议直接 BLOCK 的规则类别

#### 8.2.1 明显闲聊类

例如：

- “你好”
- “你是谁”
- “你能做什么”
- “在吗”

#### 8.2.2 明显问答类

例如：

- “解释一下 JVM”
- “什么是 Redis”
- “帮我介绍下 MySQL”

#### 8.2.3 明显画图类

例如：

- “帮我画一个架构图”
- “生成时序图”
- “画流程图”
- “帮我做一张登录链路图”

#### 8.2.4 明显无效类

例如：

- 空字符串
- 只有标点
- 只有“嗯”“好的”“继续”

### 8.3 建议直接 PASS 的规则类别

#### 8.3.1 正文类任务且草稿内容充分

例如：

- `taskType = GENERATE_BODY`
- 草稿正文长度超过阈值
- 未命中闲聊/画图关键词

#### 8.3.2 局部润色类

例如：

- `taskType = POLISH_TEXT`
- `selectedText` 非空

#### 8.3.3 明确的摘要/标题/标签/质检任务

例如：

- `SUMMARIZE`
- `GENERATE_TITLE`
- `GENERATE_TAGS`
- `QUALITY_CHECK`

且草稿内容足够支撑任务执行。

### 8.4 建议进入 UNCERTAIN 的场景

例如：

- “帮我整理一下这段内容”
- “说清楚一点”
- “你好，顺便帮我润色下这一段”
- “帮我调整一下这部分”

这些请求具有写作可能性，但规则无法安全判断，必须进入模型二判。

---

## 9. 模型二判设计

### 9.1 目标

规则层只负责低成本、高确定性场景。对于语义边界场景，采用轻量模型进行二次判定。

### 9.2 模型组件

新增组件：

- `WritingIntentModelClassifier`

建议单独使用一个轻量意图分类 Agent，不复用正文写作 Agent。

**Agent 供给与注册**：新增一个轻量意图分类 Agent（建议 agentId `300005`，沿用现有 `300002`~`300004` 的编号体系），在 Agent 配置表中注册，选用低成本、低延迟的模型。`WritingIntentModelClassifier` 通过现有 `IChatService.handleMessage(agentId, userId, message)` 调用（返回 `List<String>`），不引入新的调用通道。分类 Agent 不使用 `recoverHistory`（与写作快捷操作一致，避免历史对话污染意图判断）。

### 9.3 为什么不复用正文写作 Agent

原因如下：

1. 意图分类不需要长文本生成能力。
2. 成本应尽量低。
3. 不应被正文历史或复杂 prompt 污染。
4. 需要结构化、稳定、快速的响应。

### 9.4 模型输入建议

模型输入建议包括：

- 当前场景：`QUICK_WRITING_PANEL`
- `taskType`
- `draftTitle`
- `draftSummary`
- `draftContentLength`
- 是否存在 `selectedText`
- `customInstruction`

### 9.5 模型输出协议

模型必须返回 JSON：

```json
{
  "intent": "WRITE_ARTICLE | CHAT | DRAW_DIAGRAM | UNKNOWN",
  "confidence": 0.84,
  "reason": "用户语义更像闲聊问候，不是文章写作任务",
  "suggestion": "CONTINUE_WRITING | SWITCH_TO_CHAT | SWITCH_TO_DRAWIO | ASK_CONFIRM"
}
```

### 9.6 模型结果转决策

建议阈值：

- `WRITE_ARTICLE && confidence >= 0.80` -> `PASS`
- `CHAT && confidence >= 0.80` -> `BLOCK`
- `DRAW_DIAGRAM && confidence >= 0.80` -> `BLOCK`
- 其他情况 -> `CONFIRM_REQUIRED`

### 9.7 失败降级

若模型超时、异常或响应格式错误：

- 不直接放行
- 不直接拦截
- 统一返回 `CONFIRM_REQUIRED`

这样可最大程度降低误伤。

具体约束：

- **超时阈值**：建议 3 秒（轻量分类应足够快），超过即降级 `CONFIRM_REQUIRED`。
- **解析协议**：模型应输出**单行 JSON**（与 9.5 节协议一致）。实现上取 `handleMessage` 返回列表的第一条非空行解析；若为多行，则拼接后解析，解析失败即降级。字段缺失或类型不符同样降级，不信任模型的部分输出。

### 9.8 预检限流与结果缓存

模型二判是有成本的调用，且预检接口本身可被频繁触发，必须独立于 `submitTask` 的限流（现有 `RateLimitService`，每用户每分钟 5 次）单独控制，避免成为新的滥用入口：

- **预检接口限流**：每用户每分钟 10 次（独立于提交限流）。
- **模型二判限流**：每用户每分钟 5 次（只对进入 `UNCERTAIN` 的请求计数），超过后本次直接降级 `CONFIRM_REQUIRED`。
- **结果缓存**：以 `userId + draftId + taskType + promptHash` 为 key，将预检结果在 Redis 缓存 30~60 秒，命中缓存时不再重复规则判断与模型调用。

---

## 10. precheckToken 预检凭证设计

### 10.1 设计目标与核心问题

原有的 `confirmToken` 只覆盖 `CONFIRM_REQUIRED` 场景，存在一个致命缺口：`submitTask` 是**无状态接口**，无法判断一次请求是"precheck PASS 后直接提交"还是"用户确认后提交"。

- 若 submit 对无 token 的请求**默认放行** → 任何调用方（旧前端、脚本）都能绕过整个 guard 直接 POST submitTask，guard 退化为纯前端装饰，违背"后端决策权威"原则。
- 若 submit **强制要求 token** → 会破坏现有前端的兼容性。

因此本方案将 `confirmToken` 升级为统一的 **`precheckToken`（预检凭证）**，并配套灰度开关解决兼容性问题。

### 10.2 凭证类型

precheck 接口在 `PASS` 与 `CONFIRM_REQUIRED` 两种结果下都签发 `precheckToken`，`BLOCK` 不签发：

- `tokenType = PASS`：规则或模型明确判定允许写作，前端凭此直接 submit。
- `tokenType = CONFIRM`：判定边界不清，前端必须在用户确认后凭此 submit。

凭证内容不变，仅用 `tokenType` 区分来源，便于埋点统计"PASS 直接提交"与"确认后提交"的比例。

### 10.3 服务设计

新增：

- `PrecheckTokenService`（原 `ConfirmTokenService` 更名）

使用 Redis 存储 token，适配多实例部署。

### 10.4 Token 绑定字段

绑定：

- `userId`
- `draftId`
- `taskType`
- `promptHash`
- `tokenType`（`PASS` / `CONFIRM`）
- `issuedAt`
- `expireAt`

**promptHash 范围（必须明确）**：只 hash 稳定、可感知的输入 `taskType + customInstruction + formatInstruction + draftId`，**不纳入 `selectedText`（实时选区）与 `draftContent`（正文可能被自动保存）**。否则用户在 precheck 返回后到点击确认的几秒内，选区变化或正文自动保存会导致 hash 不匹配而被误拒。对"用户修改输入后复用旧确认"的防护，由 `customInstruction` 纳入 hash + token 短有效期共同承担。

### 10.5 有效期

建议有效期：

- 120 秒到 300 秒

### 10.6 submit 侧校验与灰度强制

正式调用 `submitTask` 时：

1. 读取请求中的 `precheckToken`。
2. 若开启强制开关（`ai-writing.guard.enforce=true`）：缺失或无效 token 直接拒绝（返回"请先完成意图预检"）。
3. 若未开启（灰度期 `false`）：缺失 token 放行，但记录 `ai_writing_guard_bypass_total` 告警指标，用于观察旧前端/绕过行为。
4. token 存在时：校验 `userId / draftId / taskType / promptHash / tokenType` 一致且未过期。
5. 校验通过后继续进入现有主链路。

校验失败，返回：

- “确认信息已过期或不匹配，请重新提交”

### 10.7 Token 消费语义与防重锁分工

- **消费语义**：token 采用 consume-once（submit 成功落库后原子删除）。网络重试导致的重复提交不依赖 token 兜底，而是由现有 `AI_TASK_LOCK_PREFIX` 分布式锁（`userId:draftId:taskType`，5 秒）拦截，返回"请勿重复提交"。
- **职责分工**：`precheckToken` 负责"本次请求的意图已通过预检（并已确认），有资格进入链路"（防绕过）；分布式锁负责"同一任务不被并发重复执行"（防重）。两者互补，不互相替代。

---

## 11. 接口设计

### 11.1 新增预检接口

#### URL

`POST /api/v1/ai-writing/task/precheck`

#### Request DTO

建议字段：

- `draftId`
- `taskType`
- `promptParams`
- `enableIllustration`

#### Response DTO

建议字段：

- `decision`：`PASS | BLOCK | CONFIRM_REQUIRED`
- `intent`：`WRITE_ARTICLE | CHAT | DRAW_DIAGRAM | UNKNOWN`
- `confidence`
- `reason`
- `suggestedAction`
- `precheckToken`（`PASS` / `CONFIRM_REQUIRED` 时返回，`BLOCK` 时为 null）
- `tokenType`：`PASS | CONFIRM`
- `tokenExpireSeconds`

### 11.2 扩展提交接口

扩展现有 `SubmitAiTaskRequestDTO`：

- 新增可选字段 `precheckToken`（灰度期可缺省，强制期必填）

### 11.3 submitTask 改造原则

`submitTask` 只增加“前置守卫校验”能力，不动原有核心主链路：

1. 校验请求与用户
2. 校验 `precheckToken`（按 10.6 节的灰度策略：强制期缺失即拒绝，灰度期缺失放行并打点）
3. 通过后继续进入现有：
   - [`AiWritingService.submitTask`](file:///d:/java/scaffold/AgentWrite/sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AiWritingService.java#L113-L139)
   - [`AiWritingService.doSubmitInTransaction`](file:///d:/java/scaffold/AgentWrite/sutone-agent-bok-domain/src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/AiWritingService.java#L146-L173)

---

## 12. 前端交互设计

### 12.1 提交流程改造

`AiWritingPanel.handleAiTask()` 改为：

1. 组装并**冻结 `promptParams` 快照**（含 `customInstruction`、`formatInstruction`，排除实时 `selectedText`），确保 precheck 与 submit 两次请求的 `promptHash` 一致。
2. 调 `precheckTask`，拿到 `decision` 与 `precheckToken`。
3. 根据返回结果决定后续动作。

### 12.2 三类结果处理

#### PASS

- 静默继续
- 携带 `precheckToken`（`tokenType=PASS`）直接调用 `submitTask`

#### BLOCK

按 `suggestedAction` 弹出提示：

- 更像闲聊 -> 直接切换到面板内的“对话写作”Tab（`setMode("chat")`），不离开当前草稿页，编辑上下文不丢失。
- 更像画图 -> 提示后引导到独立路由 `/drawio`（`router.push("/drawio")`），跨页面，保留手动确认。

#### CONFIRM_REQUIRED

弹出确认框：

- “系统无法确定这次请求是否适合进入写作任务，是否仍按写作任务执行？”

用户点击继续后：

- 携带 `precheckToken`（`tokenType=CONFIRM`）调用 `submitTask`

### 12.3 前端跳转策略

区分两类目标入口：

- **面板内 Tab（对话写作）**：`AiWritingPanel` 内部已有 `quick / chat` 两个 Tab，切换不离开当前草稿页、不破坏编辑上下文，因此 BLOCK 为闲聊时可**自动切换**。
- **跨页面路由（Draw.io）**：`/drawio` 是独立页面，自动跳转可能打断当前写作流程，因此保留**手动确认**。

对用户"故意想在当前场景继续"的场景，一律保留手动入口（提示条不强制跳转，用户可关闭后继续操作），保证可控、可解释。

---

## 13. 决策矩阵

| 规则结果 | 模型结果 | 最终结果 |
|---|---|---|
| PASS | 不调用 | PASS |
| BLOCK | 不调用 | BLOCK |
| UNCERTAIN | WRITE_ARTICLE 且高置信度 | PASS |
| UNCERTAIN | CHAT 且高置信度 | BLOCK |
| UNCERTAIN | DRAW_DIAGRAM 且高置信度 | BLOCK |
| UNCERTAIN | UNKNOWN / 低置信度 / 模型异常 | CONFIRM_REQUIRED |

这张决策矩阵是整个方案的核心。

**凭证签发约定**：`PASS` 与 `CONFIRM_REQUIRED` 最终都会签发 `precheckToken`（`tokenType` 分别为 `PASS` / `CONFIRM`），`BLOCK` 不签发。submit 侧依据 token 完成权威校验（见第 10 节）。

---

## 14. 包结构建议

建议新增如下结构：

```text
sutone-agent-bok-domain
└── src/main/java/cn/sutone/ai/domain/agent/service/ai_writing/intent
    ├── WritingIntentGuardService.java
    ├── WritingIntentRuleEngine.java
    ├── WritingIntentModelClassifier.java
    ├── PrecheckTokenService.java
    ├── model
    │   ├── WritingIntentDecisionVO.java
    │   ├── WritingIntentTypeVO.java
    │   ├── WritingIntentPrecheckContextVO.java
    │   └── WritingIntentPrecheckResultVO.java
```

Trigger / API 层新增：

```text
sutone-agent-bok-api
└── src/main/java/cn/sutone/ai/api/dto/aiwriting
    └── intent
        ├── PrecheckAiTaskRequestDTO.java
        └── PrecheckAiTaskResponseDTO.java
```

---

## 15. 与现有主链路的集成点

### 15.1 新增预检，不替换提交

这套方案不修改现有异步执行链路，只在“创建任务前”增加守卫。

### 15.2 推荐接入位置

推荐接入方式：

1. `AiWritingController` 新增 `precheck`
2. `AiWritingController.submitTask` 或 `AiWritingService.submitTask` 增加 `precheckToken` 兜底校验（含灰度开关）

### 15.3 为什么不建议把 guard 放到 MQ 后面

如果已经完成：

- `ai_task` 落库
- `outbox_event` 落库
- MQ 已发送

再去判断用户是否真的要写作，已经太晚了。  
因此 guard 必须发生在建任务前。

---

## 16. 异常与降级设计

### 16.1 规则层异常

- 降级为 `UNCERTAIN`

### 16.2 模型层异常

- 返回 `CONFIRM_REQUIRED`

### 16.3 Token 校验失败

- 返回明确错误码与提示文案

### 16.4 前端预检接口失败

推荐策略：

- 不默认绕过
- 提示用户“预检失败，请重试”

这样更安全，避免预检异常时无条件放过。

### 16.5 灰度开关与回滚

建议引入三个配置开关，支持分阶段上线与快速回滚：

- `ai-writing.guard.rule-enabled`（默认 true）：规则预筛开关。
- `ai-writing.guard.model-enabled`（默认 false）：模型二判开关，先规则后模型。
- `ai-writing.guard.enforce`（默认 false）：submit 侧是否强制要求 `precheckToken`，灰度期 false、全量验证后切 true。

任一环节异常时，按以下顺序降级，保证不阻断正常写作：

1. 规则层异常 → 该请求走 `UNCERTAIN`（若模型未启用则 `CONFIRM_REQUIRED`）。
2. 模型层异常/超时 → `CONFIRM_REQUIRED`。
3. Token 服务异常（Redis 不可用）→ 当 `enforce=false` 时放行 + 打点告警；`enforce=true` 时拒绝并提示重试，避免"异常即绕过"。

---

## 17. 可观测性设计

### 17.1 指标

建议新增指标（统一带 `taskType` / `decision` / `tokenType` 维度，便于定位"哪类任务最易误判"）：

- `ai_writing_precheck_total`
- `ai_writing_precheck_pass_total`
- `ai_writing_precheck_block_total`
- `ai_writing_precheck_confirm_total`
- `ai_writing_precheck_model_timeout_total`
- `ai_writing_confirm_continue_total`
- `ai_writing_block_to_chat_click_total`
- `ai_writing_block_to_drawio_click_total`
- `ai_writing_guard_bypass_total`（灰度期缺失 token 仍放行的计数，观察旧前端/绕过行为）
- `ai_writing_token_verify_fail_total`（token 校验失败计数，含过期/不匹配/不存在）

### 17.2 日志字段

建议记录：

- `userId`
- `draftId`
- `taskType`
- `ruleDecision`
- `modelIntent`
- `confidence`
- `finalDecision`

这样后续可以分析：

1. 被拦最多的是哪类输入
2. 哪类 `taskType` 最容易误判
3. 规则命中是否足够
4. 模型阈值是否需要调整

---

## 18. 测试方案

### 18.1 单元测试

#### RuleEngine

覆盖：

- 闲聊输入
- 画图输入
- 空输入
- 正常写作输入
- `selectedText` 场景
- `SUMMARIZE / GENERATE_TITLE / QUALITY_CHECK` 场景
- `GENERATE_OUTLINE` 且正文为空的场景（应 PASS，不被误判为无效输入）
- 规则优先级冲突场景（闲聊关键词 + 正文充分，应命中 BLOCK）

#### ModelClassifier

覆盖：

- 正常 JSON 解析
- JSON 缺字段
- 低置信度结果
- 超时与异常结果

#### PrecheckTokenService

覆盖：

- 正常 token（`PASS` 与 `CONFIRM` 两种 `tokenType`）
- 过期 token
- `promptHash` 不匹配
- `taskType` 不匹配
- 缺失 token（灰度期放行 + 强制期拒绝）
- 一次性消费（consume-once）语义

### 18.2 集成测试

覆盖：

1. `precheck -> PASS -> submit(precheckToken)`
2. `precheck -> BLOCK`
3. `precheck -> CONFIRM_REQUIRED -> submit(precheckToken)`
4. 模型异常 -> `CONFIRM_REQUIRED`
5. token 过期 -> submit 失败
6. 灰度期缺失 token -> submit 放行 + 打点
7. 强制期缺失 token -> submit 拒绝

### 18.3 回归测试

必须确保以下链路不受影响：

- `submitTask -> doSubmitInTransaction`
- `ai_task + outbox_event`
- Outbox 即时投递与定时补发
- RocketMQ Consumer
- `executeTask`
- 配图链路

---

## 19. 分阶段实施建议

### Phase 1：规则预检 + 预检凭证闭环

实现：

- `precheck` 接口 + `RuleEngine`
- `PrecheckTokenService`（`PASS` / `CONFIRM` 双类型签发）
- submit 侧 `precheckToken` 灰度校验（`enforce=false`，缺失放行 + 打点）
- 前端预检提示（对话写作切 Tab / Draw.io 引导）

目标：

- 先挡住明显闲聊、明显画图和明显空输入
- 建立"预检 → 凭证 → 提交"闭环，保证后续可强制收紧

### Phase 2：模型二判

实现：

- `ModelClassifier`（注册轻量分类 Agent）
- `UNCERTAIN` -> 模型判别
- 预检限流与结果缓存

目标：

- 提升边界请求处理质量，控制模型成本

### Phase 3：全量强制与可观测

实现：

- `ai-writing.guard.enforce=true`，全量强制 `precheckToken`
- 清理灰度 bypass 告警，完善指标维度

目标：

- 彻底杜绝绕过，Guard 转为权威强制

---

## 20. 风险与权衡

### 20.1 风险

1. 规则写得过严，可能误杀真实写作请求
2. 模型判别存在不稳定性
3. 前端交互流程变复杂
4. 需要新增一部分监控与测试成本
5. 模型二判引入额外调用成本，需通过限流与结果缓存控制
6. 强制期（`enforce=true`）对未接入 precheck 的旧前端不兼容，需灰度充分后再收紧

### 20.2 权衡

本方案选择：

- 让规则只拦高确定性请求
- 把语义边界交给模型
- 把模型不确定性交给用户确认

这是一种“工程上稳妥优先”的设计，而不是追求纯自动化。

---

## 21. 结论

本方案通过在快捷写作任务创建前新增一层“意图守卫”，以最小侵入方式解决当前项目中“非写作请求误入写作链路”的问题。

其核心机制为：

1. **前端先预检**
2. **后端规则预筛**
3. **模型处理边界语义**
4. **用户确认兜底**
5. **precheckToken 保证提交一致性与防绕过**
6. **原有 MQ 主链路保持不变**

这是当前项目下兼顾：

- 工程稳定性
- 用户体验
- 模型成本
- 异步链路完整性

的一种平衡方案。
