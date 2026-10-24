# 过程状态补全 + 学习查询正则移除

> 创建：2026-09-13
> 定位：同一句实测暴露的两个问题——**路由被字面正则截胡** + **大动作前没有任何过程提示**。
> 前序：`agent-v4-5-global-thinking-display-design.md`（过程提示机制）、`agent-planner-literal-gate-removal-design.md`（字面判断排查）

## 一、实测原句

```
用户：你帮我分析一下我的c++学习计划，看看这份计划按安排的有没有什么问题
体验：前端根本看不到他在干嘛，突然就全部发出来了
```

日志还原了全过程：

```
AI意图识别结果: intent=LEARNING_ASSIST, confidence=0.95, risk=LOW,
                planRef=c++学习计划, planIndex=1, planId=2092885385868185602
                reason=用户请求分析已有学习计划的合理性，属于拆解/解释/评估类辅助请求

AgentDecisionTrace: action=TOOL, toolName=getLearningDashboard,
                    ruleHits=[learning_plan_query],
                    reason=学习计划查询句，使用 dashboard 读工具
```

**分类器判对了**（LEARNING_ASSIST：分析评估诉求，且准确定位到 C++ 计划），**被后端一行正则推翻**。

## 二、问题一：字面正则抢在语义判断前面

`looksLikeLearningPlanQueryRequest` 写在 `decideLearning` 的**最前面**：

```java
public AgentDecision decideLearning(...) {
    if (looksLikeLearningPlanQueryRequest(message)) {     // ← 正则先说话
        return ...TOOL... dashboard
    }
    ...
    AiWorkflowType workflowType = resolveLearningWorkflowType(intent, ruleHits);  // ← 分类器没机会
}
```

正则内容是 `.*(看看|查|有几个|进度|...).{0,12}(学习)?(计划|...).*`——「**看看**这份**计划**」直接命中。

**设计意图与实现的偏差**（原测试名就是证据）：

```java
void queryRequestUsesDashboardToolEvenWhenClassifierMisjudgesPlanIntent()
//   ↑ 意图是"分类器判错时兜底"，实现却是"无条件优先"
```

**兜底 vs 抢跑的区别**：兜底只在语义判断空缺时生效，抢跑会推翻已经明确的语义判断。

**处置**：正则整体删除，改由分类器判 `LEARNING_PLAN_QUERY`。

**位置很关键**——新分支必须放在 `resolveLearningWorkflowType` **之后**（第一次改放前面，被测试 `workflowSuggestionCanPassWhenIntentIsMisclassifiedButSuggestedTypeMatches` 抓出：主字段是 `LEARNING_PLAN_QUERY` 但建议字段指向真实工作流时，那一步要把它**救回**成 WORKFLOW）。

```java
AiWorkflowType workflowType = resolveLearningWorkflowType(intent, ruleHits);
if (workflowType == null) {
    // 没有工作流意图时，才轮到"纯查询"分流
    if (intent != null && "LEARNING_PLAN_QUERY".equals(intent.getIntent())) { ...TOOL... }
    if (isWorkflowSuggestion(intent)) { ...CTA... }
    return ...CHAT...;
}
```

**判定代价的对比**（沿用字面排查的判据）：

| | 保留正则 | 删除正则 |
|---|---|---|
| 分类器判错（查询→创建） | 救回 ✓ | 走错路——但走错的那条有确认门，不是静默出错 |
| 分类器判对但用词像查询 | **误伤**（实测已撞上）✗ | 正确 ✓ |

「看看」「查」误命中率极高，而误伤的代价是明确诉求被降级。

## 三、问题二：大动作前没有任何过程提示

`buildChatStatusFlux` 只认文章相关的两种检索模式，而 **dashboard 查询走的是"普通聊天兜底路径"**（`retrievalMode=NONE`）→ 返回空流 → 前端全程无提示。

**补两路**（都是"用户会干等、之前却没有任何提示"的场景）：

| statusType | 文案 | 场景 |
|---|---|---|
| `LEARNING_DASHBOARD_READING` | 正在查看你的学习计划… | 学习计划查询（dashboard 读工具） |
| `CLASSIFIER_DEGRADED` | 这次没理解准，先按普通回答处理… | 分类器调用失败静默降级 |

**降级为什么要说**：分类失败是**静默**的（强制 `GENERAL_CHAT`），用户看到的是"AI 突然不认我说话了"，而不是"这次是系统故障"——两者对用户的意义完全不同。判据用 `confidence == 0.0`（`generalChat()` 兜底固定值，正常分类不会给 0 分）。

**前端渲染同步升级**（`AiAssistant.vue`）：过程状态从"跟在打字点后面的一行浅灰小字"改成**与思考面板同族的卡片**（边框 + 底色 + 圆角 + 转圈）。

```css
.ai-chat-status {  /* 对齐 .ai-thinking 的视觉语言 */
  display: flex; gap: 8px; padding: 8px 12px;
  border: 1px solid #e5e7eb; border-radius: 8px; background: #fafafa;
}
.ai-chat-status__spinner { /* 转圈：静态度只说明「在等」，转圈才说明「在动」 */ }
```

## 四、验证

| 项 | 结果 |
|---|---|
| `AgentPlannerSupportTests`（改写 1 条 + 新增防回退 1 条） | 47/47 绿 |
| 离线评测门（删 Planner 分流规则） | 26 例绿 |
| `AiMessageIntentRoutingTests` + `AgentRuntimeRouteRegistryTests` | 5/5 绿 |
| 前端 `npm run build` | 通过 |

**新增防回退用例** `analyzeRequestIsNotHijackedByQueryWording` 直接用实测原句锁定——将来若有人想加回"用词像查询就走 TOOL"的字面分流，这条会拦住。

**评测样例变更**（按 eval-gate 记录原因）：`学习计划查询进入 Dashboard` 的输入从「分类器判成 LEARNING_PLAN（靠正则兜底）」改为「分类器判对 LEARNING_PLAN_QUERY」——原样例锁的正是本次删除的机制。

## 五、未做（登记）

- **分类那 2-4 秒的空白**：分类在 Flux 建立**之前**同步执行（HTTP 线程），后端发不出"正在理解你的问题"事件。可行解法是**前端本地过渡态**（发送即显示），但那会让前端"自己拼文案"（现约定是服务端给文案）——待定
- **工作流的理解展示**（"我理解你要调整《X》"）：见 V4.x 第二刀（会话锚 + 工作流理解阶段）
