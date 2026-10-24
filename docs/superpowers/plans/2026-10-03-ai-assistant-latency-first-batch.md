# AI Assistant Latency First Batch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not dispatch subagents unless the user explicitly requests delegation.

**Goal:** Eliminate request-thread identity leakage, make the slow AI stages measurable, and stop Article Agent from repeatedly generating final answers without new evidence.

**Architecture:** Keep the three changes isolated behind existing boundaries: servlet interceptors own `UserContext`, each LLM/retrieval caller owns its own timing log, and `ArticleAgentState` owns per-run evidence revisions. No API, DDL, model, prompt, or frontend protocol changes are included.

**Tech Stack:** Java 17, Spring Boot 3, Spring MVC async interceptors, Spring AI, MyBatis-Plus, JUnit 5, Mockito, AssertJ, Maven Surefire.

---

## Constraints

- Work in `D:\AIAgent\01myBlog\blog-system`; the parent directory is not a Git repository.
- Preserve the existing dirty RAG worktree and inspect overlapping diffs before editing.
- Do not run unscoped `mvn test`, `-Dgroups=eval`, or real-LLM tests.
- Do not edit `.env`, secrets, CI/CD, or production deployment files.
- Do not commit. Show the change summary first if the user later asks for a commit.

### Task 1: Clear `UserContext` at every request and async handoff

**Files:**

- Create: `blog-system/src/test/java/com/hailin/blogsystem/AuthInterceptorUserContextTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/interceptor/OptionalJwtInterceptor.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/interceptor/JwtInterceptor.java`

- [ ] **Step 1: Write the failing interceptor tests**

Create one test class with `@AfterEach UserContext.clear()` and these cases:

```java
@Test
void optionalRequestWithoutHeaderClearsReusedThread() {
    UserContext.set(99L);
    when(request.getHeader("Authorization")).thenReturn(null);

    assertThat(optional.preHandle(request, response, handler)).isTrue();
    assertThat(UserContext.get()).isNull();
}

@Test
void requiredRequestWithoutHeaderClearsReusedThread() throws Exception {
    UserContext.set(99L);
    when(request.getHeader("Authorization")).thenReturn(null);

    assertThat(required.preHandle(request, response, handler)).isFalse();
    assertThat(UserContext.get()).isNull();
}

@Test
void asyncHandoffClearsOriginalThread() throws Exception {
    when(request.getHeader("Authorization")).thenReturn("Bearer valid");
    when(jwtUtil.parsePayload("valid")).thenReturn(new JwtUtil.Payload(12L, "jti", 1L));
    when(tokenBlacklist.isRevoked("jti")).thenReturn(false);

    assertThat(optional.preHandle(request, response, handler)).isTrue();
    assertThat(UserContext.get()).isEqualTo(12L);
    optional.afterConcurrentHandlingStarted(request, response, handler);
    assertThat(UserContext.get()).isNull();
}
```

Also cover invalid and revoked tokens so stale state cannot survive either branch.

- [ ] **Step 2: Run RED**

Run:

```powershell
./mvnw -Dtest=AuthInterceptorUserContextTests test
```

Expected: missing `afterConcurrentHandlingStarted`, plus no-header assertions observe the seeded `99L`.

- [ ] **Step 3: Implement the minimal lifecycle fix**

For both interceptors:

```java
import org.springframework.web.servlet.AsyncHandlerInterceptor;

public class ... implements AsyncHandlerInterceptor {
    @Override
    public boolean preHandle(...) {
        UserContext.clear();
        // existing authentication logic
    }

    @Override
    public void afterConcurrentHandlingStarted(...) {
        UserContext.clear();
    }
}
```

Keep `afterCompletion()` unchanged. Do not introduce an inheritable thread-local.

- [ ] **Step 4: Run GREEN**

Run the same targeted test and require zero failures/errors.

### Task 2: Add stage timing without logging content

**Files:**

- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/AiStageTimer.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ai/AiStageTimerTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiIntentClassifierImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiPromptServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/GteRerankV2ArticleRagRanker.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/LlmAgentStepDecider.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleEvidenceVerifier.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AbstractAgentRuntime.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiIntentClassifierUsageTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAgentRuntimeVerifierTests.java`

- [ ] **Step 1: Write RED tests for a monotonic timer**

Use an injected `LongSupplier` so duration tests do not sleep:

```java
@Test
void elapsedMillisUsesMonotonicNanosAndNeverReturnsNegative() {
    AtomicLong nanos = new AtomicLong(1_000_000L);
    AiStageTimer timer = AiStageTimer.start(nanos::get);
    nanos.set(4_500_000L);
    assertThat(timer.elapsedMillis()).isEqualTo(3L);
    nanos.set(500_000L);
    assertThat(timer.elapsedMillis()).isZero();
}
```

- [ ] **Step 2: Run timer RED**

Run `./mvnw -Dtest=AiStageTimerTests test` and expect compilation failure because the class does not exist.

- [ ] **Step 3: Implement `AiStageTimer`**

```java
public final class AiStageTimer {
    private final LongSupplier nanoTime;
    private final long startedAt;

    private AiStageTimer(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
        this.startedAt = nanoTime.getAsLong();
    }

    public static AiStageTimer start() {
        return new AiStageTimer(System::nanoTime);
    }

    static AiStageTimer start(LongSupplier nanoTime) {
        return new AiStageTimer(nanoTime);
    }

    public long elapsedMillis() {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - startedAt));
    }
}
```

- [ ] **Step 4: Run timer GREEN**

Run `./mvnw -Dtest=AiStageTimerTests test` and require zero failures/errors.

- [ ] **Step 5: Instrument classifier calls**

Wrap the production `classify(...)` entry with one total timer and keep an integer call count in that invocation. Increment for first pass, repair, and plan locator. In a `finally` block log only:

```java
log.info("[PERF-AI] stage=classifier durationMs={} outcome={} calls={}",
        timer.elapsedMillis(), outcome, callCount);
```

Do not log the user message, prompt, or parsed reason. Preserve `ClassifierProbe` behavior.

- [ ] **Step 6: Instrument memory recall**

Inside each `supplyAsync`, time the real retrieval and return a small private result containing `text`, `durationMs`, and `outcome`. Distinguish `hit`, `empty`, `timeout`, and `error`; log total join time after both futures settle. Preserve the current 3-second fail-open behavior in this batch.

- [ ] **Step 7: Instrument rerank and generation**

Add `[PERF-AI] stage=rerank` around the existing HTTP request and fallback. In `AiMessageServiceImpl`, measure from `aiModelService.streamChat(...)` subscription to first non-null chunk and to `doFinally`; log `ttftMs`, `durationMs`, and Reactor signal only. Work with the current dirty RAG diff and do not remove candidate-selection changes.

- [ ] **Step 8: Instrument Decider and Verifier**

Add one timer around each complete decide/repair sequence and verifier/repair sequence. Log `runId` and `stepNo` from the Runtime boundary where available; do not log goal, context, draft, or missing-evidence reason.

- [ ] **Step 9: Correct Agent step duration semantics**

Capture `stepStartedAtNanos` immediately before `decider.decide(...)`. Pass elapsed milliseconds to rejected, skipped, terminal, and action-recording methods so `ai_agent_steps.duration_ms` represents total step time. Action executor timing remains a separate log field.

- [ ] **Step 10: Run focused observability tests**

Run:

```powershell
./mvnw -Dtest=AiStageTimerTests,AiIntentClassifierUsageTests,ArticleAgentRuntimeVerifierTests test
```

Expected: zero failures/errors. No real LLM calls occur because ChatClient/Decider/Verifier are mocked.

### Task 3: Stop repeated final answers without evidence progress

**Files:**

- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentRunState.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAgentState.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AbstractAgentRuntime.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ai/agent/ArticleAgentStateTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAgentRuntimeVerifierTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAgentRuntimeDecisionContextTests.java`

- [ ] **Step 1: Write RED state tests**

Define a run-state API based on `Verdict.MissingEvidence`:

```java
long evidenceRevision(Verdict.MissingEvidence type);
void noteVerifierRejection(Verdict.MissingEvidence type);
boolean hasEvidenceProgressSinceRejection();
```

Test that article evidence only increments `ARTICLE_CONTENT`, memory hit or first authoritative empty increments `MEMORY`, RAG hit or first authoritative empty increments `RAG`, repeated identical results do not increment, and failed/partial-failure retrieval does not increment.

- [ ] **Step 2: Run state RED**

Run `./mvnw -Dtest=ArticleAgentStateTests test` and expect compilation failure for the missing revision API.

- [ ] **Step 3: Implement per-type revisions**

Use an `EnumMap<Verdict.MissingEvidence, Long>` plus per-query keys already present in the state. Increment only on new slot values or first authoritative empty result. Keep failure payloads out of revisions. `OTHER` compares the sum/max snapshot of all three revisions.

- [ ] **Step 4: Run state GREEN**

Run the same state test class and require zero failures/errors.

- [ ] **Step 5: Replace the max-step empty-loop expectation with RED convergence behavior**

Change `unresolvedRejectionAtMaxStepsShutsDownConservatively()` so it expects:

```java
assertThat(result.usedSteps()).isEqualTo(2);
verify(verifier, times(1)).verify(any(), any(), any(), any(), any());
List<AiAgentStep> steps = captureSteps();
assertThat(steps).extracting(AiAgentStep::getStatus)
        .containsExactly("FAILED", "SKIPPED");
```

Add a second test proving `NEED_MORE -> matching query progress -> FINAL_ANSWER` still invokes Verifier twice and completes normally.

- [ ] **Step 6: Run Runtime RED**

Run `./mvnw -Dtest=ArticleAgentRuntimeVerifierTests test`. Expected: the old loop uses all six steps and invokes Verifier repeatedly.

- [ ] **Step 7: Add the deterministic convergence guard**

When Verifier returns `NEED_MORE`, call `runState.noteVerifierRejection(...)`. Before invoking Verifier for a later `FINAL_ANSWER`, if `hasEvidenceProgressSinceRejection()` is false:

```java
recordSkippedStep(run, decision, nextStepNo,
        "上次拒绝后没有新增证据", stepUsage, elapsedStepMs);
return completeWithConservativeShutdown(run, observations);
```

The guard applies only when a structured run state has an unresolved rejection. Learning, General, and Wallet runtimes retain current behavior.

- [ ] **Step 8: Run Runtime GREEN and related regressions**

Run:

```powershell
./mvnw -Dtest=ArticleAgentRuntimeVerifierTests,ArticleAgentRuntimeDecisionContextTests,ArticleAgentStateTests test
```

Expected: zero failures/errors and no real LLM calls.

### Task 4: Final focused verification and handoff

**Files:**

- Modify: `docs/agent/ai-assistant-latency-first-batch-design.md` only to mark exactly what was implemented and record actual test evidence.

- [ ] **Step 1: Run all affected offline tests together**

```powershell
./mvnw -Dtest=AuthInterceptorUserContextTests,AiStageTimerTests,AiIntentClassifierUsageTests,ArticleAgentRuntimeVerifierTests,ArticleAgentRuntimeDecisionContextTests,ArticleAgentStateTests test
```

- [ ] **Step 2: Check the diff**

```powershell
git diff --check
git status --short
git diff --stat
```

Inspect overlapping RAG files to confirm existing user edits remain intact. Search new log statements for `message`, `prompt`, `answer`, `context`, and JWT values; none may be emitted.

- [ ] **Step 3: Update documentation with exact evidence**

Record test class count, Maven exit status, unrun gates, remaining inability to prevent the first repeated Decider generation, and the next planned Decider/Generator split.

- [ ] **Step 4: Report without committing**

Summarize changed files, behavioral impact, tests run, tests intentionally not run, and any dirty files that predated this work.
