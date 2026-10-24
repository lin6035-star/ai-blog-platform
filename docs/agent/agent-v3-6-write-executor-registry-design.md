# V3.6 写动作执行器注册表（A 刀兑现）：AgentWriteActionService 壳化 + 域执行器分发

> 状态：**定稿并实施完成（2026-09-06）**。形态在 V3.5 已与 Codex 对齐（A 记下一刀、第 5 个写动作前做；Codex：A 是顺手清 switch 非主雷，收益/风险比低于 C，故 V3.5 只做路由收口、A 后置）。纯重构：行为零变化、proposal/前端/夹具零迁移。
> 相关：[[agent-module-acceptance-checklist.md]] / [[agent-v3-5-route-registry-design.md]]

## Context

AgentWriteActionService 原为「类名通用、内容双域混合」：switch 4 case（学习 3 + 文章 1）直接注入两域 service（LearningPlansService + LearningStageMapper + ArticlesService），execute* 私有方法 + markExecuted/markArticleTitleExecuted 按域复制（V3.5 登记的触发证据 2/3/4）。第 5 个写动作将至（switch 4 → 5 case 到量化触发点），先注册后落动作。

## 实施内容（纯重构，逐行迁移零语义变化）

### 1. AgentWriteActionExecutor（新接口，ai/agent/）

- `boolean supports(String actionType)`——注册判定（学习执行器认 3 个 TYPE_*、文章执行器认 1 个）
- `WriteActionResult execute(AiAgentRun run, AgentWriteProposal proposal, Long userId)`——record(message, auditPayload)；域执行异常照抛（BusinessException），壳 @Transactional 回滚语义不变
- 执行器不依赖壳（防循环依赖）；壳注入 `List<AgentWriteActionExecutor>`（Spring 自动收集 @Component）

### 2. AgentWriteActionService 壳化

- 构造注入：删 learningStageMapper/learningPlansService/articlesService → 加 List<executors>
- confirmWithLock 分发改分发：proposal 校验 → stream filter supports findFirst → 未命中 SERVER_ERROR「提案数据异常，无法执行」（原 default 语义，未知 actionType 测试断言文案不变）→ execute → markExecuted
- **markExecuted 通用化**：原两法合一（壳统一补 actionType + executedAt，域字段 + before/after 由执行器载荷给）；try-catch 吞留痕失败语义保留
- 保留：confirmWrite/cancelWrite/getWriteAction（锁/CAS/getOwnedRun/requireLogin/状态机）+ parseWriteAction/parseContext/toJson
- 迁出：4 个 execute* + resolvePlanAndStage/resolveTaskIndex/readStageTasks/PlanStageTarget/articleSnapshot + 两 mark* 的域载荷组装

### 3. 域执行器（新 @Component）

| 类 | 依赖 | supports | 内部 |
|---|---|---|---|
| LearningAgentWriteActionExecutor | learningStageMapper + learningPlansService | UPDATE_TASK_DONE / ADD_LEARNING_TASK / UPDATE_LEARNING_TASK | 3 动作 execute（内部 switch）+ 定位器私有化（resolvePlanAndStage/resolveTaskIndex/readStageTasks/PlanStageTarget）+ 载荷组装 |
| ArticleAgentWriteActionExecutor | articlesService | UPDATE_ARTICLE_TITLE | executeUpdateArticleTitle（stale 校验 + 锚定条件更新语义）+ articleSnapshot + 载荷组装 |

### 4. 测试

- AgentWriteActionServiceTests setUp 重装配：壳（runMapper/objectMapper/actionLock/List）+ 真执行器注入原 mock service——**22 个断言全部平移（实际 20 个测试）零改动**；夹具（proposal 平铺 JSON）零改动；未知 actionType 断言文案不变

## 改动清单（实施结果）

| 文件 | 变更 |
|---|---|
| ai/agent/AgentWriteActionExecutor.java（新） | 接口 + WriteActionResult record |
| ai/agent/AgentWriteActionService.java | 壳化（分发 + markExecuted 通用化 + 迁出域逻辑） |
| ai/agent/LearningAgentWriteActionExecutor.java（新） | 学习域 3 动作（迁移 + helper 私有化） |
| ai/agent/ArticleAgentWriteActionExecutor.java（新） | 文章域 1 动作（迁移） |
| AgentWriteActionServiceTests | setUp 重装配（20 断言平移全绿） |

不动：runtime 提案端（Learning/Article）；proposal record；contextJson 平铺形状；前端（ai.ts/AiAssistant.vue/STOP 透出）；Controller 端点；AgentRunSuggestionService（B 刀）；评测门（分类器 prompt / 匹配算法未动 → 无需在线/离线门）。

## 验证结果

1. AgentWriteActionServiceTests 20/20 全绿（行为语义平移，纯重构等价验收）
2. LearningAgentRuntimeTests 21 / ArticleAgentRuntimeTests 16 零回归（runtime 提案端未动）
3. Spring context 装配验证（ArticlesServiceUpdateTitleTests 起 context——壳注入 List<executor> 无循环依赖）
4. AgentRuntimeRouteRegistryTests 4 零回归
5. 手工 E2E（待老大，与 V3.4/V3.5 合并测）：学习页勾选/追加/改名 + 文章页改标题 四动作确认流各走通一次

## 下一刀（本刀完成后）

- **第 5 个写动作**（新动作落执行器即插即用 = 域执行器加 supports/分支 + 域 service 调用 + 分类器分界样例 + 在线门）：候选 = 文章域可见性切换（hideArticle/publishArticle service 现成，需设计目标状态动作语义：targetStatus + stale 校验形态）/ 学习域备注
- B 确认壳基类（与 AgentRunSuggestionService 共享锁+CAS 骨架，可选）
- 工程化补课（老大：Agent 主线写完再补）
