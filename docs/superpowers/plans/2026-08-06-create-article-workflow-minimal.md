# Create Article Workflow Minimal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the first article workflow run end to end: create a workflow, pause for user confirmation, accept/reject feedback, and finally fill the editor.

**Architecture:** Keep the first version scoped to `CreateArticleWorkflow` only. Reuse the existing `AiWorkflowRun` record, `AiWorkflowController`, and `AiAssistant.vue` bottom confirmation panel. Align the backend VO with the frontend `context` shape so the workflow state can round-trip cleanly.

**Tech Stack:** Java 17, Spring Boot, MyBatis-Plus, Vue 3, TypeScript.

---

### Task 1: Align workflow response shape

**Files:**
- Modify `blog-system/src/main/java/com/hailin/blogsystem/entity/vo/AiWorkflowRunVO.java`
- Modify `blog-frontend/src/api/ai.ts`

- [ ] **Step 1: Make the backend return `context` instead of `contextJson`**

```java
private Object context;
```

- [ ] **Step 2: Keep the frontend workflow interface on `context`**

```ts
export interface AiWorkflowRun {
  context: CreateArticleWorkflowContext
}
```

- [ ] **Step 3: Verify the API contract still matches the existing UI code**

Run:
```powershell
npm -C blog-frontend run typecheck
```

### Task 2: Make the workflow panel use the real workflow API

**Files:**
- Modify `blog-frontend/src/components/ai/AiAssistant.vue`

- [ ] **Step 1: Route article-writing prompts into `createArticleWorkflow`**

```ts
const res = await aiApi.createArticleWorkflow({
  conversationId: currentSessionId.value ? Number(currentSessionId.value) : null,
  requirement: content,
  pageContext: buildPageContext(),
})
```

- [ ] **Step 2: Keep normal chat on the existing SSE path**

```ts
await aiApi.streamChat(currentSessionId.value, content, buildPageContext(), callbacks, abortController.signal)
```

- [ ] **Step 3: Update `activeWorkflow` and show the confirmation panel**

```ts
activeWorkflow.value = res.data
```

- [ ] **Step 4: Verify the component compiles**

Run:
```powershell
npm -C blog-frontend run typecheck
```

### Task 3: Finish the backend workflow skeleton

**Files:**
- Modify `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiWorkflowRunServiceImpl.java`
- Modify `blog-system/src/main/java/com/hailin/blogsystem/entity/AiWorkflowRun.java`
- Modify `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/AiWorkflowStatus.java`
- Modify `blog-system/src/test/resources/schema.sql`

- [ ] **Step 1: Keep `PAUSED` in the status enum and entity comments**

```java
PAUSED
```

- [ ] **Step 2: Persist workflow state with `workflowVersion`, `contextJson`, `retryCount`, and token counters**

```java
run.setWorkflowVersion("1.0");
run.setRetryCount(0);
```

- [ ] **Step 3: Add the workflow table to the test schema**

```sql
CREATE TABLE ai_workflow_runs (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  conversation_id BIGINT NULL,
  workflow_type VARCHAR(64) NOT NULL,
  workflow_version VARCHAR(32) NOT NULL DEFAULT '1.0',
  status VARCHAR(64) NOT NULL,
  current_step VARCHAR(64) NULL,
  context_json JSON NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  input_tokens INT NOT NULL DEFAULT 0,
  output_tokens INT NOT NULL DEFAULT 0,
  total_tokens INT NOT NULL DEFAULT 0,
  pause_reason VARCHAR(255) NULL,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL,
  updated_at DATETIME NOT NULL
);
```

- [ ] **Step 4: Run backend tests or at least a compile**

Run:
```powershell
cd blog-system
mvn test
```

### Task 4: Smoke the full path

**Files:**
- No code changes expected

- [ ] **Step 1: Start backend and frontend**
- [ ] **Step 2: Send a Redis article request**
- [ ] **Step 3: Confirm the workflow pauses on outline confirmation**
- [ ] **Step 4: Confirm approve/reject works and final approve triggers editor fill**

