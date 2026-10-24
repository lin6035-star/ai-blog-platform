# P3 分类器收缩 —— 结论与交接（2026-10-01，最终）

> 会话交接文档。权威设计口径见 `agent-token-optimization-design.md` §16.4 / §16.7。
> 全部证据报告留档在 `docs/agent/p3-reports/`（每轮配对报告 + 覆盖检查 + B 的形状数据）。

## 一句话现状

**P3 已收口并激活：A 通过并成为默认模式，B 停止；复合请求保守回退 FULL，配置仍可一键回退。**

| 项 | 状态 |
|---|---|
| 候选 A（按需装载） | ✅ **通过 spike**。回退前 26 条在线降幅 33.8%；按当前 Prompt 于 2026-10-02 重测，补复合回退后离线降幅 **23.1%** |
| 候选 B（Route + Extractor） | ❌ **停止**，保留实验报告（结论见下） |
| 复合请求保守回退 | ✅ 已实施（`CandidateA.COMPOUND_SIGNALS`） |
| 一键回退开关 | ✅ 已实施（默认 `ON_DEMAND`；`BLOG_AI_CLASSIFIER_MODE=FULL` 显式回退） |
| 生产是否启用 | ✅ **已启用**。2026-10-02 三条真实模型定向样例通过；未跑 67 条完整在线门 |

## 结论一：B 停止（不是"永远无效"）

B 相对 A 只再省 **10.8%**（< 15% 门槛），而**调用次数 10 → 19**、**P50 延迟 +40%**。
B 的收益全部来自两条（闲聊省 92%、A 走 FULL 兜底的样例省 38%），其余 8 条贵 12~19%。

**严谨表述**：B 不是理论上永远无效——**双调用分层**在"供应商不限流 + 模型输出稳定 + Route 能压到很小"
的条件下是对的方向。它是在**当前模型（qwen-plus-latest）、当前 Prompt 结构（19 块里 27% 是输出契约）
和当前供应商限流条件**下不成立。设计稿里应按这个口径写。

## 结论二：A 通过，最终形态是四层

```text
1. 复合目标信号命中（再/然后/同时/顺便/接着/以及/并且/之后） → FULL     ← 激活前新增
2. 原文线索命中 → 装"公共块 + 命中域块"（命中 > 4 块 → FULL）
3. 一条线索都没命中 → FULL（闲聊或没写进词表的新说法，不赌）
4. 页面专属（editor-* → EDITOR + PAGE_ACTION_RULES）

其中 FULL 兜底**直接复用生产的 19 块拼装**，逐字相同（不是自己再拼一遍）
```

### 为什么必须加复合回退（真实漏洞，Codex 复核指出）

> "**点赞这篇文章，再给我弄个复习路线**"

`点赞` 命中文章块，而"复习路线"不在学习词表里（表里只有 学习/计划/规划/难点/拆解/拆/不懂/练习），
于是学习规则被裁掉，**第二个目标静默丢失**。多目标编排是第三刀/第四刀刚做完的能力，
也正是最贵的那部分（丢一个目标 = 一次编排失败），不能为省 Token 拿它冒险。

**代价是诚实的**：26 条 spike 里 6 条复合样例会退回 FULL；当前 Prompt 的离线总降幅为 **23.1%**
——仍远超 15% 门槛；简单请求（闲聊、单域动作、编辑器、钱包）的收益一个不少。

**实测验证（2026-10-01）**：

| 样例 | FULL | A |
|---|---|---|
| "点赞这篇文章"（单目标） | 8417 | **4511**（按需，省 46%） |
| "点赞这篇文章，**再**分析一下它写得怎么样" | 8425 | **8425**（回退 FULL，token 逐位相同） |

两侧硬门 PASS。A 侧 token 与 FULL 完全一致 → 回退拿到的确实是同一个 prompt。

## 一键回退开关（激活方式）

```yaml
blog:
  ai:
    classifier-mode: ${BLOG_AI_CLASSIFIER_MODE:ON_DEMAND}
```

- **默认 `ON_DEMAND`**：P3 已激活；需要回退时显式设置 `BLOG_AI_CLASSIFIER_MODE=FULL`
- 切换只影响"选哪个 system prompt"这一处；调用链、解析、repair、计划定位、planId 解析、旧字段兜底全部不变
  ——这正是"改配置就能回退"能成立的前提
- 启动日志会打印 `[P3] 分类器 prompt 模式 = X`（"以为切回去了"是这类开关最常见的误判）
- `repair` 轮两种模式走**同一条**短 prompt：它是 §6.6 登记的基线缺口，与本次切换无关，
  改了它就不是"回退"了
- 契约测试：`ClassifierModeSwitchTests`（FULL 模式逐字等于旧 prompt / ON_DEMAND 走候选 A /
  复合请求回退 / repair 两模式一致 / 默认值是 ON_DEMAND）

## 数据总览

| 轮次 | 对照 | 范围 | 结果 |
|---|---|---|---|
| 轮 1 | FULL → A（纯裁块） | 26 条全量 | 输入 **-33.8%**、调用 26→26、P50 3865→3108 ms、硬门 25/26 |
| 轮 2 | A + prompt 提醒 | 26 条全量 | 31.5%、硬门 25/26 —— **被证伪，已回退** |
| 轮 3 | A vs B | 挑跑 11 条（10 个 layer） | B 相对 A 只 **-10.8%**、调用 10→19、P50 +40% |
| 离线 | A + 复合回退 | 26 条 | **-23.1%**（2026-10-02 重测，12/26 走 FULL 兜底） |

**注**：23.1% 是当前离线字符降幅，**回退后的完整在线降幅未实测**——按决策**不跑全量在线确认**
（离线与在线的吻合度此前一直很好：33.4% vs 33.8%；且复合回退只会让 prompt 更长更保守，
不会引入新的正确性风险）。2026-10-02 已用当前 judge model 定向验证纯问答、单文章动作、单优化 Workflow 三条，3/3 通过。

## ⚠️ 模型口径（必须写清）

**本轮 A/B 历史实测用的是 `qwen-plus-latest`**；2026-10-02 激活验证使用当前默认的
`qwen3.7-max-2026-05-20`。两轮延迟与绝对 Token 不直接横比。

AGENTS.md / CLAUDE.md / 设计稿原本写的是 `qwen3.7-max-2026-05-20`（那是 5a 轮次的模型），
**本轮已全部纠正为"当时模型"并加注说明**。

- 不影响本轮结论：A/B 两侧用**同一个模型**，配对差值成立
- **但不能把本轮的延迟（P50 3.1s / 6.3s）与之前 max 模型的基线直接对比**

## 方法论发现：模型输出不稳定，单次小样本不能定生死

实测（**同一 prompt、同一输入 token 数逐位相同**，跨轮次结果会翻）：

| 样例 | 侧 | 5 次结果 |
|---|---|---|
| Workflow：写一篇新文章 | A | PASS PASS **FAIL FAIL FAIL**（3/5 失败） |
| 文章动作：单动作不得带分析证据 | **FULL（基线自己）** | PASS PASS **FAIL** PASS **FAIL** |

1. 它是**概率性失败**（60% / 40%），不是随机抖动——复跑不会好转
2. **基线自己会红**：用"单次运行的硬门通过数"做淘汰，可能淘汰掉更好的候选，也可能误判基线是干净的

因此：`-Dp3Sample` 支持逗号分隔多个子串做定点复跑；失败样例应复跑 2~3 次再定性。

## 已登记的缺陷与欠账

| # | 内容 | 时机 |
|---|---|---|
| 1 | **taskGoals 偶发缺失**："写一篇新文章"历史上 3/5 次漏输出 taskGoals；复合请求现已由旧 Workflow 字段 fail-closed，不能静默执行半份，模型输出质量仍需观测 | 后续观测 |
| 2 | `actionType` 完整枚举只在 `NAVIGATE` 块（`ClassifierPromptBlocks:98`）；命中文章动作线索时只装 `ARTICLE_CONTENT` → 用户说"**取消点赞**"时模型看不到 `unlikeArticle`。评测集不覆盖、离线覆盖检查也抓不到 | 激活后欠账；修法：把枚举拆成独立小块并入公共块（它本就是字段契约），或命中动作线索时同时装 `NAVIGATE`（+688 字符） |
| 3 | `REPAIR` prompt 不自包含（§6.6）——本轮没动它（同口径优先） | 独立议题 |

已修掉的：**旧字段择一失败**（"优化 + 搜索"）→ `AiIntentClassifierImpl.backfillWorkflowFields`
（恰好一条合法 `WORKFLOW_HANDOFF` 时确定性回填；五种类型白名单；多条不强行择一；7 条边界测试锁定）。

## 下一步

1. ✅ **激活验证**：默认切到 `ON_DEMAND`，启动日志确认生效；纯问答、单文章动作、单优化 Workflow 三条真实模型样例 3/3 通过
2. **完整在线门**：当前固定 JSON 已扩到 67 条，`AiIntentClassifierIntegrationTests` 完整为 70 个 execution；
   按本轮决策**不跑全量**，真要跑时需先定跑法——在 spike 框架里跑（零侵入）
   还是接进生产后跑（更真实但已是实施）
3. **运行期观察**：练习项目不同时跑新旧分类器（那会白花额度与 RPM）；用"配置开关 + 观测 `[PERF-AI]`
   token 口径"替代 SHADOW，判错就切回 FULL
4. ✅ 已同步纠正 AGENTS.md / CLAUDE.md / 设计稿的**模型口径**与 P3 状态

## 产出文件清单

**生产（本轮及前几轮改动）**
- `service/impl/CandidateA`：线索表按域补齐 + 命中计数按块 + **复合信号回退** + `full()` 复用生产拼装 + `assembleWithCommon`
- `service/impl/AiIntentClassifierImpl`：`backfillWorkflowFields` 兜底 + `systemPrompt/sPromptFor` 模式选择 + 启动日志
- `config/BlogAiProperties`：`classifierMode` 枚举（默认 ON_DEMAND）
- `ai/ClassifierProbe`：`CallRole` 增加 `ROUTE` / `EXTRACTOR`
- `resources/application.yml`：`blog.ai.classifier-mode: ${BLOG_AI_CLASSIFIER_MODE:ON_DEMAND}`

**测试侧**
- `ClassifierCandidate`（FULL / ON_DEMAND / ROUTE_EXTRACTOR，候选自己决定调几次）
- `ClassifierCandidateRunner`（同构调用链）+ `ClassifierCandidateRunnerTests`（反射锁 user prompt 一致）
- `CandidateB` + `CandidateBShapeTests`（B 的实验代码，**保留**）
- `CandidateACompoundTests`（复合回退边界）、`ClassifierModeSwitchTests`（一键回退契约）
- `AiIntentClassifierBackfillTests`（兜底 7 边界）、`CandidateACoverageTests`（65 例覆盖检查）
- `ClassifierP3BaselineTests#collectCandidatePairs`（交错配对采集，`-Dp3Candidates` / `-Dp3Sample`）

**证据留档**（`docs/agent/p3-reports/`）：`p3-pairs-26-round1-no-reminder.txt`（代表 A 原始数据）、
`p3-pairs-26-round2-with-reminder.txt`（被证伪的补救）、`p3-pairs-11key-with-backfill.txt`、
`p3-pairs-ab-11key.txt`（A vs B）、`candidate-a-coverage-65cases.txt`、`candidate-b-shape.txt`

## 跑法

```powershell
$env:ALIYUN_API_KEY = [Environment]::GetEnvironmentVariable('ALIYUN_API_KEY','User')

# 离线（零成本）：切块一致性 / A 降幅 / 口径契约 / 覆盖检查 / 兜底边界 / 复合回退 / 模式开关 / B 形状
.\mvnw.cmd test "-Dtest=ClassifierPromptBlocksTests,CandidateAOfflineTests,ClassifierCandidateRunnerTests,CandidateACoverageTests,AiIntentClassifierBackfillTests,CandidateACompoundTests,ClassifierModeSwitchTests,CandidateBShapeTests"

# 在线：A 对现状（全量 26 条约 11 分钟）
.\mvnw.cmd test "-Dtest=ClassifierP3BaselineTests#collectCandidatePairs" "-DrunRealLlm=true" "-Dp3ThrottleMs=8000"

# 在线：A vs B
.\mvnw.cmd test "-Dtest=ClassifierP3BaselineTests#collectCandidatePairs" "-DrunRealLlm=true" "-Dp3ThrottleMs=8000" `
  "-Dp3Candidates=ON_DEMAND,ROUTE_EXTRACTOR" "-Dp3Sample=纯概念问答,单节内容评价,单动作,动作在前,优化当前文章,优化 + 搜索,分析计划内容,钱包,选中 C++,编辑器页保存草稿"

# 定点复跑（-Dp3Sample 支持逗号分隔多个子串）
.\mvnw.cmd test "-Dtest=ClassifierP3BaselineTests#collectCandidatePairs" "-DrunRealLlm=true" "-Dp3ThrottleMs=4000" "-Dp3Sample=单动作,动作在前"
```

报告落点：`blog-system/target/classifier-p3-pairs.txt`、`candidate-a-report.txt`、`candidate-a-coverage.txt`、
`candidate-b-shape.txt`。**`clean` 会清空它们**，要留档就复制到 `docs/agent/p3-reports/`。

## 踩过的坑（累计）

1. **`clean` 会删 `target/`**：dump 基线、证据报告都在里面 → 生成源放 `src/test/resources/`，报告复制到 `docs/`
2. **`@SpringBootTest` 需要 `ALIYUN_API_KEY`**：漏设会连锁几十个 `ApplicationContext failure` 假失败
3. **改了方法签名/构造器必须 `clean test-compile`**：增量编译会假装通过（本轮加构造器参数后又踩一次，
   两处手工 `new` 的测试跟着红）
4. **在线跑必须带退避**：`-Dp3ThrottleMs=8000`（四轮共约 300 次调用，零 429）
5. **控制台码页会吃掉中文**：报告必须写文件（UTF-8）
6. **后台任务要带 `workdir`**：`run_in_background` 的 pwsh 不继承工作目录
7. **测试侧类跨包不可见**：候选/Runner 在 `service.impl`，被 `com.hailin.blogsystem` 的采集测试引用时必须 `public`
8. **改源码与后台 Maven 抢 target**：等它进入测试阶段再改

## 未提交提醒

全部改动**仍在工作区**（`01myBlog\.git` 是空目录，这个项目没有版本控制）。
