# CTA 用户文案解耦：内部诊断不再念给用户

> 创建：2026-09-13
> 定位：表达层修复——CTA 不再把 `AgentDecision.reason`（开发者诊断串）拼进用户可见文案。
> 前序：`agent-planner-literal-gate-removal-design.md`（同一轮"字面 vs 理解"排查的另一处）

## 一、问题（实测原文）

用户触发 CTA 时看到的是：

```
我可以继续普通讲解，也可以为你启动对应的学习流程。

当前判断：分类器置信度不足，降级 CTA

如果只是想聊天或解释概念，可以直接继续问；如果要进入学习流程，请明确告诉我。
```

「LLM 建议启动 Workflow，但后端规则未命中，降级 CTA」这类句子也会原样出现。

三个问题叠在一起：

1. **术语泄漏**：LLM / Workflow / 后端规则 / 降级 CTA / 置信度——用户一个都不认识
2. **信息无效**：知道"后端规则未命中"能做什么？什么也做不了
3. **归因反了**：明明是系统判断不了，末句却像在说"是你没讲清楚"

## 二、根因：一个字段服务两个读者

`AgentDecision.reason` 的注释是"最终裁决原因"，和 `ruleHits`（结构化诊断）是一对，
**本来就是给开发者看的**。但 `buildPlannerCtaContent` / `buildGuestCtaContent` 直接把它拼进了用户文案。

```java
return "我可以继续普通讲解，也可以为你启动对应的学习流程。\n\n"
        + "当前判断：" + reason + "\n"   // ← 诊断串进用户文案
        + "如果只是想聊天……";
```

副作用：**改 reason 措辞会静默改掉用户看到的字**。2026-09-13 改 Planner 那条
「后端规则未命中」时，用户可见文案就跟着变了，而没有任何测试拦住——因为
**CTA 文案此前零测试覆盖**（全测试集里没有断言过这两段文案）。

反证：`decideArticleAgent` 里那条"你说的这篇文章在这段对话里还没有出现过……"
是**唯一写成人话的**——因为它没有别的用途，只能当用户文案。

## 三、方案：按「用户能不能行动」分流

不是简单删掉 reason 了事——删了之后 CTA 会变成一句无信息的空话。
真正要做的是**按用户能否据此行动**分类渲染：

| CtaKind | 场景 | 用户能行动吗 | 文案方向 |
|---|---|---|---|
| `MISSING_ARTICLE_CONTEXT` | 缺文章上下文 | ✅ | 去文章详情页 / 说清哪篇 |
| `PAGE_CONTEXT_MISMATCH` | 页面不对 | ✅ | 去对应的页面 |
| `AMBIGUOUS_REQUEST` | 分类器主动建议澄清 | ✅ | 追问一句 |
| `QUOTA_REACHED` | 会话自动拉起配额用尽 | ❌ | 中性："这次请你明确说一句" |
| `SYSTEM_UNCERTAIN` | 置信 / 风险 / 字段打架 / 白名单外 | ❌ | **按 intent 给确认式引导** |

后两类是重点：**用户对系统的内部状态做不了任何事**，所以不说这些，
改说"我猜你是想做 X，对吗"——用户用一句话就能确认或纠正。

## 四、实现要点

### 4.1 默认值让 30 处产点零改动

`AgentPlannerSupport` 里有 30+ 处 CTA 产生点。如果每处都要改，那是大规模机械修改且容易漏。

```java
// 三参版缺省 = SYSTEM_UNCERTAIN（大多数 CTA 都是"系统不确定"）
private AgentDecision cta(String intent, List<String> ruleHits, String reason) {
    return cta(intent, ruleHits, reason, CtaKind.SYSTEM_UNCERTAIN);
}
```

只有需要区分的 **8 处**显式标注（缺文章 ×2 / 页面不符 ×2 / 模糊 ×4 / 配额 ×1），
其余 20+ 处一行未动——它们的语义本来就都是"系统不确定"。

### 4.2 编译期穷举保证

文案用 Java 17 switch 表达式对枚举穷举：**将来给 `CtaKind` 加新值，漏写文案直接编译失败**，
不会静默落到某个默认分支。

### 4.3 顺带修一条失真 reason

`"LLM 建议启动 Workflow，但后端规则未命中，降级 CTA"`——V4.x 删掉 Planner 字面词表后，
**后端已不存在这道校验**，失配只可能来自分类器自身（两个字段都拿不出学习类类型）。
改为「分类器建议启动 Workflow 但未指明学习类类型」。同步更新单测断言。

## 五、验证

| 项 | 结果 |
|---|---|
| `AgentPlannerSupportTests`（新增 5 处 ctaKind 断言） | 46/46 绿 |
| **新增 `CtaContentTests`**（3 例） | 3/3 绿 |
| 离线评测门 `-Dgroups=eval`（改 Planner 规则铁律） | 26 例绿（8 在线跳过） |
| `AiMessageIntentRoutingTests` + `AgentRuntimeRouteRegistryTests` | 5/5 绿 |
| `AgentDecisionTraceTests`（AgentDecision 加字段无副作用） | 2/2 绿 |

`CtaContentTests` 锁两件事（**这次的根因就是文案无测试**）：

1. **文案不得含内部术语**——遍历所有 kind × 常见 intent，断言不含
   `LLM / Workflow / CTA / 降级 / 置信度 / 后端规则 / 分类器 / 白名单 / intent / 命中`。
   将来若有人把 reason 拼回去，这里会拦住。
2. **SYSTEM_UNCERTAIN 按 intent 分流**——学习类提到"学习计划"、创作类给出主题示例、
   未知 intent 的兜底也要给可行动的例子（不能是一句"我不确定"就完了）。

## 六、边界

- **判断逻辑一行未动**：五道闸门（语义 / 风险 / 置信 / 一致性 / 配额）全部保留，
  workflowType 解析、白名单、确认门不变。本次只改"怎么说话"
- **`reason` 语义保留**：仍进 `AgentDecisionTrace`，仍被评测门断言——诊断能力不减
- **前端无影响**：CTA 仍是纯文本消息（不带 `clientActionType`），前端不解析文案

## 七、登记：CTA 死路问题（另议，未实施）

本次修复让 CTA **说得像人话**了，但没解决它的**结构性缺陷**：

```
用户被拦下 → 看到 CTA → 只能重新表述 → 还是可能被拦 ← 这里能无限循环
```

CTA 依赖"用户重新说一遍"，而拦下它的原因（置信度/风险）**不会因为用户换种说法就消失**。
对照系统里已有的另一条链路（文章域 V2.5 起在跑）：

```
分类器判 ARTICLE_AGENT → 只读 Agent 侦察 → SUGGEST_WORKFLOW → 用户确认 → 进工作流
```

**Agent 是单调前进的**（查了就有数据，有数据就能给建议或问出好问题）。

待议方案：把「分类器判了工作流但闸门没过」也接到这条链路上（降级到只读 Agent 而非 CTA）。
已知支持条件：`LearningAgentRuntime.ALLOWED_SUGGEST_WORKFLOW_TYPES` 已含三个学习 Workflow，
`QUERY_LEARNING_DASHBOARD` 可读用户计划与进度——学习域接得上。

**未评估项**：① 学习域 Agent 用的是基类通用 prompt（无 `LearningAgentStepDecider`），
决策质量未验；② 降级后需防"被拦 → 进 Agent → 建议工作流 → 用户拒绝 → 再被拦"的循环；
③ 属判断层改动，会碰评测门锁住的现有行为。
