# Planner 字面闸门移除：学习类 Workflow 不再校验用户用词

> 创建：2026-09-13
> 定位：删除 Planner 里「必须命中字面词表才放行学习 Workflow」的准入正则。
> 前序：`agent-learning-plan-selection-design.md`（同一轮的另一处「字面 vs 理解」修复）

## 一、问题（用户实测）

```
用户：帮我把我的C++的学习计划的第一阶段浓缩成4个小任务
系统：我可以继续普通讲解，也可以为你启动对应的学习流程。
      当前判断：LLM 建议启动 Workflow，但后端规则未命中，降级 CTA
```

用户说了完整的目标（C++ 计划 / 第一阶段）和动作（浓缩成 4 个任务），系统回了一句"我不确定你要干什么"。

## 二、证据：模型判对了，是后端词表否决的

同一秒的两条日志：

```
# 分类器（10:51:54）
intent=LEARNING_PROGRESS, confidence=0.95
planRef=C++的学习计划, stageRef=第一阶段
planIndex=1, planId=2092885385868185602      ← 正是《C++ 系统学习与工程化实践计划》
reason=用户要求调整已有学习计划的阶段结构（压缩/重排）

# Planner
action=CTA, ruleHits=[], reason=LLM 建议启动 Workflow，但后端规则未命中，降级 CTA
```

分类器把意图、计划、阶段全判对了，后端把结果丢了。

## 三、根因：用字面匹配复核语义判断

`resolveLearningWorkflowType` 曾要求**双重条件**：intent 命中 **且** 用户原话命中正则。

```java
// 学习进度准入正则（已删）
.*(调整|改一下|改改|修改|更新|重新|重排|压缩|加快|延长|缩短|去掉|删掉|加点|加个|换个)
  .{0,12}(学习)?(计划|进度|阶段|任务|安排|节奏).*
```

用户说的是「**浓缩**」——不在表里。

三个结构性缺陷：

1. **用更弱的能力否决更强的能力**：字面匹配永远不可能等价于语义理解
2. **词表是有限闭集，表达是开放集**：补了「浓缩」还有「精简/梳理/瘦身/补充几个/换一批/砍掉两个」
3. **词表内容实质是"历史踩坑清单"**：只认识曾经出过问题的说法；没踩过的说法默认判你不像

同一份代码里 `decideLearningAgent`（LEARNING_AGENT 裁决）的注释早已写明正确姿势：

> 入口规则不做正则兜底（漏检掉普通聊天安全，误检只读无害），只认分类器主判 + 建议一致

## 四、决策：删掉准入词表，后端只判事实

`resolveLearningWorkflowType` 不再接收 `message`；三个 `looksLikeLearning*Request` 准入方法（计划/进度/难度）删除。

**保留**的 `looksLikeLearningPlanQueryRequest`（查询句分流 dashboard）不在本次范围：它的失败模式是"漏兜底"（查询句可能进 Workflow，但分类器本就判 `LEARNING_PLAN_QUERY` + TOOL），不是"拦正常请求"。

**后端该判的是事实，不是语义**：

| 类别 | 归属 | 现状 |
|---|---|---|
| 用户这句话算不算"调整计划" | 分类器（有 29 例评测门锁边界） | 交还 |
| 有没有 ACTIVE 计划可操作 | 后端 | `routeLearningXxxWorkflow` 已有 |
| 计划是不是本人的 | 后端 | 已有 |
| 定位不到目标怎么办 | 后端 | 已有追问/CTA |
| confidence / risk 是否达标 | 后端 | `hasConfidence` / `isLowRisk` 已有 |
| 写操作要不要确认 | 后端 | 确认门已有 |

## 五、两个字段怎么用（评测门抓出来的关键细节）

分类器输出 `intent` 与 `suggestedWorkflowType` 两个字段。第一版改动直接保留 `intent ∪ suggestedWorkflowType` 的 OR 判断，**被离线评测门抓出回归**：

> 样例「LLM Workflow 类型不一致时降级 CTA」构造 `intent=LEARNING_PLAN` + `suggestedWorkflowType=LEARNING_PROGRESS`（模型自相矛盾），期望降级 CTA。
> OR 判断下 progress 会靠建议字段先命中并"代表"分类器，下游 `hasWorkflowSuggestion` 的一致性校验因此变成自证 → 错误放行成 WORKFLOW。

**最终语义**（两个方向都有测试锁定）：

| 情形 | 行为 | 锁定用例 |
|---|---|---|
| 主字段 `intent` 是学习类 | 以它为准；与建议字段打架 → 下游判不一致 → **CTA**（两字段矛盾时不猜） | 评测样例「LLM Workflow 类型不一致时降级 CTA」 |
| 主字段没落在学习 Workflow 上（如被判成 `LEARNING_PLAN_QUERY` / `GENERAL_CHAT`），但建议字段明确指向某个学习 Workflow | **退到建议字段救回**，拉起 Workflow | 单测 `workflowSuggestionCanPassWhenIntentIsMisclassifiedButSuggestedTypeMatches` |

## 六、评测样例变更记录（按 eval-gate 要求记录原因）

`planner-evaluation-cases.json` 一条样例随行为变更调整：

| | 变更前 | 变更后 |
|---|---|---|
| name | 模糊学习句**规则未命中**时降级 CTA | 学习攻坚请求（intent 与建议一致、低风险高置信）**直通 Workflow** |
| message | 我最近学 Redis 有点乱 | （不变） |
| expectedAction | CTA | **WORKFLOW** |
| expectedReasonContains | 后端规则未命中 | 双签命中 |

**原因**：原样例锁的正是本次删除的机制（"用户用词未命中词表 → 降级"），不是掩盖回归——该机制已被判定为负债（§三）。
**未丢失的保护**：同一评测集已有「风险不是 LOW 时降级 CTA」样例承担"模糊请求不直接走 Workflow"的兜底职责——语义模糊应由 `risk` / `confidence` 这类**后端可可靠校验的离散信号**表达，而不是由词表表达。

## 七、验证

| 项 | 结果 |
|---|---|
| `AgentPlannerSupportTests`（含新增"说法无关"防回退用例） | 46/46 绿 |
| **离线评测门** `-Dgroups=eval`（改 Planner 规则铁律） | 26 例绿（8 个在线用例按门控跳过） |
| `AiMessageIntentRoutingTests` + `AgentRuntimeRouteRegistryTests` | 5/5 绿 |

新增单测 `learningWorkflowIntentIsAcceptedRegardlessOfWording` 用「帮我把我的C++的学习计划的第一阶段浓缩成4个小任务」原文锁定行为——将来若有人想加回"必须命中 XX 词"的准入正则，这条会拦住他。

## 八、边界与残留

- **词表兜底能力确实变弱了**：分类器判错 intent 时不再有第二道语义闸。代价上限是"多弹一张可取消的确认卡"，不是数据损坏（写操作有确认门 + 原子条件更新）
- **`looksLikeLearningPlanQueryRequest` 未动**：查询句分流仍走正则，其失败模式是漏兜底而非拦正确请求
- **分类器是这套设计的单点**：分类器 prompt 的边界质量直接决定体验，评测门（在线 29 例）是它的护栏——改分类器 prompt 必跑

## 九、续：全库字面判断排查 + 第二批处置（2026-09-13）

删掉 Planner 准入词表后，对整个 `src/main/java` 做了一次排查：**哪些地方还在用字面匹配做判断**。

### 9.1 判据（不是"有没有正则"，而是"没命中时往哪边倒"）

| 方向 | 含义 | 判定 |
|---|---|---|
| 没命中 → **拦住用户** | 明确诉求被拒/降级 | 🔴 低级的根源 |
| 没命中 → **多问一句 / 给安全默认** | 无损或可恢复 | 🟢 合理兜底 |
| **误命中 → 直接执行** | 做了用户不想要的事 | ⚠️ 比拦住更危险 |

### 9.2 处置清单

| 位置 | 方向 | 处置 |
|---|---|---|
| `NEGATIVE_FEEDBACK` ×3（学习 Plan/Progress/Assist） | ⚠️ 误命中 → **取消 Workflow（不可逆）** | **删词**（见 §9.3） |
| `ArticleAgentActionExecutorImpl` focus 结构词表 | 🟡 没命中 → 多绕一步（V3.13 已补"明说找不到"兜底） | prompt 补规则 + 词表留作兜底（见 §9.4） |
| `CreateArticleWorkflowHandler.extractSimpleKeywords` | 🟢 观感差（硬编码 Redis/缓存/高并发） | **删硬编码**（见 §9.5） |
| `LearningPlansServiceImpl.TOKEN_PATTERN` | 🟡 兜底路径上的 `c++` 隐患 | **补字符集**（见 §9.6） |
| `LearningPlanWorkflowHandler.hasPlanRequest` | 🟢 失败方向安全（"漏了最多多问一句"） | **保留**（正面典范） |
| 「敷衍词」检测（啥/随便/点东西） | 🟢 两头无损 | 保留 |
| `INDEX_PATTERN` / `STAGE_ORDINAL_PATTERN` / `\d+` | 结构化解析，非语义判断 | 保留 |
| `AgentThoughtSanitizer` / `AgentPlanValidator` | 安全闸门，宁严勿宽 | 保留 |
| RAG 分词排序 / 错误信息解析 / URI 判断 / Markdown 格式检测 | 各有明确用途 | 保留 |

### 9.3 `NEGATIVE_FEEDBACK`：不是"分级"，是**精准删词**

**第一版改法被推翻**：原打算改成"整句否定→取消、含糊→追问"。推翻依据两条：

1. 需求确认面板**本来就有「取消工作流」按钮**（`AiAssistant.vue`，无按钮的是"同意/不同意"那一组）——文本取消是冗余的
2. 现有测试语料 `"算了，不学了"` / `"算了，不改了"` 表明**"算了 + 同义否定"就是真取消**；"整句才算"的判定会把这些真实表达误判成含糊，多问一轮

**最终改法**：从词表移除**非终止语**（保留全部终止语）

| 词表 | 移除 | 理由 |
|---|---|---|
| Plan | `不是要` / `不是想` | 纠正句式的前半（"不是要X，我要Y"），后面跟的往往是新要求 |
| Progress / Assist | `取消` | **动作词**而非终止语——"把第二阶段取消掉""取消勾选"会被误判成终止流程，而取消不可逆 |

真要终止，面板上的按钮兜底。保留的终止语（`算了` / `不学了` / `不改了` / `不需要` …）行为不变。

### 9.4 focus 词表：prompt 教模型 + 词表降级为兜底

**重新评估**：它的失败方向是"多绕一步"（去正文搜"整体框架"→ 搜不到 → 明说），**不是拦住**——严重度低于初判。

**根因在 prompt**：决策器规则只说"如需某节内容可带 focus；缺省不带返回整体摘要"，没说"整体性询问不要带 focus"，模型就把「整体结构」这类词塞进去了。

**改法**：`ArticleAgentStepDecider` prompt 补一句明确规则；词表保留为兼容兜底。

### 9.5 `extractSimpleKeywords`：删硬编码

LLM 不可用时的降级标签，三个硬编码词只在"LLM 挂了且主题恰好含这三个词"时生效，收益接近零。改为直接取主题截断。

### 9.6 分词正则：补 `+ # .`

```java
// 原：[a-z0-9]+           → "C++" 被切成 "c"，与「C语言系统学习计划」同分并列
// 新：[a-z0-9+#.]+        → 与 RAG 侧 DefaultArticleRagRanker 的字符集一致
```

主路径虽已由"分类器选序号 → 权威 planId"绕过，兜底路径上的隐患借此收尾。

### 9.7 附带修复：verifier 在线样例 [3] 的构造缺陷

**背景**：该样例 09-10 起被记录为"判定波动"，本次**连续两次复现**，按 eval-gate"连续两次才算回归"规则升格为回归处理。

**根因（样例缺陷，非模型问题）**：

```json
"observations": ["标题《Redis 缓存设计》、字数约 3200 字", "记忆摘要：偏好「先给结论再展开论证，喜欢用具体代码示例」"],
"answer": "……另外你已经用了具体例子，保持就好。"   ← 对文章内容的断言，但证据里没有正文
```

验证器判 NEED_MORE 是**正确**的——"你已经用了具体例子"是文章内容层面的论断，而观察里只有标题与字数。

**修复**：把该句改为基于偏好的建议（"你偏好用具体代码示例，可以在关键小节补一个可运行的小例子"），**不改变样例意图**（仍是"记忆偏好问答有依据 → SUFFICIENT"）。修复后 14/14 全绿。

**记录原因（按 eval-gate 要求）**：改的是样例自身的构造缺陷，不是为了让失败变绿——原 answer 确实越证据，验证器的判定比样例期望更对。

### 9.8 验证

| 项 | 结果 |
|---|---|
| 三条取消用例（Plan/Progress/Assist） | 3/3 绿（"算了，不学了"仍走取消） |
| `CreateArticleWorkflowHandlerTests` | 6/6 绿 |
| 在线评测门（`-DrunRealLlm=true`，覆盖决策器 prompt + 匹配算法两处变更） | 分类器 29 ✓ / planner 1 ✓ / 阶段匹配 17 ✓ / 三域决策 smoke 3 ✓ / **verifier 14 ✓**（样例修复后） |
