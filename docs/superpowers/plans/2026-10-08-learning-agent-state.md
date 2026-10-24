# Learning Agent Structured State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the Learning Agent from replaying full observation text on every decision by introducing a per-run structured state with bounded, deduplicated context.

**Architecture:** Keep `AgentActionResult.summary` as the existing audit/UI text and add typed facts in its payload. `LearningAgentState` consumes only those facts, renders fixed-budget slots, and is created by `LearningAgentRuntime` per run. The base runtime, persistence schema, billing boundary, and other Agent domains remain unchanged.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, JUnit 5, AssertJ, Mockito, Maven Wrapper.

---

### Task 1: Lock the Learning State Contract With Tests

**Files:**
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ai/agent/LearningAgentStateTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentRunState.java:class Javadoc`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentActionResult.java:of(String) Javadoc`

- [x] **Step 1: Write failing state tests**

  Add tests for these exact contracts:

  ```java
  @Test
  void firstDecisionHasNoExtraContext() {
      LearningAgentState state = new LearningAgentState("学习 Redis", 5);

      assertThat(state.decisionContext()).isEmpty();
  }

  @Test
  void dashboardResultIsDeduplicatedAndLatestFactWins() {
      LearningAgentState state = new LearningAgentState("查看计划", 5);
      AgentStepDecision decision = decision(AgentStepActionType.QUERY_LEARNING_DASHBOARD,
              Map.of("planRef", "Redis"));

      state.reduce(decision, AgentActionResult.of("旧计划结果")
              .with("status", "SUCCESS")
              .with("planRef", "Redis")
              .with("planCount", 1)
              .with("activePlanCount", 1)
              .with("matchedCount", 1)
              .with("selectedPlanId", 11L));
      state.reduce(decision, AgentActionResult.of("新计划结果")
              .with("status", "SUCCESS")
              .with("planRef", "Redis")
              .with("planCount", 1)
              .with("activePlanCount", 1)
              .with("matchedCount", 1)
              .with("selectedPlanId", 11L));

      assertThat(state.decisionContext()).contains("新计划结果").doesNotContain("旧计划结果");
  }

  @Test
  void failedMemoryDoesNotBecomeEvidence() {
      LearningAgentState state = new LearningAgentState("查我的偏好", 5);
      AgentStepDecision decision = decision(AgentStepActionType.QUERY_MEMORY,
              Map.of("question", "回答风格"));

      state.reduce(decision, AgentActionResult.of("记忆检索失败")
              .with("status", "FAILED")
              .with("query", "回答风格")
              .with("hitCount", 0));

      assertThat(state.verificationEvidence()).doesNotContain("记忆检索失败");
      assertThat(state.decisionContext()).contains("失败");
  }

  @Test
  void sameEmptyQueryIsNotRepeatedAsNewEvidence() {
      LearningAgentState state = new LearningAgentState("查文章知识", 5);
      AgentStepDecision decision = decision(AgentStepActionType.SEARCH_RAG,
              Map.of("keyword", "量子计算"));
      AgentActionResult empty = AgentActionResult.of("没有命中")
              .with("status", "SUCCESS")
              .with("keyword", "量子计算")
              .with("hitCount", 0);

      state.reduce(decision, empty);
      long firstRevision = state.evidenceRevision(Verdict.MissingEvidence.RAG);
      state.reduce(decision, empty);

      assertThat(state.evidenceRevision(Verdict.MissingEvidence.RAG)).isEqualTo(firstRevision);
      assertThat(state.lastStepHadNewEvidence()).isFalse();
  }
  ```

  Include a small private `decision(...)` helper that constructs `AgentStepDecision` with a null thought summary and the supplied input map.

- [x] **Step 2: Run the new tests and verify they fail**

  Run from `D:\AIAgent\01myBlog\blog-system`:

  ```powershell
  .\mvnw.cmd -Dtest="LearningAgentStateTests" test
  ```

  Expected: compilation failure because `LearningAgentState` does not yet exist.

- [x] **Step 3: Update only the shared contract documentation**

  Change the `AgentRunState` and `AgentActionResult` Javadocs so they state that Article is the first structured domain and Learning is the domain being added; keep General and Wallet on the default observation path until a later slice. Do not change method signatures or behavior in this step.

### Task 2: Implement the Per-Run Learning State

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/LearningAgentState.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ai/agent/LearningAgentStateTests.java`

- [x] **Step 1: Implement the minimal state reducer**

  Implement `AgentRunState` with these private fields and budgets:

  ```java
  private static final int PLAN_BUDGET = 1800;
  private static final int MEMORY_BUDGET = 600;
  private static final int RAG_BUDGET = 800;
  private static final int ACTION_BUDGET = 300;
  private static final int MISSING_BUDGET = 300;
  private static final int FEEDBACK_BUDGET = 400;

  private final Map<String, String> planFacts = new LinkedHashMap<>();
  private final Map<String, String> memoryFacts = new LinkedHashMap<>();
  private final Map<String, String> ragFacts = new LinkedHashMap<>();
  private final Set<String> emptyMemoryQueries = new HashSet<>();
  private final Set<String> emptyRagQueries = new HashSet<>();
  private final Set<String> emptyPlanQueries = new HashSet<>();
  private final List<String> completedActions = new ArrayList<>();
  private final List<String> missingEvidence = new ArrayList<>();
  private final List<Feedback> feedbacks = new ArrayList<>();
  private final EnumMap<Verdict.MissingEvidence, Long> evidenceRevisions =
          new EnumMap<>(Verdict.MissingEvidence.class);
  ```

  `reduce` must never parse Chinese summaries. It should inspect payload status and counts, render compact facts from payload fields only, and use action/query keys to replace duplicates. Mirror Article state’s feedback clearing and revision semantics, but use learning labels such as `学习计划`, `学习偏好`, and `站内知识`.

- [x] **Step 2: Render bounded contexts**

  Implement `decisionContext()`, `verificationEvidence()`, and `summaryContext()` with the same three distinctions as `ArticleAgentState`: operational fields for the decider, factual fields for verification, and the full bounded state for summary. Use newest-first slot selection and truncate a single oversized entry to its slot budget. Return `""` before any fact or feedback exists.

- [x] **Step 3: Run state tests and verify they pass**

  ```powershell
  .\mvnw.cmd -Dtest="LearningAgentStateTests" test
  ```

  Expected: all state tests pass.

### Task 3: Return Structured Facts From Learning Actions

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/LearningAgentActionExecutorImpl.java:execute and three read-action methods`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/LearningAgentActionExecutorTests.java`

- [x] **Step 1: Add executor contract tests**

  Mock `LearningPlansService`, `AiMemoryRetrieveService`, `AiEpisodicMemoryRetrieveService`, `ArticleRagSearchService`, and `BlogAiProperties`. Assert that:

  - a uniquely matched plan returns `summary` unchanged in meaning and payload `status=SUCCESS`, `matchedCount=1`, and the selected plan ID;
  - a named plan with no match returns `matchedCount=0` and does not claim a selected plan;
  - memory returns `hitCount` plus `status=SUCCESS`, `PARTIAL_FAILURE`, or `FAILED` according to the existing two-source behavior;
  - RAG returns keyword, hit count, and status without changing the existing display text.

- [x] **Step 2: Run the new executor tests and verify the expected failure**

  ```powershell
  .\mvnw.cmd -Dtest="LearningAgentActionExecutorTests" test
  ```

  Expected: tests fail because the current executor wraps every result with `AgentActionResult.of(String)` and emits no payload.

- [x] **Step 3: Refactor the executor without changing display text**

  Change the private action methods to return `AgentActionResult`. Keep their existing string-building branches, but attach only backend facts:

  ```java
  return AgentActionResult.of(sb.toString(), Map.of(
          "status", "SUCCESS",
          "planRef", planRef == null ? "" : planRef,
          "planCount", plans.size(),
          "activePlanCount", activePlans.size(),
          "matchedCount", matched.size(),
          "selectedPlanId", plan.getId()
  ));
  ```

  For branches where `selectedPlanId` is absent, use a mutable `LinkedHashMap` so the value can be omitted instead of inserting a null into `Map.of`. Keep all existing ownership, plan matching, RAG filtering, exception handling, and user-facing text intact.

- [x] **Step 4: Run executor tests and verify they pass**

  ```powershell
  .\mvnw.cmd -Dtest="LearningAgentActionExecutorTests" test
  ```

  Expected: all executor tests pass.

### Task 4: Enable the State in Learning Runtime and Prove the Boundary

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/LearningAgentRuntime.java:newRunState override`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/LearningAgentRuntimeTests.java:focused second-decision test`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentRunState.java:contract Javadocs if needed`

- [x] **Step 1: Add a runtime regression test**

  Configure the mocked executor to return a dashboard result with a payload, configure the decider to return `QUERY_LEARNING_DASHBOARD` followed by `FINAL_ANSWER`, run the runtime, and capture the second decider context. Assert it contains the dashboard summary once, is bounded by the state budget, and does not contain a duplicated first observation block.

- [x] **Step 2: Run the focused runtime test and verify it fails**

  ```powershell
  .\mvnw.cmd -Dtest="LearningAgentRuntimeTests" test
  ```

  Expected: the second context still uses `clipContext(observations)` because Learning runtime has no `newRunState` override.

- [x] **Step 3: Enable the state**

  Add:

  ```java
  @Override
  protected AgentRunState newRunState(String goal, int maxSteps) {
      return new LearningAgentState(goal, maxSteps);
  }
  ```

  Do not alter `effectiveGoal`, `allowedActions`, max steps, write proposal handling, or token accumulation.

- [x] **Step 4: Run the focused Agent regression batch**

  ```powershell
  .\mvnw.cmd -Dtest="LearningAgentStateTests,LearningAgentActionExecutorTests,LearningAgentRuntimeTests,ArticleAgentStateTests,ArticleAgentRuntimeTests" test
  ```

  Expected: all tests pass with zero failures and zero errors.

### Task 5: Review, Document the Milestone, and Verify the Patch

**Files:**
- Modify: `D:\AIAgent\01myBlog\AGENTS.md:Token 工程 progress line`
- Modify: `D:\AIAgent\01myBlog\CLAUDE.md:same matching progress line`
- Test: all focused classes from Task 4

- [x] **Step 1: Update both project instruction files byte-identically**

  Record that P4.1 now covers Learning Agent structured state and that General/Wallet remain on observation replay. Do not change unrelated project history. Compare them with:

  ```powershell
  fc.exe /b D:\AIAgent\01myBlog\AGENTS.md D:\AIAgent\01myBlog\CLAUDE.md
  ```

  Expected: no differences.

- [x] **Step 2: Run the final focused verification**

  ```powershell
  .\mvnw.cmd -Dtest="LearningAgentStateTests,LearningAgentActionExecutorTests,LearningAgentRuntimeTests,ArticleAgentStateTests,ArticleAgentRuntimeTests" test
  git diff --check
  ```

  Expected: focused tests pass; `git diff --check` reports no whitespace errors. Do not run `mvn test`, `-Dgroups=eval`, or live LLM evaluation without explicit approval.

- [x] **Step 3: Review the final diff**

  Confirm no changes were made to wallet billing, memory/summary token policy, database DDL, frontend contracts, or unrelated domains. Leave all changes uncommitted as required by the project instructions.
