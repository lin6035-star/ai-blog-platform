# Task RAG 正文证据传递设计

## 问题

复合请求“帮我找找关于 Redis 的文章，并横向分析一下各篇文章”会拆成
`ARTICLE_SEARCH -> ARTICLE_ANALYSIS`。搜索步骤虽然调用统一的 `ArticleRagSearchService`，
但按文章去重后只保留每篇首个 chunk；分析步骤又把每篇内容截到 240 字，导致横向分析缺少正文依据。

## 设计

1. `ArticleSearchExecutor` 保留两份不同用途的输出：
   - `articles`：按文章去重，供确定性汇总和推荐卡片使用；
   - `ragContexts`：按 Rerank/Gate 顺序保留最多 5 个正文 chunk，供紧随其后的分析步骤使用。
2. `ArticleAnalysisExecutor` 优先读取 `ragContexts`，恢复为 `ArticleRagContext`，并复用
   `ArticleRagPromptBuilder` 拼装证据；旧的 `articles` 摘要输入只作为历史任务恢复兼容。
3. 正文只写入当前 Task step 的 `outputJson` 并传给当前分析子 run。会话消息只保存最终回答，
   后续轮次不会自动重放 `ragContexts`，也不写入 Memory。
4. 不重复检索、不额外调用回答模型；现有子 Agent 仍负责本步最终分析和状态收敛。

## 边界

- 最多 5 个 chunk；当前切块每块最多 800 字，正文上界约 4000 字。
- `articles` 与 references 的按文章去重行为保持不变。
- `ragContexts` 缺失或格式非法时，回退现有 `articles` 摘要路径，保证历史持久化任务可恢复。
- 本次只跑 Task 执行器相关测试，不运行真实 LLM、Rerank 或全量测试。

## 验收

- 同一文章的第二个命中 chunk 不再从 `ragContexts` 丢失。
- `ARTICLE_ANALYSIS` 收到多个正文 chunk，且 Prompt 使用与单任务 RAG 相同的来源格式。
- 推荐卡片仍按文章去重。
- 旧 `articles` 输出仍可被分析步骤消费。
