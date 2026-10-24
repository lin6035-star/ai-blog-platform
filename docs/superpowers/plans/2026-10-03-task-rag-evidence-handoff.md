# Task RAG Evidence Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让复合 Task 的文章横向分析消费统一 RAG 返回的正文 chunk，同时不把正文带入后续会话轮次。

**Architecture:** 搜索步骤分别输出卡片数据 `articles` 和本轮证据 `ragContexts`。分析步骤恢复 `ArticleRagContext` 后复用 `ArticleRagPromptBuilder`；历史 `articles` 摘要保持兼容。

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, Mockito, Maven

---

### Task 1: 锁定搜索步骤的双输出契约

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/TaskStepExecutorTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/executor/ArticleSearchExecutor.java`

- [x] 在 `searchReturnsTitles` 中断言 `articles` 仍按文章去重，并断言 `ragContexts` 保留同一文章的两个 chunk。
- [x] 运行 `./mvnw -Dtest=TaskStepExecutorTests test`，确认新断言因 `ragContexts` 不存在而失败。
- [x] 在执行器中按检索顺序输出最多 5 个 `ragContexts`，字段为 `articleId/title/chunkIndex/content`。
- [x] 重跑定向测试，确认通过。

### Task 2: 让分析步骤复用统一 RAG Prompt

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/ArticleAnalysisExecutorTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/executor/ArticleAnalysisExecutor.java`

- [x] 构造包含同篇多 chunk 的 `ragContexts`，断言传给 `runAnalysisOnly` 的 goal 含两个完整来源、片段序号和正文。
- [x] 运行 `./mvnw -Dtest=ArticleAnalysisExecutorTests test`，确认测试因仍读取 `articles` 而失败。
- [x] 注入 `ArticleRagPromptBuilder`，解析前序 `ragContexts` 并用其构造 goal；无 `ragContexts` 时保留旧摘要路径。
- [x] 重跑 `ArticleAnalysisExecutorTests`，确认通过。

### Task 3: 定向回归与文档同步

**Files:**
- Modify: `docs/agent/agent-task-orchestration-design.md`

- [x] 记录 `articles` 与 `ragContexts` 的职责分离，以及正文只在当前 Task run 内消费。
- [x] 运行 `./mvnw -Dtest=TaskStepExecutorTests,ArticleAnalysisExecutorTests,TaskArticleReferenceSupportTests test`。
- [x] 检查 diff，确认没有修改真实模型、Rerank 配置或会话历史注入逻辑。
