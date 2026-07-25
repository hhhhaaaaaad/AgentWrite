# AI 写作「标题/正文错乱」问题排查与修复总结

> 关联设计文档：`docs/ai-writing-markdown-routing-design.md`
> 涉及模块：`sutone-agent-bok`（后端）、`sutone-agent-bok-front`（前端）
> 场景：编辑器快捷操作「生成大纲 / 续写正文」

---

## 1. 问题背景

AI 写作快捷操作中，「生成大纲」的排版基本正常，但「续写正文」的最终 Markdown 频繁错乱，典型现象：

1. **标题吞正文**：`### 2.1单体架构的演进单体架构并非一无是处……` 整行被渲染成一个 Heading，正文被吞进标题里。
2. **代码围栏语言与代码粘连**：` ```javapublic void m() ` 应为 ` ```java ` 换行再接代码；CommonMark 会把 `javapublic` 当成语言标识。
3. **数字被换行打断**：`Spring Boot3.4` → `3.` 换行 `4`；`v2.3.0`、`127.0.0.1` 同样断裂。
4. **一次生成出现两份全文**：措辞不同的两篇文章被追加进草稿。
5. **返回对话式内容**：续写正文返回「文章已完整，如果你希望扩展可以告诉我方向……」而不是正文。
6. **结构化块原始 JSON 泄漏**：`{"type":"md_heading","level":3,"text":"1.1微服务架构背景"}` 直接显示在正文里。

根本矛盾（沿用设计文档结论）：**不同快捷操作需要不同的输出协议，但后处理链路早期没有按任务类型分流，且「格式正确性」长期依赖模型自觉，而非代码保证。**

---

## 2. 方案总体演进

整个修复按「先分流止血 → 再收敛协议 → 最后结构化根治」推进，分多轮迭代：

- **阶段一：按任务类型分流**（策略化）
- **阶段二：正文治理档位调整 + 预处理修 bug + Prompt 收敛**
- **阶段三：会话无状态化（去历史污染）**
- **阶段四：流式去重（前后端）**
- **阶段五：结构化块输出协议（根治标题吞正文）**
- **阶段六：结构化块解析健壮化（修 JSON 泄漏）+ DB 列扩容**

---

## 3. 中间踩过的坑

这部分是本次排查最有价值的经验，按发现顺序记录。

### 坑 1：把「正文治理」降档为轻治理，反而更糟
- 初期为避免过度格式化，把 `GENERATE_BODY` 的 Markdown 策略设为 `ARTICLE_LIGHT`（只跑正则预处理、跳过 CommonMark AST）。
- 结果：正文块之间不再补空行，畸形暴露得更明显。
- 教训：**正文类内容恰恰最需要 AST 级块结构治理**，「默认轻治理」的假设在当前模型输出质量下不成立。→ 回退为 `ARTICLE_STRICT`。

### 坑 2：写了一条「长标题智能拆分」正则，制造了更坏的输出
- 为拆分「标题+正文粘连」，加了按长度/边界切分的正则规则（8b）。
- 结果：把 `### 1.1微服务架构背景2010年前后...` 拆成了 `### ` 空标题 + 正文段落（`###` 被孤立），甚至把「异步处理」切成「异步处 / 理」。
- 教训：**标题与正文之间没有任何分隔符时，正则/AST 无法可靠定位标题边界，硬拆只会帮倒忙。** → 删除该规则；根治交给结构化协议 + 生成侧 Prompt。

### 坑 3：SSE 对 PENDING 状态用异常表达，导致 500
- 前端提交后立刻建 SSE，此时任务常还在 PENDING（MQ 未抢占），旧代码用 `completeWithError(AppException)` 表达「排队中」，被 Spring MVC 当异常抛出 → HTTP 500 + 满屏堆栈。
- 教训：**「排队中」是正常状态，不该用异常表达。** → 改为发 `pending` 状态事件并进入订阅循环等待转 RUNNING。

### 坑 4：两份全文来自「前端拼接了两个阶段的 token 流」
- `GENERATE_BODY` 走 analyst→generator→reviewer，后端把 generating 和 reviewing 两个阶段的 token 都推给前端，各是一份完整文章（措辞不同）。
- 前端 `aiResultBuffer += token` 无差别拼接，且后端 MQ 路径**从不发 `result` 事件**，导致采纳时用了拼了两份的 buffer。
- 教训：**流式预览与最终落地必须区分**——预览可以看过程，落地只认权威终稿。

### 坑 5：对话式回复来自「历史对话上下文污染」
- `ChatService.createSession` 每次都调 `recoverHistoryContext`，把该 user+agent 最近 20 条 `chat_message`（含此前生成的整篇文章）作为「历史对话上下文」注入会话。
- 加上草稿本身已是完整文章，模型据此判断「文章已完整」，返回对话式说明。
- 教训：**一次性快捷操作应是无状态单发，不该继承多轮对话历史。**

### 坑 6：结构化块被模型的「杂散换行」打断，原始 JSON 泄漏
- 改用结构化块协议后，reviewer 按行输出 `{"type":"md_heading",...}`，但模型在 `1.1` 处插入真实换行，把一个 JSON 对象拆成两行。
- 旧的「按 `\n` 逐行判断块」逻辑要求整行以 `{` 开头、`}` 结尾，被拆断后两段都不被识别为块 → 原始 JSON 泄漏成正文。
- 教训：**不能假设「一行一个 JSON」**，模型会在任意位置插换行。→ 改为按花括号配对提取。

### 坑 7：写作输出撑爆 `chat_message.content`
- 写作任务把 analyst+generator+reviewer 的完整拼接作为一条 assistant 消息落库，reviewer 改结构化 JSON 后体量更大，超过 `TEXT`（65535 字节）上限，报 `Data too long for column 'content'`。
- 属非致命（`persistMessage` 已 try-catch），但刷错误日志且存不进库。→ 列类型升 `LONGTEXT`。

---

## 4. 最终方案

### 4.1 按任务类型分流（策略层）
- 新增 `MarkdownPolicyVO`（NONE/PLAIN_TEXT/PLAIN_LINES/TAGS/INLINE_LIGHT/OUTLINE_LIGHT/ARTICLE_LIGHT/ARTICLE_STRICT/REPORT_LIGHT）。
- 新增 `AiWritingTaskStrategy` + `AiWritingTaskStrategyResolver`，按 `taskType`（及 `POLISH_TEXT` 是否选中文本）决定 `useReviewer` 与 `markdownPolicy`：
  - `GENERATE_OUTLINE` → 不走 reviewer，OUTLINE_LIGHT
  - `GENERATE_BODY` / 全文润色 → 走 reviewer，**ARTICLE_STRICT**（AST 级块治理）
  - `POLISH_TEXT`（选中文本）→ 不走 reviewer，INLINE_LIGHT
  - `SUMMARIZE / GENERATE_TITLE / GENERATE_TAGS / QUALITY_CHECK` → 不走 reviewer，PLAIN_TEXT/PLAIN_LINES/TAGS/REPORT_LIGHT
- `AgentWritingRunner` 按策略决定是否消费 reviewer 输出、用哪种 Markdown 策略。

### 4.2 结构化块输出协议（根治标题吞正文）
- 复用已有的 `MarkdownBlockRenderer`（层四：结构化输出 + 确定性渲染）。
- 重写 `agent_writing_reviewer` 的 instruction：不再输出自由 Markdown，而是**逐个输出 JSON 块**，核心约束：**标题只放标题文字，正文放独立 `md_paragraph` 块，严禁把正文塞进标题 text**。
- 后端确定性渲染标题/正文/代码/表格，空行与层级由代码保证——标题吞正文在结构上不可能发生；代码围栏、表格对齐问题一并消除。

### 4.3 结构化块解析健壮化（修 JSON 泄漏与数字断裂）
- `MarkdownBlockRenderer` 新增 `extractTopLevelObjects(raw)` + `matchBrace`：按**花括号配对**（感知字符串与转义）从 reviewer 完整输出中切出每个顶层 JSON 对象，不依赖换行。
- `AgentWritingRunner` reviewer 分支改为**累积全部原始输出 → 流结束后 `renderReviewerBlocks`**：
  - 提取每个 JSON 对象；
  - 剔除对象内部的**真实回车换行**（`replaceAll("[\r\n]","")`），修复 `1.\n1`→`1.1`、`v2.\n3.0`→`v2.3.0`；代码块里被转义的 `\n`（反斜杠+n）保留；
  - 交 `renderLine` 渲染；
  - 无法解析出块时回退为原文当 Markdown 落库，不丢内容。

### 4.4 Markdown 预处理增强（`MarkdownNormalizer`）
- 代码围栏语言与代码粘连拆行：` ```javapublic ` → ` ```java ` 换行 `public`（按长优先匹配常见语言）。
- 删除有 bug 的「长标题按长度硬切」规则（避免孤立 `###`、切碎词）。
- 保留 `##标题`→`## 标题` 补空格、`**` 边界拆分等安全规则。
- 策略化入口 `normalize(raw, policy)`：短文本走轻治理，正文走 `ARTICLE_STRICT`（CommonMark AST 重排）。

### 4.5 生成侧 Prompt 收敛（`agent-writing.yml`）
- 删除误导模型的「加/n换行符」字面文本。
- `GENERATE_BODY`：强调标题独占一行、代码围栏后换行；**严禁对话式内容**（「文章已完整」「如果你希望…」）与建议清单；正文已完整时补充细节而非回复「无需续写」。
- `POLISH_TEXT`：选中文本只润色选中部分；全文润色保留结构、不把大纲扩写为正文、不新增章节。

### 4.6 会话无状态化（去历史污染）
- `IChatService` 新增 `createSession(agentId, userId, boolean recoverHistory)`；原方法委托为 `true`，聊天功能不变。
- `AgentWritingRunner` 的写作/配图/drawio 会话改为 `recoverHistory=false`——快捷操作不再加载历史对话，避免模型误判「文章已完整」。

### 4.7 流式去重（前后端一致性）
- 后端 `executeTask` 成功后、`publishDone` 前，**补发 `result` 事件**（携带治理后的全文终稿）。
- 前端 `AiWritingPanel`：`token` 事件在阶段切换（generating→reviewing）时重置预览缓冲；采纳时优先用 `result` 的权威终稿。
- 追加/替换由前端按钮控制；纯大纲草稿扩写建议用「替换」。

### 4.8 数据库列扩容
- 迁移脚本 `docs/dev-ops/mysql/sql/15-...-chatmessage-longtext.sql`：`chat_message.content` 由 `TEXT` 提升为 `LONGTEXT`。

---

## 5. 关键文件改动清单

- `sutone-agent-bok-domain/.../valobj/MarkdownPolicyVO.java`（新增）
- `sutone-agent-bok-domain/.../ai_writing/strategy/AiWritingTaskStrategy.java`（新增）
- `sutone-agent-bok-domain/.../ai_writing/strategy/AiWritingTaskStrategyResolver.java`（新增）
- `sutone-agent-bok-domain/.../ai_writing/AgentWritingRunner.java`（策略分流、reviewer 块解析、无状态会话）
- `sutone-agent-bok-domain/.../ai_writing/AiWritingService.java`（Prompt 收敛、补发 result 事件）
- `sutone-agent-bok-domain/.../ai_writing/markdown/MarkdownNormalizer.java`（策略化 + 预处理增强）
- `sutone-agent-bok-domain/.../ai_writing/markdown/MarkdownBlockRenderer.java`（`extractTopLevelObjects` + `matchBrace`）
- `sutone-agent-bok-domain/.../service/IChatService.java` + `chat/ChatService.java`（无状态会话重载）
- `sutone-agent-bok-trigger/.../http/AiWritingController.java`（PENDING 不再抛异常）
- `sutone-agent-bok-app/src/main/resources/agent/agent-writing.yml`（reviewer 结构化块协议、generator Prompt）
- `sutone-agent-bok-front/src/components/AiWritingPanel/index.tsx`（阶段切换重置预览、以 result 为准）
- `docs/dev-ops/mysql/sql/15-sutone-agent-bok-phase15-chatmessage-longtext.sql`（新增迁移）

---

## 6. 验证与部署

- 单测覆盖：`MarkdownNormalizerTest`、`MarkdownBlockRendererTest`、`AgentWritingRunnerTest`（含结构化块分行、块内杂散换行、短文本跳过 reviewer）、`AiWritingServiceTest`、`ChatServiceTest`，全部通过。
- 前端：`tsc --noEmit` 与 ESLint 通过。
- 部署步骤：
  1. 数据库执行 `15-...-chatmessage-longtext.sql`。
  2. 重新构建并重启后端（Prompt、策略、块解析、会话改动均需重启生效）。
  3. 重新构建前端。
- 验证用例：**干净大纲草稿 → 续写正文 → 选「替换」**，预期得到标题/正文分行、代码块规整、无重复、无对话式内容、无原始 JSON 的完整文章。

---

## 7. 遗留与后续

- **多 Agent 成本**：短文本任务（摘要/标题/标签/大纲）仍会白跑 analyst+reviewer 两次 LLM 调用（策略层只丢弃 reviewer 输出，未跳过调度）。根治需为短文本配置 single-agent workflow 并在 runner 按策略切换 agentId。
- **结构化块依赖模型稳定输出合法 JSON**：已对「块内杂散换行」做健壮化；若后续发现长 `md_code`（含引号/大量转义）易致解析异常，可进一步对 code 块做分段或转义加固。
- **RAG 记忆注入**：`buildPrompt` 仍注入 `memoryManager.retrieveContext`，理论上也可能召回历史正文；当前未处理，如再现旧内容引用可对快捷操作跳过记忆注入。
- **`AiWritingService.generateStream`** 为遗留/测试路径（生产走 MQ 的 `executeTask → AgentWritingRunner`），其 reviewer 处理未同步结构化块解析改造。
