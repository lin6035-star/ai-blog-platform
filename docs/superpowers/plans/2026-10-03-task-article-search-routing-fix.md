# Task Article Search Routing Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the legacy RAG answer path for standalone article searches while preserving Task orchestration and recommendation cards for composite searches.

**Architecture:** Add one narrow routing exception in `TaskPlanningFacade` for a single validated `ARTICLE_SEARCH` goal whose legacy intent agrees. Keep composite searches in Task orchestration and translate their existing structured article output into the shared `references` protocol at the chat boundary.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, AssertJ, Maven

---

### Task 1: Restore standalone RAG routing

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/plan/TaskPlanningFacadeTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/plan/TaskPlanningFacade.java`

- [x] Add a regression test using “有没有关于 Redis 的文章？” and assert `LEGACY`.
- [x] Add a companion assertion that `ARTICLE_WRITE + ARTICLE_SEARCH` remains `ORCHESTRATE`.
- [x] Run the two tests and confirm the standalone case fails before production changes.
- [x] Add a narrow single-search predicate requiring one executable search step, no uncovered goals, and legacy `intent=ARTICLE_SEARCH`.
- [x] Re-run `TaskPlanningFacadeTests` and confirm both route contracts pass.

### Task 2: Restore cards for composite Task searches

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/TaskArticleReferenceSupport.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/TaskArticleReferenceSupportTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`

- [x] Write tests that convert `StepResult.output.articles` to `ArticleRagReferenceVO`, preserve order, deduplicate by article ID, and ignore malformed rows.
- [x] Run the new test and confirm it fails because the support class does not exist.
- [x] Implement the small pure converter.
- [x] Add `references` from the converter to the Task orchestration `STOP` event.
- [x] Re-run the converter and focused Task tests.

### Task 3: Focused verification

**Files:**
- Verify only; no production files added.

- [x] Run `TaskPlanningFacadeTests,TaskStepExecutorTests,TaskArticleReferenceSupportTests,TaskResultRendererTests`.
- [x] Inspect the scoped diff and confirm no RAG ranking or frontend behavior was changed.
- [x] Do not commit; project instructions require explicit user authorization for commits.

### Task 4: Unify composite Task retrieval with RAG

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/TaskStepExecutorTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/executor/ArticleSearchExecutor.java`

- [x] Replace the site-search mock with `ArticleRagSearchService` and assert the executor passes an `ARTICLE_SEARCH` intent with the Task query as `keyWord`.
- [x] Add Gate-output fixtures with repeated chunks from one article and assert article-level deduplication preserves RAG order.
- [x] Run the executor tests and confirm the old constructor/retrieval path fails.
- [x] Replace `ArticleSearchService` and the second database lookup with `ArticleRagSearchService`.
- [x] Keep deterministic summaries and structured article evidence; do not call the answer model.
- [x] Run the focused Task and RAG service tests without real model evaluation.
