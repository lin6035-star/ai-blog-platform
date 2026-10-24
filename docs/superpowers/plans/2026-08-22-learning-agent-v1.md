# Learning Agent V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single Learning Agent decision layer that chooses between CHAT / TOOL / WORKFLOW / CTA, keeps active Workflow priority, and exposes only one high-level learning dashboard tool to the model.

**Architecture:** Keep the existing Workflow runtime intact. Move learning-related routing out of `AiMessageServiceImpl` into `AgentPlannerSupport`, keep `AiIntentClassifier` as the suggestion layer, and let the backend enforce the final decision with deterministic rules and per-session auto-start limits. The model should only see one learning tool: `getLearningDashboard(question)`, while lower-level plan-detail tools stay available to backend code and tests.

**Tech Stack:** Spring Boot 3, Spring AI, MyBatis-Plus, JUnit 5, AssertJ, existing blog-system config and DTO patterns.

---

### Task 1: Add decision DTOs and planner config

**Files:**
- Create: `src/main/java/com/hailin/blogsystem/entity/dto/AgentDecision.java`
- Create: `src/main/java/com/hailin/blogsystem/entity/dto/AgentAction.java`
- Create: `src/main/java/com/hailin/blogsystem/entity/dto/AgentRisk.java`
- Modify: `src/main/java/com/hailin/blogsystem/config/BlogAiProperties.java`
- Test: `src/test/java/com/hailin/blogsystem/AgentPlannerSupportTests.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void autoStartIsBlockedWhenSessionLimitIsReached() {
    // arrange a planner decision with WORKFLOW intent and a session that already auto-started twice
    // expect CTA instead of WORKFLOW
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=AgentPlannerSupportTests test`
Expected: FAIL because `AgentPlannerSupport` and `AgentDecision` do not exist yet.

- [ ] **Step 3: Write minimal implementation**

```java
public enum AgentAction { CHAT, TOOL, WORKFLOW, CTA }
public enum AgentRisk { LOW, MEDIUM, HIGH }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=AgentPlannerSupportTests test`
Expected: PASS.

### Task 2: Add the learning dashboard tool

**Files:**
- Create: `src/main/java/com/hailin/blogsystem/ai/tool/AiLearningDashboardTool.java`
- Create: `src/main/java/com/hailin/blogsystem/entity/dto/LearningDashboardVO.java`
- Modify: `src/main/java/com/hailin/blogsystem/service/impl/AiModelServiceImpl.java`
- Modify: `src/main/java/com/hailin/blogsystem/entity/dto/AiPrompt.java`
- Test: `src/test/java/com/hailin/blogsystem/AiLearningDashboardToolTests.java`
- Test: `src/test/java/com/hailin/blogsystem/AiModelServiceImplLearningToolSelectionTests.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void dashboardToolReturnsCompactDecisionPacketWhenQuestionIsBlank() {
    // expect summary + planSummaries + emptyState + cappedHints fallback
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=AiLearningDashboardToolTests test`
Expected: FAIL because the tool and VO are missing.

- [ ] **Step 3: Write minimal implementation**

```java
public String call(String toolInput, ToolContext toolContext) {
    // parse optional question
    // build a compact JSON/string response from learningPlansService + memory + current stage
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=AiLearningDashboardToolTests test`
Expected: PASS.

### Task 3: Extract learning routing into AgentPlannerSupport

**Files:**
- Create: `src/main/java/com/hailin/blogsystem/ai/planner/AgentPlannerSupport.java`
- Modify: `src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Modify: `src/main/java/com/hailin/blogsystem/service/impl/AiIntentClassifierImpl.java`
- Test: `src/test/java/com/hailin/blogsystem/AiMessageIntentRoutingTests.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void activeWorkflowBypassesPlannerAndStillHandlesFollowUpMessages() {
    // seed a session with active_workflow_run_id
    // expect route to workflow branch, not planner decision
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=AiMessageIntentRoutingTests test`
Expected: FAIL until the planner extraction keeps the same priority order.

- [ ] **Step 3: Write minimal implementation**

```java
public AgentDecision decide(String message, PageContextDTO pageContext, AiIntent intent, Long userId, AiSessions session) {
    // 1. active workflow guard lives in caller
    // 2. query -> TOOL(getLearningDashboard)
    // 3. learning plan / progress / assist -> WORKFLOW when double-sign rules pass
    // 4. otherwise CTA or CHAT
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=AiMessageIntentRoutingTests test`
Expected: PASS.

### Task 4: Keep tool exposure narrow and update prompt/tool wiring

**Files:**
- Modify: `src/main/java/com/hailin/blogsystem/service/impl/AiModelServiceImpl.java`
- Modify: `src/main/java/com/hailin/blogsystem/entity/dto/AiPrompt.java`
- Modify: `src/main/java/com/hailin/blogsystem/config/BlogAiProperties.java`
- Test: `src/test/java/com/hailin/blogsystem/AiModelServiceImplLearningToolSelectionTests.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void learningModeOnlyExposesDashboardTool() {
    // when learning dashboard mode is enabled, tool list should contain only getLearningDashboard
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=AiModelServiceImplLearningToolSelectionTests test`
Expected: FAIL until the tool selection branch is added.

- [ ] **Step 3: Write minimal implementation**

```java
if (prompt.isLearningDashboardEnabled()) {
    return new Object[]{ aiLearningDashboardTool };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=AiModelServiceImplLearningToolSelectionTests test`
Expected: PASS.

### Task 5: Add trace sink and evaluation cases

**Files:**
- Create: `src/main/java/com/hailin/blogsystem/ai/trace/AgentDecisionTraceSink.java`
- Create: `src/main/java/com/hailin/blogsystem/ai/trace/LoggingAgentDecisionTraceSink.java`
- Create: `src/test/resources/agent-learning-cases.json`
- Test: `src/test/java/com/hailin/blogsystem/AgentDecisionTraceTests.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void traceSinkReceivesRequestIdAndDecisionReason() {
    // verify one structured trace line per decision
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -Dtest=AgentDecisionTraceTests test`
Expected: FAIL until the trace sink exists.

- [ ] **Step 3: Write minimal implementation**

```java
public interface AgentDecisionTraceSink {
    void trace(String requestId, AgentDecision decision);
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -Dtest=AgentDecisionTraceTests test`
Expected: PASS.

---

### Notes

- Keep `learningPlanRef` and `learningStageRef` in `AiIntent`; do not use them as final ids.
- Keep `queryLearningPlans` and `queryLearningPlanDetail` in backend code, but remove them from the model-facing tool list.
- Keep active Workflow routing ahead of Planner routing.
- Use `ai_workflow_runs.context_json` for `agentAutoStarted=true` in V1 if you need a cheap auto-start counter.
