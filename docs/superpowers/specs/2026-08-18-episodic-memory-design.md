# Episodic Memory 设计（V1）

## 背景

当前 `01myBlog` 已有两层记忆：

- `Short-term Context`：最近约 40 条消息，直接注入 prompt。
- `Semantic / User Memory`：`ai_user_memories` + `ai_user_memory_candidates`，覆盖 PROFILE / PREFERENCE / PROJECT_STATE。

现有链路已经能回答“用户是谁、喜欢什么、项目当前状态是什么”，但还不能稳定回答：

```text
我们之前为什么决定用 ES？
RAG 当时为什么走这个方案？
那次学习计划修改是怎么拍板的？
```

因此新增第三类长期记忆：

```text
Episodic Memory：记录过去发生过的重要事件、决策、里程碑和计划。
```

## 目标

V1 只验证一个完整闭环：

```text
历史对话
→ 异步抽取
→ LLM 判断是否值得记忆
→ 生成情景记忆
→ MySQL 保存
→ ES 向量写入
→ 未来语义检索
→ 注入 prompt
```

要解决的是“过去发生了什么、为什么这么做、最后怎么决定的”。

## V1 原则

1. 不存原始聊天全文，不做“全部消息都入库”。
2. 不让用户逐条确认情景记忆。
3. 不做独立向量索引，V1 先复用现有 `spring-ai-document-index`，用 `source=episodic_memory` 区分。
4. 不做时间衰减、记忆图谱、跨用户召回、自动演化到 Semantic。
5. 只保留少量高价值事件，控制噪声优先。

## 与现有 Memory 的边界

### Semantic Memory 回答“现在是什么”

例如：

- 用户偏好：喜欢先看结论再看细节。
- 项目状态：RAG 使用 ES，学习计划处于进行中。

### Episodic Memory 回答“过去发生了什么”

例如：

- 2026-08-17 讨论 ES 与 PGVector，最终决定使用 ES。
- 2026-08-17 学习计划阶段二压缩后，任务数收口到 5 个。

两者允许信息重叠，但职责不同。

## 数据模型

建议新增表：`ai_episodic_memories`

### 字段建议

```text
id
user_id
session_id
project_key
memory_type
title
content
importance
confidence
source_message_ids
content_hash
occurred_at
created_at
updated_at
last_retrieved_at
retrieval_count
```

### 字段说明

- `user_id`：用户隔离。
- `session_id`：来源会话。
- `project_key`：项目隔离，当前默认可写死为 `01myBlog`，以后再扩多项目。
- `memory_type`：`DECISION` / `EVENT` / `MILESTONE` / `PLAN`。
- `title`：短标题，方便列表和调试。
- `content`：压缩后的历史事件描述，必须保留原因。
- `importance`：1-10，控制是否入库和排序。
- `confidence`：0-1，控制是否入库和排序。
- `source_message_ids`：JSON 数组，记录这条记忆来自哪些消息，不用单个 `message_id`。
- `content_hash`：去重用。
- `occurred_at`：事件发生时间，默认取源消息窗口中的最后一条时间。
- `last_retrieved_at` / `retrieval_count`：用于简单可观测性和后续调优。

## 记忆类型

V1 只保留 4 类：

```text
DECISION    做出的决定
EVENT       发生的重要事件
MILESTONE   重要里程碑
PLAN        后续计划
```

不保留 `DISCUSSION`，因为太泛，容易把普通聊天也存进去。

## 触发策略

采用“双触发”：

```text
事件触发为主
每 N 轮窗口兜底
```

### 事件触发

当对话里出现明显信号时，直接触发抽取，例如：

- 决定 / 选择 / 最终 / 就用 / 拍板
- 完成 / 上线 / 已经做完 / 里程碑 / 这阶段结束
- 以后准备 / 后面再做 / 暂缓 / 先不做

实现约束：

- 只使用用户消息作为事件触发源，AI 回复中的“阶段”“下一步”“准备”等普通措辞不能触发抽取。
- 触发词优先使用带有明确动作或结论的短语，例如“最终决定”“选用”“已完成”“部署”“后面再做”“下一步做”。
- 仅有 AI 建议、用户继续提问、用户没有反驳，不能推断为用户确认。

### 窗口兜底

不要只喂单轮消息，应该喂最近 N 轮窗口，默认先取 10 轮。

原因是 Episodic 经常跨轮形成：

- 第 1 轮：讨论方案
- 第 3 轮：比较利弊
- 第 5 轮：最终决定

只看单轮会丢掉“为什么”。

### 异步执行

记忆抽取继续放在聊天 `STOP` 之后异步执行，不阻塞主回复。

## 抽取策略

V1 采用：

```text
规则预筛
→ LLM 判断 shouldRemember
→ 生成结构化事件
→ 质量门槛过滤
→ 落库 + 向量化
```

### 结构化输出建议

```json
{
  "shouldRemember": true,
  "memoryType": "DECISION",
  "title": "RAG 选用 ES",
  "content": "用户和 AI 讨论 RAG 方案后，最终决定使用 ES，因为需要同时支持 Keyword Search 和 Vector Search。",
  "importance": 8,
  "confidence": 0.91,
  "sourceMessageIds": [123, 124, 125],
  "occurredAt": "2026-08-17 21:30:00"
}
```

### 质量门槛

- `confidence < 0.6` 不保存。
- `importance < 6` 不保存。
- `content` 为空不保存。
- `content_hash` 命中相似旧记忆则忽略。

## 去重策略

V1 不做复杂 MERGE 流程，优先简单稳定：

- 先用 `content_hash` 做硬去重。
- 再用 ES 相似检索做软去重。
- 命中高相似旧记忆时直接 `IGNORE`。

这样可以避免“同一件事被反复存很多遍”。

## ES 设计

### V1 方案

继续复用现有索引：

```text
spring-ai-document-index
```

通过 metadata 增加区分字段：

```text
source=episodic_memory
userId=...
projectKey=...
memoryType=...
importance=...
confidence=...
occurredAt=...
contentHash=...
sourceMessageIds=...
```

### 理由

- 当前系统已经有共享索引实践。
- V1 的主要问题是“记忆质量”，不是“索引分裂”。
- 先共享索引，验证闭环；若后续噪声、规模或查询模式不合适，再拆独立索引。

## 检索策略

### 查询流程

```text
当前问题
→ embedding
→ ES episodic_memory 过滤检索
→ topK
→ 去噪排序
→ prompt 注入
```

### 检索过滤

必须先过滤：

- `source = episodic_memory`
- `userId = 当前用户`
- `projectKey = 当前项目`

### 检索排序

V1 先用简单权重：

```text
finalScore = similarity × importance
```

可选再加一点时间偏置，但不做正式时间衰减。

### 注入上限

- 最多注入 3 条。
- 每条长度控制在 200 字左右。
- 只在和当前问题相关时注入，不机械复述。

## 接入点

### 普通聊天

在 `AiPromptServiceImpl.buildPrompt` 中，长期记忆段后面追加：

```text
## 历史事件记忆
```

这里与现有 `Semantic / User Memory` 分开，不混写。

### Workflow

在 `WorkflowKnowledgeSupport.retrieveMemoryContext` 的返回内容里同样拼入 Episodic Memory。

这样普通聊天和 Workflow 共享同一套检索逻辑，但 prompt 段分开。

### 提取侧

在 `AiMessageServiceImpl` 的 `stopEvent` 后并行触发 Episodic Extractor，与现有 `extractAfterChat` 解耦。

## 明确不做

V1 不做这些：

- 不做用户逐条确认。
- 不做独立 `episodic-memory-index`。
- 不做记忆图谱。
- 不做自动时间衰减。
- 不做 Episodic → Semantic 自动演化。
- 不做跨项目默认召回。
- 不做完整管理后台。
- 不做复杂 MERGE 流程。

## 风险与处理

### 风险 1：重复记忆

处理：`content_hash` + ES 相似去重。

补充：事件触发不再检查 AI 回复，避免模型的常规规划话术把每轮聊天都送入情景记忆抽取。

### 风险 2：噪声太多

处理：事件触发 + 10 轮窗口兜底 + 置信度/重要性门槛 + topK 限制。

### 风险 3：跨项目串扰

处理：`projectKey` 过滤，默认值先固定为 `01myBlog`。

### 风险 4：回答时塞太多记忆

处理：最多 3 条，按相关性和重要性排序。

## 预期结果

做完 V1 后，系统应该能回答：

```text
我们之前为什么决定用 ES？
为什么当时学习计划要压缩阶段二？
那次项目推进时我们先做了什么、后做了什么？
```

同时不会因为大量闲聊、重复事件或低质量摘要污染 prompt。
