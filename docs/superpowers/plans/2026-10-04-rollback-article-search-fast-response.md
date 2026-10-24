# Roll Back Article Search Fast Response Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 恢复单目标 `ARTICLE_SEARCH` 的 RAG + flash 详细生成链路，撤销导致回答只剩短摘要的确定性直出。

**Architecture:** 保留分类器、Planner、RAG、Memory、计费和 SSE 原协议，只删除 `streamNormalChatFlow()` 与 `streamGuestChatFlow()` 顶部的快速返回及其专用渲染代码。新增一条登录流回归测试，锁住多个 RAG chunks 必须进入最终 Prompt 且正文来自 `AiModelService.streamChat()`。

**Tech Stack:** Java 17、Spring Boot、Project Reactor、JUnit 5、Mockito、H2

---

### Task 1: 用测试复现质量退化

**Files:**
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ArticleSearchQualityPathTests.java`

- [x] 写集成测试：固定 `ARTICLE_SEARCH` 路由和两个 RAG chunks，断言 `AiPromptService.buildPrompt()`、`AiModelService.streamChat()` 被调用，传给模型的 Prompt 同时包含两个 chunk，SSE 正文是模型返回的详细回答。
- [x] 运行 `./mvnw.cmd "-Dtest=ArticleSearchQualityPathTests" test`，确认当前确定性快路径导致测试失败。

### Task 2: 移除确定性快路径

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Delete: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleSearchResponseRenderer.java`
- Delete: `blog-system/src/test/java/com/hailin/blogsystem/ai/rag/ArticleSearchResponseRendererTests.java`
- Delete: `blog-system/src/test/java/com/hailin/blogsystem/ArticleSearchFastPathTests.java`

- [x] 删除登录/游客两个 `ARTICLE_SEARCH` 提前返回。
- [x] 删除专用检索、渲染、SSE、性能日志 helper 及渲染器依赖。
- [x] 删除姐夫已确认的 3 个快路径文件。
- [x] 重新运行 `ArticleSearchQualityPathTests`，确认恢复为 GREEN。

### Task 3: 定向回归与记录

**Files:**
- Modify: `docs/agent/ai-assistant-latency-first-batch-design.md`

- [x] 运行 `./mvnw.cmd "-Dtest=ArticleSearchQualityPathTests,ArticleRagSearchServiceTests,TaskStepExecutorTests,TaskArticleReferenceSupportTests,ArticleInteractionStreamTests" test`。
- [x] 运行 `git diff --check`。
- [x] 在设计稿记录：确定性直出因质量回归撤回，搜索恢复约 8 秒的 RAG + flash 路径；真实延迟仍需重启后端后复测。
- [x] 不启动后端，不 commit，不 push。
