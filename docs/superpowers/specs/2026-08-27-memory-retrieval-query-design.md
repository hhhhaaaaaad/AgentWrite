# 记忆检索 Query 工程化改造设计

## 背景

当前 AI 写作链路在构造记忆注入上下文时，仅使用草稿正文作为检索 query：

- `AiWritingService.buildPrompt()`
- `memoryManager.retrieveContext(draft.getUserId(), draft.getContentMd(), 5)`

该实现存在三个直接问题：

1. 首轮生成大纲或正文时，`contentMd` 可能为空，导致记忆注入失效。
2. 即使标题、摘要、用户额外指令具有更强主题信号，当前检索也无法利用。
3. 语义检索与关键词检索复用同一段原始长文本，不利于后续做 query 标准化和缓存稳定化。

## 目标

本次改造将记忆检索输入从“单字符串正文”升级为“结构化检索上下文”，并在检索阶段拆分为双通道 query：

- `semanticQuery`：用于 embedding、Qdrant 语义召回、Reranker query
- `lexicalQuery`：用于 MySQL FULLTEXT 关键词召回

同时升级搜索缓存 key，避免继续依赖原始 query 的 `hashCode()`。

## 范围

本次改造包含：

1. `AiWritingService` 构造结构化记忆检索 query
2. 新增 query 值对象和标准化器
3. `MemoryManager`、`MemoryRetriever` 增加结构化 query 重载
4. `MemoryRetriever` 内部接入双通道 query 和新搜索缓存 key
5. 为核心规则补充单元测试

本次不包含：

1. 画像缓存筛选逻辑重构
2. 融合评分公式调整
3. `content_tokenized` 接入 FULLTEXT
4. 中文分词器或 LLM 关键词抽取接入

## 设计概览

### 新增对象

#### `MemoryRetrieveQueryVO`

位置：

- `cn.sutone.ai.domain.agent.model.valobj.MemoryRetrieveQueryVO`

职责：

- 承载原始检索上下文

字段：

- `taskType`
- `title`
- `summary`
- `contentMd`
- `selectedText`
- `customInstruction`
- `formatInstruction`

#### `NormalizedMemoryQueryVO`

位置：

- `cn.sutone.ai.domain.agent.model.valobj.NormalizedMemoryQueryVO`

职责：

- 承载标准化后的检索结果

字段：

- `queryMode`
- `semanticQuery`
- `lexicalQuery`
- `canonicalText`
- `cacheKeyDigest`

#### `MemoryQueryNormalizer`

位置：

- `cn.sutone.ai.domain.agent.service.memory.MemoryQueryNormalizer`

职责：

1. 清洗原始字段
2. 按任务类型构造 query
3. 生成双通道 query
4. 生成 canonical text
5. 生成 cache digest

## 调用链改造

### `AiWritingService`

新增私有方法：

- `buildMemoryRetrieveQuery(DraftEntity draft, AiWritingTaskTypeVO taskType, Map<String, Object> promptParams)`

现有：

- `retrieveContext(userId, draft.getContentMd(), 5)`

改造后：

1. 构造 `MemoryRetrieveQueryVO`
2. 调用 `memoryManager.retrieveContext(userId, memoryQuery, 5)`

### `MemoryManager`

保留旧方法：

- `retrieveContext(Long userId, String queryContext, int topK)`

新增重载：

- `retrieveContext(Long userId, MemoryRetrieveQueryVO query, int topK)`

旧方法内部包装为最简 `MemoryRetrieveQueryVO`，保证兼容。

### `MemoryRetriever`

保留旧方法：

- `search(Long userId, String query, int topK)`
- `retrieveFormattedContext(Long userId, String queryContext, int topK)`

新增重载：

- `search(Long userId, MemoryRetrieveQueryVO query, int topK)`
- `retrieveFormattedContext(Long userId, MemoryRetrieveQueryVO query, int topK)`

新重载内部先通过 `MemoryQueryNormalizer` 生成 `NormalizedMemoryQueryVO`，再执行检索：

- `semanticQuery` -> embedding + Qdrant
- `lexicalQuery` -> FULLTEXT
- `cacheKeyDigest` -> Redis search cache

## Query 构造规则

### `GENERATE_OUTLINE`

优先字段：

- `title`
- `summary`
- `customInstruction`

正文仅在非空时少量补充。

### `GENERATE_BODY`

优先字段：

- `title`
- `summary`
- `contentMd` 片段
- `customInstruction`

正文不取全文，仅取有限片段。

### `POLISH_TEXT`

若有 `selectedText`：

- 优先 `selectedText`
- 再补 `title`、`summary`、`customInstruction`

若无 `selectedText`：

- 使用 `title`、`summary`、`contentMd` 片段、`customInstruction`

## 双通道 Query 规则

### `semanticQuery`

目标：

- 保持语义完整
- 适配 embedding 与 reranker

构成：

- `taskType`
- `title`
- `summary`
- `selectedText` 或 `contentMd` 片段
- `customInstruction`

### `lexicalQuery`

目标：

- 保持关键词密度高
- 降低自然语言噪声
- 适配 FULLTEXT

构成：

- 主题词
- 技术术语
- 任务动作词
- 风格词

V1 使用规则提取，不引入 LLM 或复杂分词。

## 标准化规则

### 文本清洗

1. 去掉多余空白和重复换行
2. 去掉 Markdown 噪声
3. 去掉代码块、图片链接、URL

### 字段裁剪

- `title`：全保留
- `summary`：最多 300 字
- `selectedText`：最多 500 字
- `contentMd`：最多 500 字片段
- `customInstruction`：最多 200 字
- `formatInstruction`：弱参与，不进入 lexicalQuery

### 正文策略

V1 采用简单规则：

- 正文非空时取前 500 字

后续如需优化，可升级为“章节标题 + 局部片段”策略。

## 搜索缓存 key 升级

现状：

- `memory:user:{userId}:search:{query.hashCode()}`

改造后：

- `memory:user:{userId}:search:v2:{digest}`

其中：

- `digest = md5(canonicalText)`

`canonicalText` 为标准化后的固定顺序文本，例如：

- `taskType=...`
- `title=...`
- `summary=...`
- `selectedText=...`
- `customInstruction=...`
- `contentSnippet=...`

## 兼容策略

1. 旧签名不删除
2. 新签名以重载方式添加
3. 旧逻辑内部包装为最简 query 对象
4. 首先只升级 AI 写作链路，其他调用方不受影响

## 测试方案

### `MemoryQueryNormalizerTest`

覆盖：

1. `GENERATE_OUTLINE` 且正文为空时仍生成有效 query
2. `POLISH_TEXT` 且有 `selectedText` 时优先使用选中文本
3. `lexicalQuery` 不是原始长文本回填
4. `cacheKeyDigest` 对同样输入稳定一致

### `MemoryRetrieverTest`

覆盖：

1. `semanticQuery` 用于 embedding
2. `lexicalQuery` 用于 FULLTEXT
3. search cache key 使用 `cacheKeyDigest`

### `AiWritingServiceTest`

覆盖：

1. 首轮正文为空但标题/摘要存在时，仍可构造记忆检索 query
2. `selectedText`、`customInstruction` 可以参与 query 构造

## 实施顺序

1. 新增 `MemoryRetrieveQueryVO`
2. 新增 `NormalizedMemoryQueryVO`
3. 新增 `MemoryQueryNormalizer`
4. 先补 `MemoryQueryNormalizerTest`
5. 修改 `MemoryRetriever`
6. 修改 `MemoryManager`
7. 修改 `AiWritingService`
8. 补齐相关测试并执行

## 风险与边界

1. `lexicalQuery` 裁剪过强可能导致关键词过少，V1 应保守实现
2. 正文片段策略仍较简单，但已优于直接整篇正文参与
3. 关键词检索底层仍查 `content`，本次不扩展到 `content_tokenized`
4. 画像缓存和融合评分结构保持不变，避免本次范围膨胀

## 结论

本次改造的本质，是把“记忆检索只吃正文字符串”升级为“写作场景下的结构化检索上下文”，并通过 `semanticQuery / lexicalQuery` 双通道为后续继续优化混合检索留出明确演进点。
