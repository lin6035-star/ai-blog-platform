# Task 文章搜索路由回归修复设计

## 问题

单独询问“有没有关于 Redis 的文章？”时，分类器同时输出旧字段 `intent=ARTICLE_SEARCH` 和单目标
`taskGoals=[ARTICLE_SEARCH]`。`TaskPlanningFacade` 优先把它送进 Task 编排，`ArticleSearchExecutor`
随后调用站内全文搜索并由 `TaskResultRenderer` 生成确定性汇总，导致原有的 RAG 检索、模型回答和
`references` 推荐卡片全部被绕过。

## 目标行为

1. 单一 `ARTICLE_SEARCH` 目标且旧分类字段也是 `ARTICLE_SEARCH` 时，显式返回 `LEGACY`，继续使用已验证的
   RAG 问答链路。
2. 含其他步骤的复合任务继续进入 Task 编排，不能回退旧单意图链后只执行搜索。
3. 复合 Task 中的 `ARTICLE_SEARCH` 复用 `ArticleRagSearchService` 的混合召回、Rerank、Gate 和受控兜底，
   但不调用回答模型；Gate 后的上下文按 `articleId` 去重后生成确定性汇总。
4. `StepResult.output.articles` 转换为现有 `ArticleRagReferenceVO` 形态，并放进 `STOP.references`，
   让前端继续显示可点击文章卡片。
5. 文章引用按 `articleId` 去重并保持步骤、命中顺序；缺失或非法输出忽略，不影响任务汇总。

## 非目标

- 不在复合 Task 搜索中额外调用回答模型。
- 不修改 RAG 召回、Rerank、Gate、站内搜索算法或前端卡片组件。
- 不运行真实 LLM、Rerank 或全量测试。

## 验证

- 路由测试锁定单一搜索为 `LEGACY`、写动作加搜索仍为 `ORCHESTRATE`。
- 引用转换测试锁定字段映射、去重和非法输出容错。
- 运行相关 Task 路由、执行器和引用转换测试。
