# AI Memory 项目总结与简历讲法

本文用于总结 `01myBlog` 中 AI 长期记忆能力的实现思路、核心链路、关键代码和面试表达。它不是临时开发记录，而是用于复盘项目、准备简历和后续接续开发。

## 项目定位

`01myBlog` 的 Memory 能力不是简单把聊天记录塞回 prompt，而是为全站 AI 助手增加长期个性化上下文。

它要解决的问题是：

```text
用户跨会话使用 AI 助手时，系统能够记住用户画像、回答偏好和项目推进状态，让后续回答更贴合用户，而不是每次都从零开始。
```

一句话概括：

```text
基于 Spring Boot + Spring AI + MySQL + Elasticsearch，为博客 AI 助手设计并实现长期记忆系统，支持记忆候选提取、ES 向量召回相似旧记忆、LLM 决策 CREATE / UPDATE / MERGE / IGNORE、用户确认、正式记忆管理、prompt 注入和过期清理。
```

## 为什么要做 Memory

RAG 解决的是“AI 如何知道站内文章内容”，Memory 解决的是“AI 如何知道当前用户是谁、喜欢怎样被回答、项目推进到了哪一步”。

两者关注点不同：

```text
RAG：
  面向站内知识
  来源是文章内容
  解决回答有没有依据

Memory：
  面向用户长期上下文
  来源是用户确认或明确表达的信息
  解决回答是否贴合当前用户
```

真实产品中的 Memory 通常也包含这些能力：

- 从对话中发现长期有价值的信息。
- 把信息整理成稳定记忆。
- 用户可以查看、确认、忽略、删除或关闭。
- 新对话时按相关性、重要性和可信度注入上下文。
- 避免把临时闲聊、一次性报错和敏感内容写入长期记忆。

本项目采用更保守的实现：AI 不能直接污染正式记忆，必须先进入候选队列，用户确认后才进入正式记忆。

当前 Memory 更新已经不是单纯靠 `memory_type + memory_key` 判断是否覆盖旧记忆，而是采用：

```text
Embedding 负责找候选旧记忆
LLM 负责判断如何处理
数据库负责执行用户确认后的动作
```

这样可以避免“向量相似但业务上不是同一条记忆”的误覆盖问题。

## 记忆类型

当前只做三类长期记忆：

```text
PROFILE
用户画像，例如学习阶段、技术方向、长期目标。

PREFERENCE
用户偏好，例如回答风格、学习方式、写代码方式。

PROJECT_STATE
项目状态，例如当前正在做什么、已完成什么、决定暂缓什么。
```

不做泛化的“什么都记”，原因是 Memory 越泛化越容易污染 prompt。先限制类型，能让提取、确认、展示和注入都更稳定。

## 数据模型

### 正式记忆表

正式记忆表是 `ai_user_memories`。

它保存用户确认后真正会进入 prompt 的长期记忆。

核心字段：

```text
user_id
所属用户

memory_type
记忆类型：PROFILE / PREFERENCE / PROJECT_STATE

memory_key
记忆键，用于同类记忆去重和更新

content
自然语言记忆内容，方便直接拼进 prompt

source
来源：USER_EXPLICIT / USER_CONFIRMED / SYSTEM_SUMMARY

confidence
可信度，0-1

importance
重要性，1-10

enabled
是否启用，删除正式记忆时只停用，不物理删除
```

正式记忆通过唯一键控制同类记忆只有一条：

```sql
UNIQUE KEY uk_user_type_key (user_id, memory_type, memory_key)
```

这样同一个用户的 `PREFERENCE.learning_style` 不会无限新增，而是更新为最新版本。

### 候选记忆表

候选记忆表是 `ai_user_memory_candidates`。

它保存 AI 从对话中提取出来、等待用户确认的记忆候选。

核心字段：

```text
user_id
所属用户

session_id / message_id
候选来源于哪次会话和消息

memory_type / memory_key / content
候选记忆内容

candidate_action
CREATE / UPDATE / MERGE / IGNORE，用于说明确认后是新增、覆盖、合并还是忽略

reason
AI 提取这条候选的原因，给前端展示

decision_reason
AI 判断更新动作的原因，给前端展示和调试

merged_content
MERGE 动作对应的合并后内容，确认时会写入正式记忆

status
PENDING / CONFIRMED / REJECTED

target_memory_id
UPDATE / MERGE 候选可关联正式记忆 ID

created_at / updated_at / decided_at
用于候选排序、确认时间和过期清理
```

候选表没有对 `user_id + memory_type + memory_key` 加唯一约束，而是在 service 层处理同 key 或同 `target_memory_id` 的 `PENDING` 更新。这样更灵活：候选可以保留状态历史，同时前端不会堆出大量重复待确认项。

## 核心链路

### 手动记忆链路

用户或调试接口可以直接写正式记忆：

```text
POST /api/ai/memories
  ↓
AiUserMemoryController
  ↓
AiUserMemoryService.saveOrUpdateMemory(...)
  ↓
按 userId + memoryType + memoryKey 查询
  ↓
存在则更新，不存在则新增
```

这条链路用于验证 Memory 的最小闭环：只要正式记忆能写入，后续 AI prompt 就能读取。

### 候选记忆链路

AI 回复结束后异步提取候选：

```text
用户发送消息
  ↓
AI 正常流式回答
  ↓
SSE STOP 阶段保存 assistant message
  ↓
AiMemoryCandidateExtractorService.extractAfterChat(...)
  ↓
规则预筛判断是否值得提取
  ↓
调用 LLM 输出 JSON 候选
  ↓
根据 memory_type 和候选内容到 Memory 向量索引召回 TopK 相似旧记忆
  ↓
将新 Memory + TopK 旧 Memory 交给 LLM 判断 CREATE / UPDATE / MERGE / IGNORE
  ↓
后端清洗决策结果，防止 LLM 指向未召回的 target_memory_id
  ↓
解析并写入 ai_user_memory_candidates
  ↓
前端查询待确认候选
```

触发点在 `AiMessageServiceImpl`：

```java
aiMemoryCandidateExtractorService.extractAfterChat(
        userId,
        sessionId,
        userMessage.getId(),
        message,
        fullReply.toString()
);
```

这里放在 STOP 阶段，是因为用户已经拿到 AI 回复，Memory 提取不应该阻塞主聊天体验。

### 用户确认链路

前端 AI 抽屉中展示待确认候选：

```text
GET /api/ai/memory-candidates
```

用户点击保存：

```text
POST /api/ai/memory-candidates/{id}/confirm
  ↓
查询 PENDING 候选
  ↓
组装 AiUserMemorySaveDTO
  ↓
按 candidate_action 执行：
CREATE：新增正式记忆
UPDATE：更新 target_memory_id 指向的正式记忆
MERGE：用 merged_content 更新 target_memory_id 指向的正式记忆
IGNORE：不写正式记忆
  ↓
候选状态改为 CONFIRMED
  ↓
写 decided_at
```

用户点击忽略：

```text
POST /api/ai/memory-candidates/{id}/reject
  ↓
查询 PENDING 候选
  ↓
候选状态改为 REJECTED
  ↓
写 decided_at
```

所以状态流转是：

```text
PENDING -> CONFIRMED -> 写入正式记忆
PENDING -> REJECTED  -> 不写正式记忆
```

### Prompt 注入链路

正式记忆会在 AI 构造 prompt 时注入。

当前注入策略：

```text
enabled = 1
confidence >= 0.70
最多 20 条
按 importance DESC、updated_at DESC 排序
按 PROFILE / PREFERENCE / PROJECT_STATE 分组
```

prompt 结构：

```text
## 用户长期记忆
以下是用户确认或明确保存的长期记忆。请用它们理解用户背景、偏好和项目状态，但不要机械复述；只有在和当前问题相关时才自然参考。

### PROFILE
- ...

### PREFERENCE
- ...

### PROJECT_STATE
- ...
```

核心意义是：Memory 不是“查到什么都塞给模型”，而是有质量门槛和数量限制。

## 为什么要候选表

候选表的核心价值是把“AI 猜测”和“用户确认”分开。

如果 AI 直接写正式记忆，会有几个问题：

```text
1. 模型可能误解用户意思。
2. 临时上下文可能污染长期画像。
3. 用户不知道系统记住了什么。
4. 后续回答可能被错误记忆长期影响。
```

所以当前设计是：

```text
AI 只负责发现候选
用户负责确认是否保存
正式记忆才允许进入 prompt
```

这比全自动 Memory 更保守，但更适合当前项目阶段，也更容易在面试中讲清楚数据安全和用户控制。

## 为什么异步提取

候选提取需要额外调用一次 LLM。如果同步执行，会让用户每轮聊天都多等一次模型调用。

当前选择：

```text
主聊天链路：
  用户问题 -> AI 回复 -> SSE 返回

Memory 提取链路：
  AI 回复结束后 -> 后台异步提取候选
```

这样 Memory 失败不会影响正常聊天。

当前实现有两条路，由 `blog.ai.memory.extraction.use-mq` 切换：

- 默认（false）：`@Async` + 独立线程池，行为与迁移前一致；
- true：三条链路（候选记忆 / 情景记忆 / 会话摘要）统一投递 RocketMQ
  （topic `ai-memory-extraction`，三个 tag + 三个 consumer group），
  消费失败由 MQ 重试、耗尽进死信；幂等靠 `ai_memory_extraction_events` 事件表。
  投递失败是 fail-open（只记 WARN，不影响聊天收尾）。

详细设计与取舍见 `docs/mq/rocketmq-integration-design.md`；部署见 `deploy/rocketmq/README.md`。

## 前端交互

前端没有单独做复杂 Memory 管理页，而是先接在 AI 抽屉里。

交互流程：

```text
AI 回复结束
  ↓
前端延迟查询 /api/ai/memory-candidates
  ↓
如果有 PENDING 候选，显示“记忆”按钮和数量
  ↓
用户点开查看内容、类型、原因、重要性
  ↓
保存或忽略
```

这种设计不会打断聊天，不会让 AI 每次主动追问“要不要记住”，但用户仍然能控制正式记忆。

## 过期清理

候选记忆不是正式数据，只是待处理队列，所以需要生命周期管理。

当前策略：

```text
PENDING 超过 30 天：物理删除
REJECTED 超过 30 天：物理删除
CONFIRMED：保留
```

正式记忆不同：

```text
ai_user_memories 删除时只设置 enabled = 0
```

这样区分了两类数据：

```text
候选记忆：临时队列，可以过期清理
正式记忆：用户确认后的长期上下文，默认软停用
```

## 核心类职责

### AiUserMemoryService

负责正式记忆：

```text
查询当前用户记忆
新增或更新正式记忆
停用正式记忆
构建长期记忆 prompt
```

### AiUserMemoryCandidateService

负责候选记忆：

```text
查询 PENDING 候选
确认候选
拒绝候选
清理过期候选
```

### AiMemoryCandidateExtractorService

负责候选提取：

```text
聊天结束后异步执行
规则预筛
调用 LLM 输出 JSON
解析结果
写入候选表
同 key PENDING 候选更新
```

### AiPromptServiceImpl

负责把正式长期记忆注入 prompt：

```text
长期记忆
对话历史
页面上下文
当前问题
```

### AiAssistant.vue

负责前端候选体验：

```text
查询待确认候选
显示记忆 badge
保存候选
忽略候选
```

## 简历写法

可以写成一条完整经历：

```text
为博客 AI 助手设计并实现长期记忆系统，基于 MySQL 持久化用户画像、回答偏好和项目状态；通过 AI 异步提取候选记忆并引入用户确认机制，避免模型直接污染正式记忆；支持候选记忆保存/忽略、正式记忆启停、可信度和重要性排序、prompt 注入数量限制，以及 PENDING/REJECTED 候选过期清理，提升跨会话个性化回答能力和用户可控性。
```

如果简历空间较少，可以拆成 3 点：

```text
- 设计 AI 长期记忆模型，区分 PROFILE、PREFERENCE、PROJECT_STATE，并通过 confidence、importance、enabled 控制 prompt 注入质量。
- 实现候选记忆队列，AI 异步从聊天中提取候选，用户确认后才写入正式记忆，避免自动记忆污染上下文。
- 在前端 AI 抽屉接入待确认记忆提醒，支持保存/忽略，并通过定时任务清理过期 PENDING/REJECTED 候选。
```

## 面试讲法

### 你为什么要做 Memory？

可以这样讲：

```text
RAG 解决的是 AI 如何利用站内文章，Memory 解决的是 AI 如何理解当前用户。比如用户偏好先讲业务再讲代码、当前项目 RAG 阶段已完成、某些上线事项暂缓，这些信息如果每轮都靠用户重复说明，体验很差。所以我在 AI 助手里做了长期记忆，让跨会话回答能结合用户画像、回答偏好和项目状态。
```

### 为什么不直接把聊天记录当记忆？

可以这样讲：

```text
聊天记录是短期上下文，里面有大量临时问题、报错、闲聊和过期信息。如果直接把聊天记录长期注入 prompt，会增加 token 成本，也容易污染回答。我的做法是从聊天中提取稳定事实，整理成结构化记忆，再按可信度和重要性选择性注入。
```

### 为什么要候选表？

可以这样讲：

```text
AI 提取出来的信息不一定完全准确，所以我没有让模型直接写正式记忆，而是设计了候选记忆表。模型只负责生成候选，用户在前端确认后才写入正式记忆。这样既保留了自动发现能力，也保证用户对长期记忆有控制权。
```

### 保存和忽略分别发生什么？

可以这样讲：

```text
候选记忆初始状态是 PENDING。用户点击保存时，后端会按 candidate_action 执行：CREATE 新增，UPDATE 更新 target_memory_id 指向的正式记忆，MERGE 用 merged_content 合并更新，IGNORE 则确认不写入正式记忆；最后把候选状态改成 CONFIRMED。用户点击忽略时，不接受这条候选建议，只把候选状态改成 REJECTED。
```

### 为什么不直接用向量相似度更新？

可以这样讲：

```text
向量相似度只能说明两条记忆语义接近，不能说明它们是同一条记忆。比如“完成 Redis”和“完成 RocketMQ”很相似，但不能互相覆盖，更适合合并成“完成 Redis、RocketMQ”。所以我把 ES 的职责限定为召回候选旧记忆，再由 LLM 结合业务语义判断 CREATE / UPDATE / MERGE / IGNORE，最后由数据库执行确认后的动作。
```

### 为什么异步提取？

可以这样讲：

```text
提取候选记忆需要额外调用一次模型。如果放在主聊天链路里，会拖慢每次回答。所以我把它放在 SSE STOP 之后异步执行，主回答先返回给用户，后台再分析本轮对话并写入候选表。提取失败不会影响正常聊天。
```

### 怎么避免 Memory 污染 prompt？

可以这样讲：

```text
我做了几层控制：第一，只提取 PROFILE、PREFERENCE、PROJECT_STATE 三类长期信息；第二，AI 只能先写候选，用户确认后才进入正式记忆；第三，prompt 注入只选择 enabled=1 且 confidence 达标的记忆；第四，最多注入 20 条并按 importance 和更新时间排序；第五，prompt 里要求模型只在相关时自然参考，不要机械复述。
```

## 后续可选增强

这些不是当前必须继续做的部分：

- Memory 总开关和临时对话模式。
- 正式记忆编辑 UI，而不仅是保存和停用。
- 敏感信息过滤，例如身份证、手机号、token、密码等。
- 用 MQ 替换 `@Async`，增强高并发下的可靠性。
- 更强的 Memory 决策评测集，用固定样例验证 CREATE / UPDATE / MERGE / IGNORE 的稳定性。
- 增加 Memory 调试接口，查看本轮 prompt 实际注入了哪些长期记忆。

## 当前阶段结论

当前 Memory 已完成一个可运行的完整闭环：

```text
正式记忆表
候选记忆表
手动记忆 CRUD
长期记忆 prompt 注入
AI 异步提取候选记忆
ES 向量召回相似旧记忆
LLM 判断 CREATE / UPDATE / MERGE / IGNORE
用户确认 / 忽略候选
前端候选提醒和处理
PENDING / REJECTED 过期清理
注入策略：enabled + confidence + importance + 数量限制
```

本阶段验证结果：

```text
候选记忆能正常生成
CREATE / UPDATE / MERGE / IGNORE 决策信息能写入候选表
前端能展示动作、目标记忆、合并内容和决策原因
用户确认后能按动作写入或更新正式记忆
后端编译和前端构建通过
```

后续如果出现明显误提取、重复候选、敏感内容记忆或 prompt 污染，再针对具体问题优化。
