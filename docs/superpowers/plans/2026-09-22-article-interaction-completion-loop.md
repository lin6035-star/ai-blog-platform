# Article Interaction Completion Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move low-risk article interactions from frontend commands to backend-authoritative execution, persist every action result, and render the final answer only from those results.

**Architecture:** First make like, favorite, and follow operations return stable structured outcomes while preserving the existing HTTP behavior. Then add a narrow deterministic article-interaction batch runner that creates one Agent run and one `ARTICLE_INTERACTION_BATCH` step, persists results incrementally, emits a deterministic SSE reply, and lets the frontend refresh state without executing the actions again.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, Spring transactions, Reactor `Flux`, Jackson, JUnit 5/AssertJ, H2/MySQL 8, Vue 3/TypeScript/Vite.

**Execution rule:** The repositories contain unrelated uncommitted work. Do not reset, discard, commit, or rewrite it. Each production behavior follows RED -> GREEN -> focused regression; checkpoints replace automatic commits.

---

### Task 1: Structured Domain Contract

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/ArticleInteractionAction.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/ArticleInteractionOutcome.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/ArticleInteractionResultCode.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/ArticleInteractionResult.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleInteractionResultTests.java`

- [ ] **Step 1: Write the failing contract test**

  Assert that `APPLIED` and `ALREADY_SATISFIED` construct successful results, failures carry a stable result code, and the six supported action names map to the existing camel-case classifier values.

- [ ] **Step 2: Verify RED**

  Run: `mvn "-Dtest=ArticleInteractionResultTests" test`

  Expected: compilation failure because the four contract types do not exist.

- [ ] **Step 3: Add the minimal immutable contract**

  `ArticleInteractionAction` contains exactly `LIKE_ARTICLE`, `UNLIKE_ARTICLE`, `FAVORITE_ARTICLE`, `UNFAVORITE_ARTICLE`, `FOLLOW_AUTHOR`, and `UNFOLLOW_AUTHOR`, each with its existing classifier value. `ArticleInteractionResult` contains `actionType`, `outcome`, `resultCode`, `summary`, `before`, and `after`, plus explicit success/failure factories.

- [ ] **Step 4: Verify GREEN**

  Run the focused test and keep the public model free of run/step persistence concerns.

### Task 2: Like and Favorite Transactions, Idempotency, and Redis Timing

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/ArticleLikesService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/ArticleFavoritesService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/ArticleLikesServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/ArticleFavoritesServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/controller/ArticleLikesController.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/controller/ArticleFavoritesController.java`
- Modify: `blog-system/src/test/resources/schema.sql`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleInteractionDomainTests.java`

- [ ] **Step 1: Write failing integration tests**

  Cover explicit actor IDs, unauthenticated favorite, already-satisfied results, repeated cancellation, concurrent duplicate requests, count changes exactly once, rollback when the count update fails, and hot-rank updates only after commit. Add H2 unique constraints for `(article_id, user_id)` so the tests match MySQL.

- [ ] **Step 2: Verify RED**

  Run: `mvn "-Dtest=ArticleInteractionDomainTests" test`

  Expected: failures for missing result-returning overloads, missing favorite transaction/login guard, and transaction-time Redis mutation.

- [ ] **Step 3: Implement result-returning domain methods**

  Keep the one-argument methods as HTTP-compatible wrappers using `UserContext`; add explicit actor-ID overloads for Agent use. Use unique keys as the concurrency arbiter, check delete/update affected rows before changing counters, return `ALREADY_SATISFIED` for the target state, return `TARGET_NOT_FOUND` before writes, and schedule hot-rank changes through `AfterCommitExecutor`.

- [ ] **Step 4: Preserve HTTP behavior**

  Controllers continue returning the same success envelope and translate already-satisfied/failed structured outcomes to the existing Chinese `IllegalArgumentException` messages. Existing manual like/favorite callers must not observe an API-format regression.

- [ ] **Step 5: Verify GREEN and regressions**

  Run focused domain tests, `DelayedCacheEvictTests`, and the existing controller/service tests selected by Maven.

### Task 3: Follow Soft-Delete Concurrency

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/FollowService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/FollowServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/controller/FollowController.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleInteractionDomainTests.java`

- [ ] **Step 1: Add failing follow tests**

  Cover self-follow (`FORBIDDEN`), missing target (`TARGET_NOT_FOUND`), active relation (`ALREADY_SATISFIED`), concurrent new follow, concurrent restoration of a soft-deleted relation, and repeated unfollow with counts changing exactly once.

- [ ] **Step 2: Verify RED**

  Run the focused follow methods and confirm the restoration race increments counters more than once under the current unconditional update.

- [ ] **Step 3: Implement conditional state transitions**

  Restore only with `WHERE follower_id=? AND following_id=? AND deleted_at IS NOT NULL`; unfollow only with `deleted_at IS NULL`; change counts only when the affected-row count is one. A losing concurrent request re-reads authority and returns `ALREADY_SATISFIED`.

- [ ] **Step 4: Verify GREEN and existing HTTP messages**

  Run domain tests and MockMvc/controller compatibility coverage.

### Task 4: Multi-Action Planning Boundary

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/AiIntent.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/ArticleActionBatch.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/AgentDecision.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/planner/AgentPlannerSupport.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiIntentClassifierImpl.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AgentPlannerSupportTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiIntentClassifierPromptTests.java`
- Test: `blog-system/src/test/resources/agent/classifier-evaluation-cases.json`
- Test: `blog-system/src/test/resources/agent/planner-evaluation-cases.json`

- [ ] **Step 1: Write failing planner tests**

  Assert ordered multi-action batches, de-duplication, opposite-action conflicts, unknown-action rejection, login/page/article checks, action-plus-client-action CTA, action-plus-analysis CTA, and the explicit-word completeness guard when the classifier omits a requested action.

- [ ] **Step 2: Verify RED**

  Run: `mvn "-Dtest=AgentPlannerSupportTests,AiIntentClassifierPromptTests" test`

- [ ] **Step 3: Add candidate-array classification and backend validation**

  Add `requestedActions` while keeping `actionType` for compatible client-only actions. Pure server actions produce `AgentDecision.articleActionBatch`; pure client actions keep `clientActionType`; mixed execution modes and mixed action/analysis requests produce CTA with zero execution.

- [ ] **Step 4: Verify GREEN and offline evals**

  Run planner tests plus the offline evaluation group. Do not claim LLM stability from a single online response.

### Task 5: Persistent Batch Runner and Deterministic Renderer

**Files:**
- Add enum value in: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/AgentStepActionType.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleInteractionResultRenderer.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleInteractionBatchService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/mapper/AiAgentRunMapper.java` only if a narrow mapper operation is required
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/mapper/AiAgentStepMapper.java` only if a narrow mapper operation is required
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleInteractionBatchServiceTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleInteractionResultRendererTests.java`

- [ ] **Step 1: Write failing renderer and runner tests**

  Assert full success, partial success, full failure, missing/duplicate result rejection, run+step atomic creation, incremental `results[]` persistence, `maxSteps=1`, no stale-confirmation cancellation, and final `COMPLETED` semantics despite business failures.

- [ ] **Step 2: Verify RED**

  Run the two focused test classes and confirm the service/types are absent.

- [ ] **Step 3: Implement the narrow runner**

  Create run and step in one short `TransactionTemplate` transaction. Execute validated actions serially through explicit-actor domain APIs, persist `output_json.results[]` after every item, log partial failures, render without LLM, and close step/run as `SUCCESS`/`COMPLETED` with `current_step=used_steps=1`. Catch infrastructure failure separately and close as `FAILED` without rewriting already persisted item results.

- [ ] **Step 4: Verify GREEN**

  Run focused tests and inspect stored JSON through Jackson round trips rather than string matching.

### Task 6: Startup Recovery Scoped to Batch Steps

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/component/AiRunStartupRecoveryRunner.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiRunStartupRecoveryTests.java`

- [ ] **Step 1: Write failing recovery tests**

  A stale batch run must become `FAILED`, its still-`RUNNING` batch step must become `FAILED`, persisted `results[]` must remain byte-for-byte unchanged, counters become `1/1`, terminal steps are untouched, and `RUNNING` steps of all other action types remain untouched.

- [ ] **Step 2: Verify RED**

  Run only `AiRunStartupRecoveryTests` and confirm the existing runner leaves the batch step running.

- [ ] **Step 3: Implement type-scoped recovery**

  Capture stale batch run IDs before the general run update, then update only `action_type='ARTICLE_INTERACTION_BATCH' AND status='RUNNING'`. Preserve the existing all-`RUNNING` run behavior and all `WAITING_*` states.

- [ ] **Step 4: Verify GREEN**

  Run the focused recovery suite.

### Task 7: SSE Integration and Message Association

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/entity/vo/AiMessageVO.java` only if serialization lacks the existing field
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiMessageIntentRoutingTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleInteractionStreamTests.java`

- [ ] **Step 1: Write failing stream tests**

  Assert the batch path bypasses free-form reply generation, emits PARAM/DATA/STOP, includes `actionResults`, binds the saved assistant message to `agentRunId`, does not emit `articleAction`, and creates no run for CTA/rejected input.

- [ ] **Step 2: Verify RED**

  Run focused routing/stream tests.

- [ ] **Step 3: Add deterministic batch stream**

  Route `AgentDecision.articleActionBatch` before normal chat. Save the user message, execute the batch, stream the renderer text in bounded chunks, save the assistant message with `agentRunId`, and return the structured results in STOP. Preserve route token accounting without charging for a nonexistent answer-model call.

- [ ] **Step 4: Verify GREEN**

  Run focused tests and the existing Agent/Workflow message-routing regressions.

### Task 8: Disable Legacy Server-Side Action Tools

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/tool/AiArticleActionTools.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/AiModelServiceToolExposureTests.java`

- [ ] **Step 1: Write a failing exposure test**

  Assert the six server-executed methods are not exposed as Spring AI tools while `commentArticle`, `scrollToComments`, and `copyArticleLink` remain exposed.

- [ ] **Step 2: Verify RED**

  Run the focused exposure test and see the six legacy tools still registered.

- [ ] **Step 3: Remove only the six `@Tool` annotations/registrations**

  Do not delete the whole tool class or break client-only actions.

- [ ] **Step 4: Verify GREEN**

  Run tool exposure and model service tests.

### Task 9: Frontend Refresh Without Double Execution

**Files:**
- Modify: `blog-frontend/src/api/ai.ts`
- Create: `blog-frontend/src/utils/aiArticleResultBus.ts`
- Modify: `blog-frontend/src/components/ai/AiAssistant.vue`
- Modify: `blog-frontend/src/views/ArticleDetailView.vue`
- Test: use existing frontend test setup if present; otherwise typecheck/build is the repository-supported verification

- [ ] **Step 1: Add the result types and a failing type-level/build expectation**

  Extend STOP with `actionResults` and the exact backend enum strings. The callback signature must force consumers to handle results separately from legacy `articleAction`.

- [ ] **Step 2: Implement refresh-only handling**

  `AiAssistant` emits results on a dedicated result bus. `ArticleDetailView` validates the article ID and reloads article/author authority. It must never call like/favorite/follow write APIs from an `actionResults` event. Legacy client-only actions continue through `articleAction`.

- [ ] **Step 3: Verify**

  Run: `npm run build`

  Expected: Vue typecheck/build succeeds with no STOP signature mismatch.

### Task 10: Schema Baseline and Final Regression

**Files:**
- Modify: `db/init.sql`
- Modify: `blog-system/src/test/resources/schema.sql`
- Modify: `docs/agent/agent-goal-completion-loop-design.md`
- Modify: `AGENTS.md`

- [ ] **Step 1: Add schema assertions before DDL changes**

  Add/extend a schema text test that requires `ai_messages.agent_run_id`, its index, and like/favorite unique constraints in both MySQL and H2 baselines.

- [ ] **Step 2: Verify RED and patch schemas**

  `db/init.sql` gains the nullable message association column and index; H2 gains the matching message index and relation unique constraints. Do not include `ai_workflow_step_logs.log_type`.

- [ ] **Step 3: Run backend verification**

  Run focused suites first, then `mvn test`. If the full suite times out, inspect `target/surefire-reports` and report the exact verified subset rather than calling it green.

- [ ] **Step 4: Run frontend verification**

  Run `npm run build` and any existing frontend tests.

- [ ] **Step 5: Run MySQL 8 smoke when a local disposable instance is available**

  Create a dedicated temporary database, execute `db/init.sql`, query `information_schema.columns` and `information_schema.statistics`, insert/read an assistant message with `agent_run_id`, then drop only that named temporary database. If no disposable MySQL is available, report this check as not run.

- [ ] **Step 6: Update project status**

  Mark the design implemented only after both code paths and focused tests pass. Add a concise milestone note to `AGENTS.md` without rewriting unrelated history.

