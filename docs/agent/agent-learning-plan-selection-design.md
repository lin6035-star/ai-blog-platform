# 学习计划定位：从字符串匹配到「模型选择 + 后端映射」

> 创建：2026-09-13
> 定位：修复「用户明明说了计划名，系统还弹卡让他重选」的定位失败问题。
> 前序：V3.2 评测门（`agent-eval-gate.md`）、V3.9 QA 目标决议（`agent-v3-9-qa-cross-page-anchor-design.md`，本文与之对照辨析）

## 一、问题（用户实测）

用户消息：**「我的 c++ 学习计划在第一阶段卡住了，有点难，你能给我分析一下嘛」**

系统行为：意图判对（`LEARNING_ASSIST`）、阶段也对，但**弹卡问「要攻坚哪个学习计划」**。

日志证据（分类器输出，说明模型完全理解了）：

```
intent=LEARNING_ASSIST, planRef=c++学习计划, stageRef=第一阶段
ruleHits=[learning_assist_rule] → 自动拉起 Workflow
```

## 二、根因：`c++` 被分词切成 `c`，与「C语言」计划同分并列

分词正则（`LearningPlansServiceImpl`）：

```java
Pattern.compile("[a-z0-9]+|[\\u4e00-\\u9fa5]+")   // '+' 不在字符集里
```

`"c++学习计划"` → tokens `["c", "学习", "计划", ...]`，而打分是「命中 token 数」：

| 计划（该用户库内真实标题） | 命中 | 得分 |
|---|---|---|
| C语言系统学习计划 | `c` `学习` `计划` | **3** |
| C++ 系统学习与工程化实践计划 | `c` `学习` `计划` | **3** |
| 其余 6 个 ACTIVE 计划 | `学习`/`计划` 其一 | 1~2 |

**并列 → `mentioned.size() != 1` → 候选列表 → 弹卡让用户重选。**

两个叠加缺陷：
1. `+` 被丢弃，`c++` 退化成单字符 `c`（区分度丧失）
2. 通用词（「学习」「计划」）主导得分——8 个计划里 6 个都靠它俩拿分

## 三、为什么不是「把 `+` 加进正则」

那是打地鼠：修完 `c++`，`c#`、`.NET`、用户口述「C加加」还在原地。根子是**拿字符串匹配当理解用**。

关键事实：**分类器早就理解了**——它输出了 `planRef=c++学习计划`。但 prompt 里对它的约束是「只能摘录用户原话」，因为它**看不到用户真实有哪些计划**。让它做选择题，却把选项藏着；后端再拿摘录片段去和标题做包含打分。

## 四、方案：把选项给它

```
分类器调用时注入该用户真实 ACTIVE 计划列表（仅标题 + 序号，不注入 ID）
  → 模型输出 learningPlanIndex（从列表里选的序号）
  → 后端把序号映射为权威 planId（越界即丢弃）
  → 路由命中权威 ID → 直达 Workflow，跳过字符串匹配
```

### 4.1 关键设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 模型输出什么 | **序号**，不是 ID | 雪花 ID 19 位，模型抄写易少位/错位；序号短且可校验。与 V2.4「LLM 只给标题、索引后端匹配」同源 |
| 谁写 `learningPlanId` | **只有后端** | 解析后先 `setLearningPlanId(null)`——即便模型幻觉出该字段也不采信 |
| 越界怎么办 | **丢弃**（保持 null） | 落回关键词匹配兜底，"后端绝不猜"原则 |
| 选不出怎么办 | 模型输出 null → **仍走原追问** | 信息真不足时追问是对的（见 §六） |
| 命中后还要校验吗 | **要**（存在 + ACTIVE + 归属） | 防御纵深；ID 虽由后端写入，仍不无条件信任 |

### 4.2 与 V3.9「LLM 输出 ID 不可信」的关系（重要辨析）

V3.9 明确让 `intent.articleId` **全链路退出 QA 定位**，理由是「LLM 输出不可信，防幻觉绕过」。本方案让模型参与定位，看似冲突，实则不同：

| | V3.9 禁用的 | 本方案允许的 |
|---|---|---|
| 场景 | 模型**没有候选**，凭空生成一个 ID | 模型**拿到候选列表**，从里面选 |
| 风险 | 幻觉重灾区（ID 可以完全不存在） | 有界选择 + 后端强校验 |
| 兜底 | 挂了就挂 | 越界丢弃 → 落回旧逻辑 |

**「模型只能从给定集合里选，越界即丢弃」是本项目已有的成熟模式**——记忆裁决（`AiMemoryDecisionSanitizer` 校验 `targetMemoryId` 必须在候选集内）同理。

### 4.3 落点

| 环节 | 位置 |
|---|---|
| 注入列表 + 新增 `learningPlanIndex` 规则 | `AiIntentClassifierImpl.buildUserPrompt` / `buildSystemPrompt` |
| 序号 → ID 映射（**安全边界，有单测**） | `AiIntentClassifierImpl.resolveLearningPlanId`（public static，可测） |
| 权威计划加载与二次校验 | `AiMessageServiceImpl.loadAuthoritativePlan` |
| 两条消费路径 | `routeLearningAssistWorkflow` / `routeLearningProgressWorkflow`（命中即直达） |
| 接口签名 | `AiIntentClassifier.classify(message, pageContext, userId)`——**不能用 `pageContext.userId` 代替**：那个字段在他人主页场景下是「被访问用户」，不是登录用户 |

## 五、验证

| 项 | 结果 |
|---|---|
| 单测 `AiIntentPlanSelectionTests`（映射 / 越界 / 非正数 / null / 防伪造 / 空列表） | 6/6 绿 |
| **分类器在线评测门**（改 prompt 铁律） | **29/29 绿**（原有样例零漂移） |
| 其中 `picksPlanFromInjectedListByAlias` | ✅ 两易混标题下选中《C++ 系统学习与工程化实践计划》 |
| 其中 `doesNotGuessWhenNoPlanMentioned` | ✅ 未点名 + 多候选 → null，不猜 |
| 离线评测门（planner + 阶段匹配回归） | 26 例绿（8 个在线用例按门控跳过） |

## 六、边界（明确不做 / 做不到）

- **用户没说计划时仍会追问**——8 个计划都有「第一阶段」时，这是真歧义，任何方案都过不去；追问是正确行为
- **模型可能选错**（标题高度相似时）：兜底靠 ① 后端校验越界即丢弃；② 确认卡显示定位到的目标（`context.planTitle` 已在展示），用户能一眼看出选错
- **confirm 路径未接权威 ID**：`AgentRunSuggestionService.applyPlanTarget` 的目标来自 Agent 建议的 `initialMessage`，仍走关键词匹配。学习域 Agent 尚无权威 planId（V4③ 设计稿 §6 风险 6 已登记），本刀不扩大范围
- **`learningPlanRef` 保留**：作为权威 ID 缺失时的兜底输入，不删
- **评测 JSON 框架未扩展**：`ClassifierEvalCase` 不支持「断言选中的计划」，且需要库内 fixture，故别名选中能力用独立测试方法覆盖，不进 JSON 样例集

## 七、后续可选（未做）

- `learningStageRef` 同样升级为「阶段序号选择」？——**暂不需要**：阶段侧已有 `extractStageOrdinal` 序号直解（「第一阶段」直接映射 `order_num`，本就不做字符串匹配），实测未成为瓶颈
- 学习域 Agent（`LEARNING_AGENT`）的权威 planId 绑定——属 V4③ 第二阶段范围
