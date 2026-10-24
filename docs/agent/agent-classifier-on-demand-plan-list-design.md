# 分类器按需加载计划列表：普通聊天不再背计划列表

> 创建：2026-09-13
> 定位：把「计划列表」从**每次分类的必备输入**改成**只有点名计划时才加载**。
> 前序：`agent-learning-plan-selection-design.md`（计划定位改造）、`agent-cta-content-decoupling-design.md`（同轮表达层修复）

## 一、问题（老大实测发现）

上一轮为了修「C++ 被切成 c，与 C语言计划同分并列 → 弹卡让用户重选」，把计划定位从"后端字符串匹配"改成了"把真实计划列表注入 prompt，模型选序号"。

**改对了定位，但顺手把列表加成了"每次分类的必备输入"**：`classify()` 一进来就 `loadActivePlans(userId)`，不管用户说什么。

后果：

1. **说"你好"也查一次库**（`SELECT ... FROM learning_plans WHERE user_id=?`）
2. **说"你好"也把计划列表塞进 user prompt** —— 老大的原话是"普通聊天也塞进 prompt 就有点恶心了，prompt 太重了"

## 二、被推翻的一个论点（方法论）

第一次被问到"为什么每句都查"时，我给的解释是：

> 分类器需要列表才能"选"，而分类器是每条消息的必经之路，且**不能预判"这条消息要不要列表"——预判就得写关键词词表，那正是我们刚删掉的东西**。

**这个论点错了。** 预判不需要词表——**分类器第一次调用本身就是预判**。

错误来源：我把"判断意图"和"定位计划"绑死在**同一次 LLM 调用**里，于是只能得出"列表必须每次都在"。

拆开就成立了：

```
第一次调用（不带列表）→ 判 intent + 摘录用户话里的计划名（learningPlanRef）
        ↓
   用户点名计划了吗？
        ↓ 没有（闲聊 / 概念问答 / 文章 / 新建计划）→ 到此结束，0 次查询
        ↓ 点名了（"帮我把 C++ 计划浓缩一下"）
   查列表 → 第二次调用：让模型在列表里选序号
```

## 三、方案

### 3.1 判据：什么消息才值得付"计划列表"的代价

```java
private boolean needsPlanLocating(AiIntent intent) {
    // 意图必须是「调整 / 攻坚已有计划」——新建计划（LEARNING_PLAN）不涉及已有计划
    if (!"LEARNING_PROGRESS".equals(intentName) && !"LEARNING_ASSIST".equals(intentName)) {
        return false;
    }
    // 且用户原话点了计划名；没点名的（"帮我调整一下计划"）给了列表也选不出，交给下游追问
    return planRef != null && !planRef.isBlank();
}
```

两个条件都满足才查库 + 二次调用，否则第一段的结果直接用。

### 3.2 为什么是"二次调用"而不是"事后补一个序号"

计划定位必须让模型**看着选项选**。后端字符串匹配在同分并列时会退化——`C++` 曾被切成 `c`，与「C语言系统学习计划」同分，结果弹卡让用户重选（实测踩过）。

**代价与收益**：

| | 现在 | 改后 |
|---|---|---|
| "你好" / "点赞这篇文章" / "我想学 Redis" | 查库 + 注入列表 | **0 次查询、prompt 无列表** |
| "把 C++ 计划第一阶段浓缩" | 1 次 LLM | 1 次 LLM + 查库 + **第 2 次 LLM** |

老大的判断："**没关系，慢一会也没事**"——点名要动某个计划时多等一次，好过每条消息都背着列表。

### 3.3 缓存（第二层）

第二段仍走缓存（`LearningPlansService.listActiveByUserCached`）：

- 查库**收窄**：`status=ACTIVE` 下推 SQL + 只取 `id/title/status` 三列（原来取全列再在内存 filter）
- **Redis 缓存**，TTL 5 分钟，沿用文章列表缓存那套（fail-open：Redis 挂了直接查库）
- **写时失效**：`refreshPlanStatus`（保存 / 勾选 / 改名 / 追加任务四个写路径的汇聚点）+ `deletePlan`
- **空列表也缓存**（防穿透）

改后普通聊天不经过它，它只服务"点名定位"这一小撮消息。

### 3.4 未提供列表时的提示

`buildUserPrompt` 无列表时加一句：

```
（本次未提供学习计划列表，learningPlanIndex 输出 null；learningPlanRef / learningStageRef 仍按用户原话摘录）
```

前一句防模型硬编序号，后一句防止它因为"没有列表"连计划名也不摘了（摘不出 `planRef` 就触发不了第二段，会静默退化）。

## 四、验证

| 项 | 结果 |
|---|---|
| **在线评测门** `-Dgroups=eval -DrunRealLlm=true`（分类器 user prompt + 调用逻辑都变了，按门槛必跑） | **64 例全绿**：分类器 29 / verifier 14 / 阶段匹配 17 / smoke 3 / planner 1 |
| 离线门 `-Dgroups=eval` | 26 例绿（8 在线跳过） |
| `AiIntentPlanSelectionTests` + `AiIntentClassifierPromptTests` | 10/10 绿 |

在线门里的分类器 29 例包含"别名选中 / 不猜"两条计划定位用例——它们能过，说明
**第一段正常输出 `learningPlanRef` → 触发第二段 → 定位成功** 这条链路是通的。

## 五、边界

- **没点名计划 = 不查列表**：用户说"帮我调整一下计划"（没说哪个）时 `learningPlanIndex` 为 null，
  **和改动前行为一致**（改动前的 prompt 也要求"无法确定指代 → null"），下游追问逻辑不变
- **列表长度未做限制**：现在只有"点名定位"才带列表，长列表的影响只落在这一次调用上，暂不做 LIMIT
- **两段各自 repair**：`callOnce` 封装了"调用 → 解析 → 失败 repair 一次"，两段共用同一套恢复逻辑
- **`resolveLearningPlanId` 仍是唯一权威写点**：第一段后以空列表调用它（清空防伪造），第二段后以真实列表调用
