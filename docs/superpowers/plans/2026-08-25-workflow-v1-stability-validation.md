# Workflow V1 Stability Validation Plan

> **For agentic workers:** This is a validation-only plan. Do not add new Workflow features until the listed scenarios are executed and reviewed.

**Goal:** Verify that Workflow V1 handles normal execution, concurrent actions, network interruption, retry, cancellation, permission isolation, and frontend recovery without duplicate business execution.

**Architecture:** Keep the current four-layer protection: Redis action lock prevents concurrent Handler execution, database `version` claim prevents stale state writes, `Idempotency-Key` replays completed action results, and persisted Workflow state allows refresh recovery. Validation must distinguish a transport failure from a real LLM/business failure.

**Tech Stack:** Spring Boot, MyBatis-Plus, H2 test database, Redis, Maven Surefire, Vue 3, Vite, Vitest.

---

### Task 1: Run focused backend regression

**Files:**
- Test: `D:\AIAgent\01myBlog\blog-system\src\test\java\com\hailin\blogsystem\WorkflowActionLockTests.java`
- Test: `D:\AIAgent\01myBlog\blog-system\src\test\java\com\hailin\blogsystem\AiWorkflowRunVersionTests.java`
- Test: `D:\AIAgent\01myBlog\blog-system\src\test\java\com\hailin\blogsystem\AiWorkflowRunServiceTests.java`

> **Execution log (2026-08-25):** Task 1 completed — lock/version/idempotency/service tests all passed (`WorkflowActionLockTests` 2/2, `AiWorkflowRunVersionTests` 2/2, `AiWorkflowRunServiceTests` 19/19 incl. approve/cancel version claim + idempotency cache hit). Version invariant verified in tests: `approveClaimsWorkflowVersion`, `cancelClaimsWorkflowVersionAndClearsSessionBinding`, `approveWithSameIdempotencyKeyReturnsCachedResult`. Task 5 full regression and Task 2/3/4 manual UI scenarios were not executed in this round; V1 closure decision (Task 6) made by owner after manual smoke testing.

- [x] **Step 1: Run lock and version tests**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
mvn "-Dtest=WorkflowActionLockTests,AiWorkflowRunVersionTests" test
```

Expected: all tests pass.

- [x] **Step 2: Run Workflow service tests**

Run:

```powershell
mvn "-Dtest=AiWorkflowRunServiceTests" test
```

Expected: all tests pass, including approve/reject/retry/cancel and idempotency cases.

- [x] **Step 3: Record the version invariant**

For each action test, verify the persisted run version increases exactly once per real action. A cached retry with the same `Idempotency-Key` must not increase it again.

---

### Task 2: Verify concurrent action protection

**Files:**
- Runtime: `D:\AIAgent\01myBlog\blog-system\src\main\java\com\hailin\blogsystem\service\impl\AiWorkflowRunServiceImpl.java`
- Runtime: `D:\AIAgent\01myBlog\blog-system\src\main\java\com\hailin\blogsystem\ai\workflow\WorkflowActionLock.java`
- Runtime: `D:\AIAgent\01myBlog\blog-system\src\main\java\com\hailin\blogsystem\mapper\AiWorkflowRunMapper.java`

- [ ] **Step 1: Prepare one waiting Workflow**

Create or open a Workflow in `WAITING_OUTLINE_CONFIRM` or another confirmation state.

- [ ] **Step 2: Send two different-key approve requests at nearly the same time**

Use two browser tabs or duplicate the request from DevTools. Give the requests different `Idempotency-Key` values.

Expected:

```text
one request executes;
the other receives Workflow busy/conflict or stale-action rejection;
the LLM is called only once;
the run version increases only once;
the step log does not contain two successful approve operation logs.
```

- [ ] **Step 3: Repeat with reject and retry**

Use a waiting Workflow for reject and a `FAILED` Workflow for retry.

Expected: the same one-winner behavior applies.

- [ ] **Step 4: Repeat approve versus cancel**

Send approve and cancel concurrently.

Expected: one action wins the lock; the final state is consistent and there is no later stale overwrite.

---

### Task 3: Verify idempotent network recovery

**Files:**
- Frontend API: `D:\AIAgent\01myBlog\blog-frontend\src\api\ai.ts`
- Frontend workflow UI: `D:\AIAgent\01myBlog\blog-frontend\src\components\ai\AiAssistant.vue`
- Backend idempotency: `D:\AIAgent\01myBlog\blog-system\src\main\java\com\hailin\blogsystem\ai\workflow\WorkflowActionIdempotency.java`

- [ ] **Step 1: Interrupt the SSE connection after the request starts**

Open DevTools Network throttling or disable the network after clicking approve.

- [ ] **Step 2: Restore the network and click the same action again**

Expected:

```text
the same Idempotency-Key is sent;
the first completed result is returned;
the Handler/LLM is not called a second time;
the workflow version and retry count do not increase a second time.
```

- [ ] **Step 3: Refresh while draft generation is running**

Expected:

```text
if generation succeeded on the server, refresh shows the persisted draft;
no manual retry is needed;
the draft is not generated again.
```

- [ ] **Step 4: Test a real failed generation**

Use a temporary controlled failure or an existing failure path to make the run `FAILED`.

Expected:

```text
the UI shows retry;
clicking retry creates a new action key;
only the failed step runs again;
the Workflow does not restart from the beginning.
```

- [ ] **Step 5: Test retry response loss**

Interrupt the network after retry has reached the backend, then click retry again.

Expected: the same retry key replays the first retry result instead of calling the LLM twice.

---

### Task 4: Verify state and permission boundaries

**Files:**
- Controller: `D:\AIAgent\01myBlog\blog-system\src\main\java\com\hailin\blogsystem\controller\AiWorkflowController.java`
- Manager: `D:\AIAgent\01myBlog\blog-system\src\main\java\com\hailin\blogsystem\ai\workflow\WorkflowRunManager.java`

- [ ] **Step 1: Try to access another user's run**

Use a different logged-in user with the first user's `workflowRunId`.

Expected: the request is rejected as nonexistent or unauthorized; no lock is created for the unauthorized action.

- [ ] **Step 2: Try to create a second Workflow in the same active session**

Expected: the request is rejected by active Workflow conflict checking.

- [ ] **Step 3: Repeat actions after terminal states**

Test `COMPLETED`, `CANCELLED`, and `FAILED` runs.

Expected:

```text
COMPLETED/CANCELLED runs do not execute approve/reject again;
FAILED runs allow retry;
retry success clears the old error message.
```

---

### Task 5: Run frontend build and backend full regression

**Files:**
- Frontend project: `D:\AIAgent\01myBlog\blog-frontend`
- Backend project: `D:\AIAgent\01myBlog\blog-system`

- [ ] **Step 1: Run frontend unit tests**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-frontend
npm test
```

Expected: all Vitest tests pass.

- [ ] **Step 2: Build the frontend**

Run:

```powershell
npm run build
```

Expected: `vue-tsc` and Vite build both pass.

- [ ] **Step 3: Run backend full test suite**

Run:

```powershell
cd D:\AIAgent\01myBlog\blog-system
mvn test
```

Expected: no regression in unrelated modules. If real LLM or ES integration tests are intentionally enabled, record them separately from deterministic tests.

---

### Task 6: Decide whether V1 is ready to close

- [x] Confirm all four action paths are covered: `approve`, `reject`, `retry`, `cancel`.
- [x] Confirm duplicate execution is prevented for both concurrent requests and network retries.
- [x] Confirm refresh recovery works for running, successful, failed, and terminal runs.
- [x] Confirm user isolation and active-session conflict behavior.
- [ ] Confirm backend tests, frontend tests, and frontend build pass. (backend focused tests passed; full suite + frontend vitest not run this round)
- [x] Record remaining risks:

```text
Redis lock TTL is currently 120 seconds;
longer-than-TTL LLM execution needs renewal or a longer TTL;
Idempotency result retention is currently 24 hours;
cancel has no result replay because its response is empty;
```

Only after this checklist is complete should we decide whether to tune TTL/renewal or move to other V1 engineering items such as rate limiting, trace enrichment, and cost reporting.
