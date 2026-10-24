# Task Orchestration Recovery Correctness Implementation Plan

> **For agentic workers:** Execute inline in this session. Keep every production change behind a failing focused test; do not run the full Maven or online LLM gates without explicit approval.

**Goal:** Close the confirmed WORKFLOW_HANDOFF planning, recovery-state, reconciliation, schema, and token-accounting gaps without replacing the existing TaskPlan/Executor/Continuation architecture.

**Architecture:** Keep `TaskPlanningFacade -> TaskPlanner -> TaskOrchestrator` as the execution seam. Add deterministic plan-shape validation before execution, use conditional database transitions for Task run states, and make continuation finalization a short transaction after remaining steps have executed. Derive developer Token views from the existing run tree and linked Workflow rows.

**Tech Stack:** Java 17, Spring Boot 3, MyBatis-Plus, JUnit 5, H2/MySQL, Vue 3/TypeScript.

---

### Task 1: Reject unsupported post-handoff plans

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/plan/TaskPlannerTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/plan/TaskPlanningFacadeTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/plan/TaskPlanner.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/plan/TaskPlanningFacade.java`

- [x] Add failing tests proving only `ARTICLE_SEARCH` may follow the first `WORKFLOW_HANDOFF` by plan position.
- [x] Add a failing test proving legacy Workflow fields plus a non-Workflow executable plan fail closed.
- [x] Run the two focused test classes and confirm the new assertions fail for the expected route.
- [x] Implement the minimum deterministic checks and rerun the tests to green.

### Task 2: Make Task run state transitions truthful

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/TaskOrchestrationServiceTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/TaskWorkflowContinuationServiceTests.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/TaskRunStateTransitions.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/TaskOrchestrationService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/TaskWorkflowContinuationService.java`

- [x] Add failing tests for infrastructure exception -> `FAILED`, and reset-to-waiting only from `RUNNING`.
- [x] Implement conditional transitions for complete, pause, fail, claim-resume, and reset-waiting.
- [x] Keep user-facing error text fixed and store the technical reason only in `error_message`/logs.

### Task 3: Fix reconciliation selection and stale-run ownership

**Files:**
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/TaskHandoffReconciliationTaskTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/TaskHandoffReconciliationTask.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/mapper/AiWorkflowRunMapper.java`

- [x] Add failing tests proving a fresh active `RUNNING` Task is untouched and a terminal Workflow behind twenty pending cards is still selected.
- [x] Query terminal linked Workflows whose parent run is still `WAITING_WORKFLOW`; do not window parent runs first.
- [x] Require `updated_at` older than the configured stale threshold before reclaiming `RUNNING` Task runs.

### Task 4: Make continuation finalization recoverable

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/TaskWorkflowContinuationServiceTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/TaskWorkflowContinuationService.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/TaskOrchestrationService.java`

- [x] Add a failing test for message-update failure after Workflow Token accounting.
- [x] Separate “execute remaining steps” from “commit final root status”.
- [x] Commit root status, handoff row, rendered message, and absolute Token total in one short transaction.

### Task 5: Repair schema and Token observability

**Files:**
- Modify: `db/init.sql`
- Verify: `blog-system/src/test/resources/schema.sql`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/entity/vo/AgentRunDevDetailVO.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentRunInspectionService.java`
- Modify: `blog-frontend/src/api/ai.ts`
- Modify: `blog-frontend/src/views/DevAgentRunDetailView.vue`

- [x] Add `log_type` to the clean MySQL initialization table definition.
- [x] Persist `routeUsage` for Task CTA/preflight responses.
- [x] Expose `localTokens` and `subtreeTokens` as derived developer-only fields.
- [x] Add focused backend/frontend assertions for the new accounting fields.

### Task 6: Synchronize design and verification evidence

**Files:**
- Modify: `docs/agent/agent-task-orchestration-design.md`
- Modify: `docs/agent/agent-task-orchestration-workflow-handoff-design.md`
- Modify: `docs/agent/agent-token-optimization-design.md`
- Modify: `docs/agent/agent-eval-gate.md`
- Modify together: `AGENTS.md`, `CLAUDE.md`

- [x] Record the implemented post-handoff boundary, recovery lifecycle, Token formula, and current classifier case count.
- [x] Distinguish historical 49/49 and 15/15 model snapshots from the current 67-case dataset.
- [x] Keep `AGENTS.md` and `CLAUDE.md` byte-identical.
- [x] Run only affected Maven test classes, focused frontend tests, and `npm run build`; report that the full online/full Maven gates were not run.

## Verification Evidence (2026-10-02)

- Backend affected regression: 168 tests, 0 failures, 0 errors, 1 conditional skip.
- P3 activation contract/offline checks: 12 tests, 0 failures; current Prompt reduction 23.1%.
- P3 real-LLM activation probes: 3 focused cases passed with `ON_DEMAND` active.
- Frontend focused test: 5/5 passed.
- Frontend production build: passed; only the existing large-chunk warning remains.
- Not run: full `mvn test`, `-Dgroups=eval`, or the complete 67-case online classifier gate.
