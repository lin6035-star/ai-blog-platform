# 钱包域 Agent（第 4 个域）：查余额 / 充值

> 状态：✅ 已实施 · 2026-09-21
> 上游：`docs/redis/钱包与秒杀计划.md`（钱包本体）、`docs/agent/agent-v3-6-write-executor-registry-design.md`（受控写动作壳）

## 0. 一句话

让 AI 助手能充值——「帮我充 10 块钱」→ 确认卡 → 用户点确认 → 到账。
顺带给普通聊天一个只读的查余额工具。

**不新造机制**：充值复用 `WalletRechargeService.createCustomOrder` + `pay`（两轮前就做好了），
确认复用 V2.4 的受控写动作体系。本次只是接上**第 4 个 Agent 域**。

## 1. 两条路，写操作只走其中一条

这个项目里 Agent 有**两套互不相通的机制**，接任何新能力之前必须先分清：

| | Spring AI Tool Calling | Agent Runtime 循环 |
|---|---|---|
| 谁决定调用 | LLM 自己 | LLM 提 JSON，**后端校验白名单** |
| 现有工具 | 26 个方法，**零个改数据库** | 8 个动作 |
| 写能力 | 只往内存 registry 塞「前端指令」，真正的写由前端拿到指令后自己调接口 | `SUGGEST_WRITE` → **确认卡** → 用户点确认才执行 |

`AgentStepActionType` 的注释把原则写死了：

> 直接写动作一直明确禁止——Agent 只能提案，不能直接写。

**充值改余额，所以必须走确认卡。** 只读的查余额走 Tool Calling。

> 为什么不把充值也做成「工具 + 前端指令」（照点赞/收藏那套，改动小得多）：
> 工具返回值会被 LLM 二次加工，用户可能听到「已经帮你充好了」而实际还在等确认。
> 点赞说错无伤大雅，**钱上说错不行**。

## 2. 金额**不经过 LLM**

这是本设计里最重要的判断。

最初的方案是「分类器提取金额 → 拼进 goal → 决策器解析」。被否掉了，依据是这个项目一贯的判据：

> **LLM 判语义、后端判事实。**

金额是**事实**。让它过一遍 LLM，就多一次幻觉面——用户说「帮我充点钱」，
模型回一个 `payYuan:"100"`，卡片就弹出一个用户从没说过的数字。
项目对这类东西有明确先例：`articleId` 只从页面上下文取、`learningPlanId` 由后端映射不从 LLM 读。

所以 `WalletAmountParser` 直接解析 `goal`（= 用户原话），支持阿拉伯数字 + 常见中文数字。
结果只有三态：

| 解析结果 | 动作 |
|---|---|
| 0 个金额 | `FINAL_ANSWER` 追问 |
| ≥2 个**不同**金额 | `FINAL_ANSWER` 追问（歧义——防「先充10块…改成20」两轮合并后挑错） |
| 1 个 | 出提案 |

**失败的方向永远是「问用户」，不是「猜」。** 宁可多问一句，也不能弹出一张用户没想过的卡。

顺带的好处：`AiIntent` 不加字段、`AiMessageServiceImpl` 一行不用改、分类器 prompt 不用动 JSON 示例。

## 3. 决策器不调 LLM

`WalletAgentStepDecider` 是**唯一一个确定性决策器**——充值不需要多步推理，
「充多少」就在用户原话里。再让 Agent 循环跑几次 LLM 决策，既烧钱又凭空多出幻觉面。

复用 `AbstractAgentRuntime` 的骨架（run 创建、状态机、SSE、同 session 过期提案清理、
token 记账）全部白拿，只把 `decider()` 换成一个不调 LLM 的实现。

## 4. ⚠️ 死循环：本域最危险的失效模式

基类主循环只有 `usedSteps` 一个推进变量。`handleExtraTerminalAction` 返回 null 且
`isTerminalExtension` 为 true 时会 `continue` —— 而**对确定性决策器，下一轮的输入完全相同，
必然再次返回同一个提案 → 真死循环**（SSE 永不 complete，直到准入超时）。

学习域之所以没炸，是因为它的三条 null 出口都自己推进了 `usedSteps`、且靠 LLM 下一轮会换招。
**这两条在本域都不成立。**

三层防护：

1. **`handleExtraTerminalAction` 对 SUGGEST_WRITE 永不返回 null**——校验失败一律 `markFailed`（run 直接终态结束）
2. **决策器自终止**：`stepNo > 1` 一律 `FINAL_ANSWER`。这是唯一能真正让循环**有界**的东西——把活性编码进决策器，而不是指望钩子永不为 null
3. **`isTerminalExtension` 返回 `false`**：语义上有悖直觉（SUGGEST_WRITE 确实是终态动作），但万一将来多了一条返回 null 的出口，返回 false 会让流程掉进 executor 抛异常 → 落 FAILED step + 推进步数 → **有界**；返回 true 则 `continue` 回循环顶撞上同一个提案 → 死循环

## 5. 追问只能用 `FINAL_ANSWER`，不能用 `ASK_USER`

硬约束：**钱包域不能有 `WAITING_USER`**。

`AgentFollowUpResolver` 只认「上一轮建议的目标是本会话可唯一定位的学习计划」，会把 intent
强制改写成 `LEARNING_AGENT`。钱包 run 若以 ASK_USER 收尾，用户回答「10块」会走正常分类
（判成 `GENERAL_CHAT`）→ **永远回不到钱包域**，钱充不进去、用户只看到一句闲聊。

所以追问走无状态形态：决策器直接 `FINAL_ANSWER("充值金额需在 X ~ Y 元之间…")`，
用户重说一句就是新 run。

## 6. 金额校验分三层

```
决策器拦截（用户可见文案，区间读 getCustomConfig()）
  → 执行器兜底重校验（防配置漂移 / 老提案）
    → 服务层 requireWholeYuan（最后一道，本来就是权威闸门）
```

⚠️ 决策器里**不硬编码 1~1000**——那是 `blog.wallet.custom.*` 配置，改 yml 时文案会漂。

**非法金额在提案端就拦，不等 confirm**：卡片是「我可以执行」的承诺，对越界金额发卡是主动误导。

## 7. 幂等：run 状态 CAS，**不是订单 uk**

⚠️ 这条要写清楚，否则评审会误以为「订单号唯一索引保护了 confirm 双击」。

- `confirmWrite` 走 `WAITING_WRITE_CONFIRM → CONFIRMING` 的**状态 CAS**（DB 行锁、一次性消费）
- 而每次尝试都**生成新 orderNo**，撞 `uk_recharge_order_no` 那层防线在这里根本不触发
- 三层防线里的另两层（订单号唯一、流水 `uk_user_idem`）保护的是「同一次下单被重复支付」，
  不是「用户点了两次确认」

另：`markExecuted` 吞掉一切异常、不会让事务回滚。所以不存在「已支付但 run 停在 WAITING」；
唯一的残留是留痕 update 失败 → run 卡 CONFIRMING，但接口仍返回成功文案。

（还有一处存量瑕疵非本次引入：Redis 锁在 `finally` 里释放、**早于事务提交**。
这里不出事纯粹因为状态 CAS 是 DB 行锁——第二个事务的 UPDATE 会被阻塞到第一个提交后才判定。）

## 8. 执行器：下单**和**支付都要做

`createCustomOrder` 只落一张 `PENDING` 订单，**不加余额**；真正到账靠 `pay`。
只做前者的话，用户点了「确认充值」却看到钱没到。前端钱包页走的也同样是这两步。

**到账额度不快照进提案**：它是兑换比例（配置）派生的，提案时刻算好、confirm 时刻配置可能已经变了，
卡片上说「到账 10000」而实际到账 20000 是没法解释的。
卡片只显示金额，**权威数字由执行器写进结果文案**（前端会把它 toast 出来）。

## 9. 前端：最危险的地方其实是卡片文案

未知 actionType 会落到 `writeActionReason` 的默认分支显示成**「取消任务完成状态」**，
按钮是通用的「继续」。充值卡如果长这样，**「确认」就和所有其他确认长得一模一样**——
而它点下去动的是钱。

所以：

- 类型显示「充值确认」、理由显示「给钱包充值 10 元，确认后立即到账」
- 按钮改成「**确认充值 ¥10**」而不是「继续」
- 金额显式念出来——这也是分类器万一误判时唯一的兜底（弹出「充值 1000 元」比弹出「继续」显眼得多）

顺带修掉一个存量问题：`'expired'` 状态一直定义了却**没有任何地方设置它**，
死卡会永远可点、永远报错。现在 `CONFLICT` 时置 `expired`。

## 10. Agent / Workflow 不接计费（2026-09-21 决定，**终态**）

`aiBillingService.reserve` 只在普通聊天链路（`AiMessageServiceImpl`），
`streamAgentReply` 没有计费。**这个状态就是终态，不再推广。**

原 V4 待办「推广到 Agent / Workflow」已明确**不做**：聊天侧已经有完整的
预扣 → 结算 → 差额退回链路，Agent / Workflow 再接一遍是重复劳动，收益不匹配。

> 这里**曾经**登记过一条待防的隐患：「一旦 Agent 接上预扣，0 余额用户会在
> `streamAgentReply` 之前被 402 拦住——因为没钱所以不能充钱」。
> 既然不接，它就不存在了。**保留这段说明是为了讲清它为什么被划掉**——
> 免得后来人翻到旧记录，又去防一个不会发生的问题。

## 11. 改动清单（实际）

| 文件 | 改动 |
|---|---|
| `ai/agent/WalletAmountParser.java` | 新增：原话金额解析（阿拉伯 + 中文数字） |
| `ai/agent/WalletAgentStepDecider.java` | 新增：确定性决策器，`stepNo > 1` 自终止 |
| `ai/agent/WalletAgentRuntime.java` | 新增：第 4 个域，钩子永不返回 null、`isTerminalExtension` = false |
| `ai/agent/WalletAgentWriteActionExecutor.java` | 新增：下单 + 支付 + before/after 余额留痕 |
| `ai/tool/WalletBalanceTool.java` | 新增：只读查余额（手写 `ToolCallback`，无参工具必须如此） |
| `ai/agent/AgentWriteProposal.java` | 加 `TYPE_RECHARGE_WALLET` + 第 9 个字段 `payYuan` |
| `ai/agent/AgentWriteActionService.java` | `parseWriteAction` 补 `payYuan`（**编译器不报错的那一处**） |
| `ai/agent/AgentRuntimeRouteRegistry.java` | 加第 4 行登记 + `walletAgentIntents()` |
| `ai/planner/AgentPlannerSupport.java` | `isWalletAgentIntent` + `decideWalletAgent`（插在 `isLearningDomain` **之前**） |
| `service/impl/AiIntentClassifierImpl.java` | prompt 加 `WALLET_AGENT` + **反例**（问余额≠充值） |
| `service/impl/AiModelServiceImpl.java` | `buildToolCallbacks` 默认分支挂上余额工具（**一行**） |
| `service/impl/AiMessageServiceImpl.java` | 游客文案加钱包分支 |
| `blog-frontend/src/api/ai.ts` | union + `payYuan?` |
| `blog-frontend/src/components/ai/AiAssistant.vue` | 卡片文案 + 专属按钮 + `expired` 状态 |

**不加 `QUERY_WALLET` 动作类型**：钱包域没有可观察的东西（金额在原话里、余额在执行后才有意义），
加一个只读步骤等于为了填思考面板烧一步；顺带也避开了改 3 个既有 executor 的穷尽 switch。

**`WalletAgentRuntime` 注入的是 `GeneralAgentActionExecutor`**：基类要求传非空的
`AgentActionExecutor`，而按基类型注入会撞三个实现报 `NoUniqueBeanDefinitionException`。
本域白名单里只有终态动作，那个执行器**不可达**——不要为一个不可达路径写抛异常的 stub 类。

## 12. 验证

- 单测 5 个新类 29 例 + 既有 4 个测试类同步（注册表构造、Planner 注册表 mock、工具暴露断言、prompt 断言）
- **两处反向验证**：
  - 把过滤/解析点改坏 → 精确红在预期断言上
  - `parseWriteAction` 的 `payYuan` 改成 `null` → `confirmRechargeWalletParsesAmountAndExecutes`
    红在「提案缺少充值金额」——**这条正是为「编译器不报错的那处」写的**
- 评测门：改了分类器 prompt / 新意图 / 新写动作 → **在线门**；改了 `AgentPlannerSupport` 裁决规则 → **离线门**
