# Semantic Learning Workflow Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace keyword-first routing for the fourth and fifth learning Workflows with LLM semantic intent classification plus backend plan/stage validation and ambiguity handling.

**Architecture:** Extend the existing `AiIntent` contract with learning-specific intent values and textual references. The classifier becomes the primary semantic router for `LEARNING_PROGRESS`, `LEARNING_ASSIST`, and `LEARNING_PLAN_QUERY`; `AiMessageServiceImpl` keeps ownership checks, active-plan resolution, candidate selection, and existing Handler invocation. Existing regexes remain only as a narrow fallback for classifier failure or legacy classifier output, not as the primary meaning detector.

**Tech Stack:** Spring Boot, Java 17, Spring AI `ChatClient`, Jackson, JUnit 5, AssertJ, existing MyBatis-Plus learning-plan services.

---

### Task 1: Define the learning intent contract

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/AiIntent.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiMessageIntentRoutingTests.java`

- [ ] **Step 1: Write the failing contract test**

Add assertions that an `AiIntent` can carry:

```java
AiIntent intent = new AiIntent();
intent.setIntent("LEARNING_ASSIST");
intent.setConfidence(0.94);
intent.setLearningPlanRef("微服务");
intent.setLearningStageRef("阶段二");

assertThat(intent.getIntent()).isEqualTo("LEARNING_ASSIST");
assertThat(intent.getConfidence()).isEqualTo(0.94);
assertThat(intent.getLearningPlanRef()).isEqualTo("微服务");
assertThat(intent.getLearningStageRef()).isEqualTo("阶段二");
```

- [ ] **Step 2: Run the contract test and verify it fails**

Run:

```powershell
.\mvnw -Dtest=AiMessageIntentRoutingTests test
```

Expected failure: `AiIntent` does not yet expose `confidence`, `learningPlanRef`, or `learningStageRef`.

- [ ] **Step 3: Add only the required DTO fields**

Add:

```java
private Double confidence;
private String learningPlanRef;
private String learningStageRef;
```

Keep the existing `topic` behavior unchanged for article creation.

- [ ] **Step 4: Run the contract test and verify it passes**

Run the same Maven command and expect the contract assertions to pass.

### Task 2: Make the classifier understand learning workflow semantics

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiIntentClassifierImpl.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiIntentClassifierPromptTests.java`

- [ ] **Step 1: Add a prompt contract test**

Test `buildSystemPrompt()` through reflection and assert that it contains the four learning boundaries:

```java
assertThat(prompt).contains("LEARNING_PROGRESS");
assertThat(prompt).contains("LEARNING_ASSIST");
assertThat(prompt).contains("LEARNING_PLAN_QUERY");
assertThat(prompt).contains("learningPlanRef");
assertThat(prompt).contains("learningStageRef");
```

- [ ] **Step 2: Run the prompt contract test and verify it fails**

Run:

```powershell
.\mvnw -Dtest=AiIntentClassifierPromptTests test
```

Expected failure: the current classifier prompt has no fourth/fifth Workflow intent definitions or learning reference fields.

- [ ] **Step 3: Extend the classifier prompt**

Add these semantic rules:

```text
- LEARNING_PLAN: 用户想新建学习计划或规划学习路线
- LEARNING_PLAN_QUERY: 用户查看已有计划、阶段或进度，不要求修改
- LEARNING_PROGRESS: 用户要求调整、压缩、重排、延长、缩短或重新生成已有计划
- LEARNING_ASSIST: 用户表达某个学习计划、阶段或任务难度过高、卡住、理解困难，
  并希望解释、拆解或增加辅助任务
```

Require references to come only from the original message:

```text
learningPlanRef: 用户明确提到的计划名称或关键词，没有则 null
learningStageRef: 用户明确提到的阶段/任务名称，没有则 null
confidence: 0 到 1 的语义判断置信度
```

Update the JSON example to include:

```json
{
  "intent": "GENERAL_CHAT",
  "confidence": 0.98,
  "learningPlanRef": null,
  "learningStageRef": null,
  "actionType": null,
  "articleId": null,
  "userId": null,
  "content": null,
  "target": null,
  "param": null,
  "topic": null,
  "categoryName": null,
  "requirements": null
}
```

- [ ] **Step 4: Mark classifier fallback confidence explicitly**

When JSON parsing or the LLM call fails, `generalChat()` must return `confidence=0.0`. A normal classifier result may use the model-provided confidence. This lets routing distinguish “the model confidently chose chat” from “classification failed”.

- [ ] **Step 5: Run the prompt and existing routing tests**

Run:

```powershell
.\mvnw -Dtest=AiIntentClassifierPromptTests,AiMessageIntentRoutingTests test
```

Expected result: all prompt and existing routing tests pass.

### Task 3: Route learning Workflows from classifier intent

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/AiMessageIntentRoutingTests.java`

- [ ] **Step 1: Write semantic route tests**

Add pure tests for the route predicates:

```java
assertThat(isLearningAssistIntent("LEARNING_ASSIST")).isTrue();
assertThat(isLearningProgressIntent("LEARNING_PROGRESS")).isTrue();
assertThat(isLearningPlanQueryIntent("LEARNING_PLAN_QUERY")).isTrue();
assertThat(isLearningAssistIntent("GENERAL_CHAT")).isFalse();
```

Add the original user sentence as a semantic classifier fixture, not a regex fixture:

```java
AiIntent intent = new AiIntent();
intent.setIntent("LEARNING_ASSIST");
intent.setConfidence(0.94);
intent.setLearningPlanRef("微服务");
intent.setLearningStageRef("阶段二");

assertThat(learningWorkflowRoute(intent)).isEqualTo("LEARNING_ASSIST");
```

- [ ] **Step 2: Run the route tests and verify the new assertions fail**

Run:

```powershell
.\mvnw -Dtest=AiMessageIntentRoutingTests test
```

Expected failure: the route helpers and intent-first branch do not yet exist.

- [ ] **Step 3: Add intent-first routing branches**

Immediately after active-workflow continuation and before legacy regex fallback:

```java
if ("LEARNING_ASSIST".equals(intent.getIntent())
        && hasUsableLearningConfidence(intent)) {
    return routeLearningAssistByResolvedPlan(
            message, intent, pageContext, rawPageContextJson, userId, sessionId);
}

if ("LEARNING_PROGRESS".equals(intent.getIntent())
        && hasUsableLearningConfidence(intent)) {
    return routeLearningProgressByResolvedPlan(
            message, intent, pageContext, rawPageContextJson, userId, sessionId);
}
```

The new helpers must reuse the existing plan resolution behavior:

1. Match `learningPlanRef` through `learningPlansService.matchActivePlansByMessage`.
2. If exactly one active plan matches, enter the corresponding Workflow.
3. If no explicit match but exactly one ACTIVE plan exists, enter it directly.
4. If multiple ACTIVE plans remain, pass all candidates to the existing Handler selection state.
5. If no ACTIVE plan exists, keep the existing safe fallback behavior.

- [ ] **Step 4: Keep regex only as a classifier-failure fallback**

Change the existing `looksLikeLearningDifficultyRequest` and `looksLikeLearningProgressRequest` usage so they are consulted only when `intent.getConfidence()` is `null` or `0.0`, or when an older classifier response lacks the new intent values. Do not use them to override a confident `GENERAL_CHAT` or `LEARNING_PLAN_QUERY`.

- [ ] **Step 5: Add query protection**

When the classifier returns `LEARNING_PLAN_QUERY`, skip both learning Workflows and continue to ordinary chat with the existing learning-plan query tools.

- [ ] **Step 6: Run route tests**

Run:

```powershell
.\mvnw -Dtest=AiMessageIntentRoutingTests test
```

Expected result: semantic intent tests and all existing query/progress/difficulty guard tests pass.

### Task 4: Verify real classifier output without making the Workflow tests LLM-dependent

**Files:**
- Create: `blog-system/src/test/java/com/hailin/blogsystem/AiLearningIntentClassifierIntegrationTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/AiMessageIntentRoutingTests.java`

- [ ] **Step 1: Add one opt-in integration test**

Create a test that calls the real classifier for:

```text
我感觉微服务计划中的阶段二挺难的
```

Assert only the semantic category and references:

```java
assertThat(intent.getIntent()).isEqualTo("LEARNING_ASSIST");
assertThat(intent.getLearningPlanRef()).contains("微服务");
assertThat(intent.getLearningStageRef()).contains("阶段二");
```

Mark it with the project’s existing real-LLM test convention so the normal focused unit suite does not depend on network/model availability.

- [ ] **Step 2: Run the opt-in integration test**

Run it explicitly after the backend and model configuration are available:

```powershell
.\mvnw -Dtest=AiLearningIntentClassifierIntegrationTests test
```

If the model returns an unexpected but valid paraphrase, inspect the raw structured intent before changing routing code.

### Task 5: Verify the two real Workflows end to end

**Files:**
- Test manually through the running frontend and backend.
- Reference: `blog-frontend/src/views/LearningPlansView.vue`
- Reference: `blog-frontend/src/views/LearningPlanDetailView.vue`

- [ ] **Step 1: Ensure one ACTIVE learning plan exists**

Use the existing third Workflow or open `/me/learning-plans` and confirm the plan is visible.

- [ ] **Step 2: Test `LEARNING_PROGRESS`**

Send:

```text
我感觉微服务计划中的阶段二挺难的，帮我把阶段二内容压缩一下
```

Expected: this must route to `LEARNING_PROGRESS`, not ordinary chat. The confirmation card should show an adjusted plan. Approve it, then verify the existing plan is updated and completed tasks remain completed.

- [ ] **Step 3: Test `LEARNING_ASSIST`**

Send:

```text
我感觉微服务计划中的阶段二挺难的，帮我拆成几个更小的任务
```

Expected: this must route to `LEARNING_ASSIST`. The confirmation card should show an explanation and 3-5 new tasks. Approve it, then verify only the target stage receives unchecked tasks.

- [ ] **Step 4: Test ambiguity**

Create two ACTIVE plans and send:

```text
我感觉阶段二挺难的
```

Expected: the system asks which plan or lists plan candidates instead of silently choosing the newest plan.

- [ ] **Step 5: Run focused backend verification**

Run:

```powershell
.\mvnw -Dtest=AiMessageIntentRoutingTests,LearningAssistWorkflowServiceTests,LearningProgressWorkflowServiceTests test
```

Expected: all focused routing, fourth Workflow, and fifth Workflow tests pass.
