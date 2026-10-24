# Agent 评测门（V3.2 / V3.4）

## 定位与背景

评测门 = 固定样例集（3 个 JSON）+ 一键 runner（`-Dgroups=eval`）+ 回归门槛。

做这件事的动机：受控写已有 2 个写动作，第 3 个动作即将到来。V12.1 修复轮的四个 bug（阶段定位错落、多行消息降级普通聊天、步骤事件缺失、重复任务弹卡）全部靠人肉 E2E 发现。评测门在写动作 ≥3（CLAUDE.md 约定升格点）之前立好，让「改分类器 prompt / 改匹配逻辑 / 新写动作」之后有一键回归手段，边界漂移当场可见。

样例素材全部来自既有测试的历史积累，本轮是收拢归类 + 数据驱动化，**零新增样例、零语义改动**（迁移期两段式共存验证等价后删除旧测试）。

## 评测集清单

| 评测集文件（src/test/resources/agent/） | 驱动测试类 | 样例数 | 在线/离线 | category |
|---|---|---|---|---|
| `planner-evaluation-cases.json` | AgentPlannerEvaluationTests | 19 | 离线 | 既有资产 + 钱包域分界（2026-09-21 加 2） |
| `classifier-evaluation-cases.json` | AiIntentClassifierIntegrationTests | 70 | 在线（真 LLM） | boundary / regression / thinking / baseline / task-goals / workflow-handoff |
| `learning-plan-match-evaluation-cases.json` | LearningPlanMatchEvalTests | 14 + 3* | 离线 | plan-match / plan-title-match / stage-match |
| `jev-decision-evaluation-cases.json` | JevDecisionOnlineTests | 16 | 在线（**真 Jev 服务**，`-DrunRealJev=true`） | Jev goal 层（不是 intent 分界）；2026-10-18 起同时约束“学习类写 goal 必须回退 plus” |

\* 14 个匹配样例 JSON 驱动；3 个 appendTasks 行为测试保持普通 Java 测试（写操作 + 多步动作序列不适合声明式 JSON），同在新类 LearningPlanMatchEvalTests。
\** V3.4 起样例支持 pageType/pageArticleId（构建 PageContextDTO 透传分类器）——article-detail 意图分界可入样例集。样例字段全可空，非 null 才断言。

## 样例分类约定

### 分类器（classifier-evaluation-cases.json）

| category | 判定标准 | 典型样例 |
|---|---|---|
| `boundary` | 意图分界：LEARNING_AGENT（受控写）vs LEARNING_ASSIST（拆解 Workflow）等易漂分界 | 「点名任务名走受控写」 |
| `regression` | 已修 bug 的回溯（摘录丢失 / JSON 混文字 / 静默降级） | 「多行消息加任务不降级」 |
| `thinking` | needsThinking 判定（GENERAL_CHAT 正交分支），含显式 false | 「纯概念问答不思考」 |
| `baseline` | 正向锚点（无分界冲突的基础分类） | 「点名计划阶段求拆解」 |

V3.4 起含文章域分界样例（带 pageType=article-detail + pageArticleId）：「完整新标题改名 → ARTICLE_AGENT（受控写）」boundary 与「泛泛改标题 → OPTIMIZE_ARTICLE_WORKFLOW 不漂移」regression。

2026-09-21 起含钱包域分界：「帮我充10块 / 充五十块 → WALLET_AGENT」boundary，
以及两条 **regression 反例**——「我余额够1000吗」「充值比例是多少」必须落到 GENERAL_CHAT。
反例比正例更要紧：这两句里都带「充值/数字」的字面特征，一旦判成 WALLET_AGENT，
后端会从原话里解析出金额并弹出一张充值卡（卡片会把金额念出来，用户扫一眼就知道不对，但根本不该弹）。

### 阶段定位（learning-plan-match-evaluation-cases.json）

| category | 判定标准 | 对应 target |
|---|---|---|
| `plan-match` | 入口计划点名（打分取最高/并列歧义） | PLAN_MATCH |
| `plan-title-match` | 查询 Tool 详情路径（标题 AND 全命中） | PLAN_TITLE_MATCH |
| `stage-match` | 阶段定位（序号归一 / 主题名标题序号直解 / 跨用户拒绝） | STAGE_MATCH |

## 跑法

前置条件：离线门零前置；在线门需 `ALIYUN_API_KEY` 环境变量（application.yml L31）且能访问 LLM。

```bash
# 离线门（默认首选，秒级，不花钱）：planner 17 + 阶段定位 14 + append 3 = 34 样例
./mvnw test -Dgroups=eval

# 在线门（真实 LLM）：离线门 + 分类器固定样例 67 + 动作分析召回样例 8 + 计划定位 2
./mvnw test -Dgroups=eval -DrunRealLlm=true

# 单类（调试）
./mvnw test -Dtest=AiIntentClassifierIntegrationTests -DrunRealLlm=true

# 在线单例（失败复跑省钱）：按样例 name 子串过滤
./mvnw test -Dtest=AiIntentClassifierIntegrationTests -DrunRealLlm=true -DevalCase=多行

# 动作 + 分析口语化召回门（8 条按组统计，门槛 >= 80%）
./mvnw test -Dtest=AiIntentClassifierIntegrationTests#actionAnalysisExtendedExpressionsReachEightyPercentRecall -DrunRealLlm=true
```

Windows 用 `.\mvnw.cmd`；PowerShell 设置环境变量用 `$env:ALIYUN_API_KEY="..."`。

### 跑法约定（2026-09-30 起，硬约束）

**默认只跑与本次改动相关的类 / 样例，不要顺手跑全量门。**

| 目的 | 命令 | 成本 |
|---|---|---|
| 只验某个类 | `-Dtest=XxxTests` | 秒级 ~ 分钟级，**零 LLM** |
| 只验 1~4 条在线样例 | `-Dtest=AiIntentClassifierIntegrationTests -DrunRealLlm=true -DevalCase=子串` | 每次调用都花钱 |
| 全量在线门 | `-Dgroups=eval -DrunRealLlm=true` | **数分钟 + 数十次 LLM 调用** |

全量门**必须先经确认**再跑：它真花额度，且供应商有速率限制——历史上 49 例分类器调用跑到底部时，末尾几条就容易报
「分类器调用失败」（属基础设施失败，不计入模型准确率）。单例失败先复跑一次再定性，**LLM 抖动 ≠ 回归**。

2026-10-02 固定 JSON 样例为 **67 条**；2026-10-18 新增 3 条学习域只读回归样例后为 **70 条**，
`AiIntentClassifierIntegrationTests` 完整运行是 **73 个 JUnit test execution**
（70 条参数化样例 + 2 条计划定位 + 1 条召回率聚合测试）。
新增的 5b 样例继续要求分类器完整产出 handoff 后的搜索/比较目标；是否可执行由 Planner 按后置边界 fail-closed，
“模型不能丢目标”和“第一版不能执行该组合”是两个层级，不能通过删目标让评测变绿。
当时的 judge model `qwen3.7-max-2026-05-20` 的 5a 定向门 15/15 通过；P3 A/B 历史测量使用
`qwen-plus-latest`，2026-10-02 激活验证又使用当前默认的 `qwen3.7-max-2026-05-20`，不同模型的延迟不可直接比较。
2026-09-30 的 49/49 与 2026-10-01 的 15/15 都是历史快照；67 条当前数据集尚未跑完整在线门，不能写成当前全绿。
2026-10-02 `ON_DEMAND` 激活只跑了 3 条真实模型定向样例（纯问答 / 单文章动作 / 单优化 Workflow），3/3 通过。

**分类器样例的断言维度（2026-09-30 新增）**：`expectedRequestKind` / `expectedTaskGoalTypesContains` /
`expectedTaskGoalCountAtLeast`。第三刀引入 `taskGoals` 后这个字段曾**完全没有断言**，于是
「模型只拆出一个目标」这种多目标静默丢失在离线门与在线门之间穿了过去（实例见
`docs/agent/agent-task-orchestration-design.md` §5.3）。复合目标样例优先用这三个字段锁「目标没丢」；
**意图分界不稳定的句子不要写死 `expectedIntent`**（实测「点赞+搜索」在 `ARTICLE_ACTION` / `ARTICLE_AGENT` 之间漂移）。

**2026-10-18 新增反向维度 `expectedTaskGoalsBlank`**：上面的维度只能抓「目标少了」，抓不到「目标多了」。
真实事故是「查学习计划」被模型多吐了一份 `taskGoals=[ARTICLE_SEARCH]`，任务编排据此抢跑、把只读查询
执行成了文章检索（见 `docs/agent/学习域只读路由修复设计.md`）。只读查询 / TOOL 类样例必须写
`expectedRequestKind: "CHAT"` + `expectedTaskGoalsBlank: true`；以后新增只读意图样例请沿用。

语义约定（定死）：

- `-Dgroups=eval` = **只执行确定性离线门**；在线分类器类会被选中但 skipped（无 runRealLlm=true），绝不发真实 LLM 请求
- `-Dgroups=eval -DrunRealLlm=true` = 离线门 + 分类器真实 LLM

## 回归门槛规则

**必须跑在线门**（`-Dgroups=eval -DrunRealLlm=true`，全绿才能收口）：

- 改动分类器 prompt（AiIntentClassifierImpl.buildSystemPrompt，含 repair prompt）
- 改动意图枚举、路由优先级、受控写分界语义（用户点名任务名 = 受控写 vs AI 生成 = Workflow）
- 新写动作 / 新意图上线前
- 写动作 ≥3 的评测网升格评审前

**改动 Jev 快速路径**（`JevFastPathMapper` 白名单 / `JevDecisionProtocol` / 判定阈值）必须跑：

1. Jev 定向离线（零 LLM）：`-Dtest=JevFastPathMapperTests,JevWorkflowHandoffTests,JevFastPathClassifierTests,JevDecisionProtocolTests`
2. 真实 Jev 评测：`-Dtest=JevDecisionOnlineTests -DrunRealJev=true`（16 例 × repeat，真花 Jev 额度）
3. 若同时改了分类器 prompt / 路由优先级，再按上面的在线门跑 plus。

2026-10-18 起学习类三个 Workflow goal 已移出快速路径；`JevFastPathMapperTests.learningWorkflowGoalsAlwaysFallBackToPlus`
与 `JevWorkflowHandoffTests.jevLearningPlanNeverReachesPlannerDirectly` 是白名单回归锁。

**必须跑离线门**（`-Dgroups=eval`，全绿才能收口）：

- 改动 matchActivePlansByMessage / matchPlansByTitle / matchStagesByMessage 算法（含 tokenize/score/序号归一 helpers）
- 新增或修改任何样例 JSON
- 改动 AgentPlannerSupport 裁决规则

日常小改动只跑相关单测（项目测试偏好），不强制过门。

## 新增样例流程

1. 写 JSON 行（定 category/name/message/期望字段；期望字段可空，非 null 才断言）
2. 单例跑通（分类器样例：`-DevalCase=子串`；阶段定位样例：直接跑新类）
3. 跑对应门全绿
4. 更新本文档的清单表（如样例数变化）
5. commit 注明样例动机（对应 bug 或分界决策），JSON 样例文件含中文 name 可直接读

## 失败处理约定

- 在线门失败：先单例复跑 1 次（`-DevalCase`）排除 LLM 抖动；连续两次失败才算回归
- 分类器返回“分类器调用失败，降级为普通聊天”时，先按 429 / 超时 / 供应商异常处理；该结果是基础设施失败，不计入模型意图准确率或召回率
- 判定为「改 prompt 还是改样例」：样例是历史锁定的事实行为（分界决策/bug 修复），**改样例必须记录原因**（防止用改样例掩盖回归）；改 prompt 必须跑全量在线门确认其他样例未漂
- confidence 阈值类断言失败需先看分布（模型版本/温度变化可能整体偏移）

## 已知限制

- RedisConnectionTests / AiVectorStoreSmokeTests 依赖本地 Redis/ES 且未门控（范围外，后续轮次可加同类属性门控）；**评测类（AiIntentClassifierIntegrationTests / LearningPlanMatchEvalTests）已 @MockBean VectorStore——评测门不依赖本地 ES 可用性（2026-09-06 V3.4 修正）**；Redis 本地不可用时评测类 context 加载不受影响但业务写路径依赖 Redis 的断言会跳过
- 参数化单例 CLI 过滤依赖 `-DevalCase` 系统属性（surefire 无法按参数化 invocation 过滤）
- LLM 抖动风险：温度 0.2 已收敛，仍按「连续两次失败才算回归」兜底
- 默认套件含 34 例离线评测（秒级，可接受；若膨胀考虑 `-DexcludedGroups=eval` 配默认 profile）
- docs 目录不在 blog-system git 根内（历史结构问题），本文档不受版本控制
