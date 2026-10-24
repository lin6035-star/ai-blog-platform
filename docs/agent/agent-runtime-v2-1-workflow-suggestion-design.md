# Agent Runtime V2.1 Workflow Suggestion Design

> Created: 2026-08-27
> Scope: Let the readonly Learning Agent suggest a Workflow after observing learning context. The Agent may suggest, but must not directly execute high-impact business actions.

## Positioning

Agent Runtime V1 proved the smallest real Agent loop:

```text
goal -> decide action -> execute readonly action -> observation -> decide next step -> final answer
```

Agent Runtime V2.1 should connect that loop with the existing Workflow Runtime:

```text
Agent Runtime = dynamic diagnosis and recommendation
Workflow Runtime = deterministic, confirmation-gated business execution
```

The key upgrade is not "Agent can write data". The key upgrade is:

```text
Agent can decide that a fixed Workflow is the right next business path,
then ask the user to confirm before the backend starts that Workflow.
```

This keeps the project on a production-style boundary: LLM proposes, backend validates, user confirms, Workflow executes.

## Current Baseline

Already completed before this stage:

- Agent Router V1: classifier suggestion plus backend planner decision.
- Workflow Runtime: five persisted Workflows with step logs, retry, confirmation, idempotency, and active session binding.
- Agent Runtime V1: readonly Learning Agent loop with `ai_agent_runs`, `ai_agent_steps`, max step guard, action whitelist, memory/RAG/learning observations, and final answer.
- Frontend AI shell: global assistant, SSE messages, Workflow cards, route navigation resilience, and XSS-safe markdown rendering.

V2.1 must reuse these components. It should not create a second Workflow engine.

## Goal

Build a controlled bridge from Agent observation to Workflow suggestion.

Example:

```text
User:
我最近 Redis 学得有点乱，帮我理一下

Agent V1:
查询学习计划 -> 查询记忆 -> 最终回答学习建议

Agent V2.1:
查询学习计划 -> 查询记忆 -> 判断需要调整计划
-> 返回一个待确认建议：
   建议启动 LEARNING_PROGRESS Workflow
   原因：当前学习反馈和计划阶段安排不匹配
   用户确认后，后端启动现有 Workflow
```

## Non-Goals

Do not implement these in V2.1:

- Multi-Agent.
- external Agent framework integration.
- automatic Workflow start without user confirmation.
- direct `UPDATE_PROGRESS`, `CREATE_PLAN`, `SAVE_ARTICLE`, `PUBLISH_ARTICLE`, `DELETE`, or `FILL_EDITOR`.
- article creation or article optimization suggestions.
- long-running background Agent continuation.
- replacing existing Workflow cards with Agent cards.

## Action Model

Keep V1 readonly actions:

```text
QUERY_LEARNING_DASHBOARD
QUERY_MEMORY
SEARCH_RAG
ASK_USER
FINAL_ANSWER
```

Add one new terminal action:

```text
SUGGEST_WORKFLOW
```

Do not call it `START_WORKFLOW` in V2.1. `START_WORKFLOW` sounds executable and weakens the security boundary. The Agent is only allowed to suggest.

Recommended `SUGGEST_WORKFLOW` input:

```json
{
  "workflowType": "LEARNING_PROGRESS",
  "reason": "当前计划阶段与用户反馈不匹配，适合进入学习进度调整流程。",
  "initialMessage": "我最近 Redis 学得有点乱，帮我理一下",
  "risk": "MEDIUM"
}
```

Allowed workflow types in V2.1:

```text
LEARNING_PLAN
LEARNING_PROGRESS
LEARNING_ASSIST
```

Forbidden workflow types in V2.1:

```text
CREATE_ARTICLE
OPTIMIZE_ARTICLE
```

Reason: writing Workflows have stronger content and editor side effects. Learning suggestions are the safer first bridge.

## Run Status

Add a new Agent Run status:

```text
WAITING_WORKFLOW_CONFIRM
```

Status meaning:

- The Agent loop has stopped.
- The Agent has produced a pending Workflow suggestion.
- The backend has not started the Workflow yet.
- The user's next explicit confirm action may start the suggested Workflow.

Keep existing statuses:

```text
RUNNING
COMPLETED
FAILED
CANCELLED
WAITING_USER
```

`WAITING_USER` remains for clarification questions. `WAITING_WORKFLOW_CONFIRM` is specifically for a proposed business transition.

## Persistence Shape

Reuse `ai_agent_runs.context_json` for the pending suggestion.

Recommended context shape:

```json
{
  "observations": "...clipped context...",
  "pendingWorkflowSuggestion": {
    "workflowType": "LEARNING_PROGRESS",
    "reason": "当前计划阶段与用户反馈不匹配，适合进入学习进度调整流程。",
    "initialMessage": "我最近 Redis 学得有点乱，帮我理一下",
    "risk": "MEDIUM",
    "createdAt": "2026-08-27T20:50:00"
  }
}
```

No new table is required in V2.1. If later multiple suggestions, expiry, audit search, or admin review become important, extract a dedicated `ai_agent_workflow_suggestions` table.

## Backend Flow

Runtime flow:

```text
1. User enters LEARNING_AGENT route.
2. LearningAgentRuntime creates ai_agent_runs row.
3. Agent decides and executes readonly actions.
4. If Agent chooses FINAL_ANSWER:
   - persist terminal step
   - mark run COMPLETED
   - return normal assistant message
5. If Agent chooses SUGGEST_WORKFLOW:
   - validate workflowType is allowed
   - persist terminal step
   - save pendingWorkflowSuggestion into context_json
   - mark run WAITING_WORKFLOW_CONFIRM
   - return assistant message with suggestion payload
6. User clicks confirm in frontend.
7. Backend reloads agent run by id + user id.
8. Backend validates:
   - run status is WAITING_WORKFLOW_CONFIRM
   - pendingWorkflowSuggestion exists
   - workflowType is still allowed
   - no active Workflow already exists on the session
9. Backend starts the existing Workflow through the same service path used by normal routing.
10. Backend clears or marks the suggestion consumed.
11. Frontend switches to the existing Workflow card flow.
```

Cancel flow:

```text
1. User clicks cancel on suggestion card.
2. Backend validates run ownership.
3. Mark Agent Run CANCELLED.
4. Clear pendingWorkflowSuggestion from response state.
5. Return a normal assistant message such as "已取消建议，不会启动 Workflow。"
```

## API Design

Add two Agent Runtime endpoints:

```http
POST /api/ai/agent-runs/{agentRunId}/workflow-suggestion/confirm
POST /api/ai/agent-runs/{agentRunId}/workflow-suggestion/cancel
```

Both require login.

Confirm response should reuse the existing Workflow stream style where practical:

- If the project already has a non-stream Workflow creation path suitable for this transition, return the created `AiWorkflowRunVO`.
- If the target Workflow starts with streaming steps, provide a stream endpoint variant after the non-stream path is stable.

Recommended first implementation:

```text
confirm -> create Workflow run -> return Workflow snapshot
frontend then renders existing Workflow confirmation card
```

This avoids mixing Agent SSE and Workflow SSE in one request during the first version.

## Frontend UX

Use the existing AI message surface.

When STOP event contains a Workflow suggestion:

```text
AI text:
我看了你的学习计划和当前进度，建议进入学习进度调整流程。

Card:
建议启动：学习进度调整 Workflow
原因：当前计划阶段与反馈不匹配
按钮：继续 / 取消
```

After confirm:

```text
1. Disable buttons and show "正在启动 Workflow..."
2. Call confirm endpoint.
3. Replace suggestion card with existing Workflow card.
4. Keep subsequent approve/reject/retry/cancel behavior unchanged.
```

After cancel:

```text
1. Disable buttons and show "已取消"
2. Do not start Workflow.
3. Keep chat usable.
```

No Agent Step timeline is required in V2.1. The user-facing value is the suggested transition, not step visualization.

## Routing Boundary

Do not let `SUGGEST_WORKFLOW` bypass active Workflow routing.

Priority remains:

```text
activeWorkflow > LEARNING_ASSIST > LEARNING_PLAN / LEARNING_PROGRESS
              > LEARNING_AGENT > GENERAL_CHAT
```

If an active Workflow exists while confirming a suggestion, reject confirm with a friendly message:

```text
当前已有正在进行的 Workflow，请先完成或取消当前 Workflow。
```

## Decision Prompt Boundary

The Agent decision prompt should describe `SUGGEST_WORKFLOW` as a terminal action:

```text
当已有观察显示用户需要进入一个固定业务流程，而不是只需要一句建议时，
可以使用 SUGGEST_WORKFLOW。
你只能建议 LEARNING_PLAN、LEARNING_PROGRESS、LEARNING_ASSIST。
你不能直接启动 Workflow，不能更新计划，不能写入数据。
```

Use cases:

```text
用户想继续学习，但没有计划 -> SUGGEST_WORKFLOW LEARNING_PLAN
用户说学乱了/节奏不合适/想重新安排 -> SUGGEST_WORKFLOW LEARNING_PROGRESS
用户说某个阶段或知识点卡住 -> SUGGEST_WORKFLOW LEARNING_ASSIST
用户只是问今天学什么且计划清楚 -> FINAL_ANSWER
用户只是问 Redis 是什么 -> GENERAL_CHAT before Agent Runtime
```

## Validation Rules

Backend must validate the suggestion after LLM decision:

- `workflowType` must be in the V2.1 allowlist.
- `reason` must be non-blank and clipped.
- `initialMessage` defaults to the original user goal if missing.
- `risk` is informational in V2.1, but missing risk should default to `MEDIUM`.
- If workflow type is forbidden, mark Agent Run FAILED with a safe message.
- If required fields are malformed, retry decision repair once; if still invalid, fail the run.
- **Minimum observation（2026-08-27 补充）**: `SUGGEST_WORKFLOW` is only accepted after at least one successful readonly observation (`observations.size() >= 1`). A first-round suggestion with zero observations is rejected — the backend forces `FINAL_ANSWER` / `ASK_USER` instead. This prevents "Agent suggests a Workflow without looking at anything", which would regress to one-shot guessing. This is a deterministic judge rule, not an LLM instruction.

Backend must validate again on confirm:

- agent run belongs to current user.
- session belongs to current user.
- run status is `WAITING_WORKFLOW_CONFIRM`.
- suggestion has not been consumed.
- session has no active Workflow.
- target Workflow type is still allowed.

## Idempotency

Confirm should support `Idempotency-Key`.

First version can reuse the existing Workflow action idempotency pattern:

```text
fingerprint = agentRunId + action(confirm/cancel) + workflowType
ttl = 24h
same key + same fingerprint -> return cached result
same key + different fingerprint -> 409
```

This prevents double-clicking "继续" from creating duplicate Workflow runs.

### Concurrency Guard（2026-08-27 补充）

Idempotency-Key only prevents retrying the *same* request. Two different keys confirming concurrently would both pass the status check and create two Workflow runs. Two-layer guard:

```text
1. Redis lock on confirm/cancel (same pattern as WorkflowActionLock:
   SETNX + 120s TTL + Lua verify-release + Redis failure fail-open)
2. DB status CAS as the hard guarantee:
   UPDATE ai_agent_runs SET status = 'CONFIRMING' WHERE id = ? AND status = 'WAITING_WORKFLOW_CONFIRM'
   affected rows == 0 -> already consumed -> return existing workflowRunId / friendly message
```

Flow inside the lock:

```text
lock -> re-read run -> status CAS (WAITING_WORKFLOW_CONFIRM -> CONFIRMING)
     -> create Workflow via existing service path
     -> success: status COMPLETED + context_json.pendingWorkflowSuggestion.workflowRunId = new run id
     -> failure: status back to WAITING_WORKFLOW_CONFIRM (retryable), keep error visible
```

`CONFIRMING` is a short-lived transition state; the frontend never observes it as a stable state (the response is either the new Workflow VO or an error).

`ai_agent_runs` has no `version` column, so status CAS replaces the version CAS used by Workflows.

### Pending Suggestion Expiry（2026-08-27 补充）

When a new Agent Run is created, mark all previous runs of the same `session_id` still in `WAITING_WORKFLOW_CONFIRM` as `CANCELLED`:

```text
UPDATE ai_agent_runs SET status = 'CANCELLED'
WHERE session_id = ? AND status = 'WAITING_WORKFLOW_CONFIRM' AND id != ?
```

Semantics: "a new Agent judgement round started, the old suggestion naturally expires". The confirm endpoint additionally re-validates the run status, so clicking a stale card cannot start a Workflow.

## Failure Semantics

Recommended behavior:

| Failure | Behavior |
|---|---|
| LLM suggests forbidden Workflow | Mark Agent Run FAILED, return safe text |
| Confirm while active Workflow exists | Do not start new Workflow, return friendly conflict |
| Workflow creation fails | Keep Agent Run WAITING_WORKFLOW_CONFIRM, show retryable error |
| Confirm request times out after backend created Workflow | Idempotency-Key returns same Workflow on retry |
| Cancel succeeds | Mark Agent Run CANCELLED |
| Cancel after already confirmed | Return current Workflow snapshot or friendly already-consumed message |

## Implementation Status（2026-08-28 完成）

V2.1 前后端全部落地并通过验收（建议卡 → 继续 → Workflow 卡 / 刷新恢复 / 重复继续单 Workflow / 取消不启动），对应文件：

```text
ai/agent/AgentWorkflowSuggestion.java    record(workflowType, reason, initialMessage, risk)
ai/agent/AgentRunSuggestionView.java     record(status, suggestion)，历史消息恢复建议卡
ai/agent/AgentRunSuggestionService.java  confirm（幂等+锁+状态 CAS+复用 createXxxWorkflow）/ cancel / getSuggestion
ai/agent/LearningAgentRuntime.java       SUGGEST_WORKFLOW 终态 + 零观察裁判 + 新 run 过期清理
entity/dto/AgentStepActionType.java      + SUGGEST_WORKFLOW
entity/dto/AiAgentRunStatus.java         + WAITING_WORKFLOW_CONFIRM / CONFIRMING
controller/AiAgentRunController.java     POST confirm / POST cancel / GET suggestion（/api/ai/agent-runs/{id}/workflow-suggestion*）
AiMessageServiceImpl.java                STOP event 透出 workflowSuggestion + ai_messages.agent_run_id 绑定
前端 AiAssistant.vue                    建议卡（继续/取消）+ 历史 agentRunId 补拉恢复 + confirm 接 Workflow 卡
```

与设计的差异（实现时收敛，均为必要补充）：

- **confirm 需补计划定位**：chat 入口在创建 LEARNING_PROGRESS/ASSIST 前会先定位目标计划（点名/单 ACTIVE/多 ACTIVE 候选），confirm 路径直接转 DTO 会因 planId/candidates 双空被 Handler 拒绝——confirm 内复用同一套定位（单 ACTIVE 直接进、多 ACTIVE 候选让用户选）。
- **confirm 成功后同步 ai_messages.workflow_run_id**：否则刷新后 Workflow 卡无法按消息补拉恢复（建议卡→Workflow 卡闭环）。
- 幂等复用 WorkflowActionIdempotency（key 空间按 agentRunId 隔离）；锁复用 WorkflowActionLock 新增 agent 前缀重载；ai_agent_runs 无 version 列，用状态 CAS 替代 version CAS。
- 测试：AgentRunSuggestionServiceTests 11（含幂等命中/CAS 并发消耗/冲突/越权/建议快照）+ LearningAgentRuntimeTests 11（含零观察拒绝/正常建议/非法类型/过期清理）= 22 个，0 失败。

## Implementation Plan

### Phase 1: Backend Model and Runtime

- Add `SUGGEST_WORKFLOW` to `AgentStepActionType`.
- Add `WAITING_WORKFLOW_CONFIRM` (+ transition `CONFIRMING`) to `AiAgentRunStatus`.
- Add a small value object for pending Workflow suggestion.
- Extend `LearningAgentRuntime` terminal handling.
- Extend `LlmAgentStepDecider` prompt and JSON validation.
- Add deterministic tests for allowed/forbidden suggestions.
- **Backend judge rule**: `SUGGEST_WORKFLOW` only accepted with `observations.size() >= 1`; first-round suggestion forced to `FINAL_ANSWER` / `ASK_USER`.

### Phase 2: Confirm / Cancel API

- Add Agent Run service methods:
  - confirm pending Workflow suggestion.
  - cancel pending Workflow suggestion.
- Add controller endpoints.
- Reuse existing Workflow creation path (createLearningPlan/Progress/AssistWorkflow) instead of duplicating Workflow initialization.
- Add idempotency guard for confirm.
- Add concurrency guard: Redis lock + status CAS (`WAITING_WORKFLOW_CONFIRM -> CONFIRMING -> COMPLETED`, failure rolls back), record `workflowRunId` in context_json.
- New Agent Run creation cancels stale `WAITING_WORKFLOW_CONFIRM` runs of the same session.
- Add tests for confirm, cancel, conflict, duplicate confirm, concurrent confirm with different keys, and forbidden type.

### Phase 3: Chat SSE Payload

- Extend Agent response payload with `workflowSuggestion`.
- Persist assistant message with a snapshot so refresh can restore the suggestion card.
- Ensure normal `FINAL_ANSWER` responses remain unchanged.
- Add tests for Agent route returning suggestion payload.

### Phase 4: Frontend Card

- Extend AI message type with `workflowSuggestion`.
- Render a compact suggestion card under the AI message.
- Add "继续" and "取消" buttons.
- On confirm, call backend and render the returned Workflow using the existing Workflow card path.
- On cancel, mark card cancelled locally after backend success.

### Phase 5: Manual Acceptance

Use new sessions for these tests:

```text
1. "我今天继续学 Redis，帮我安排一下"
   Expected: normal FINAL_ANSWER when one clear plan exists.

2. "我最近 Redis 学得有点乱，帮我重新安排一下"
   Expected: SUGGEST_WORKFLOW -> LEARNING_PROGRESS, no Workflow starts before confirm.

3. "我还没有学习计划，想系统学 Redis"
   Expected: SUGGEST_WORKFLOW -> LEARNING_PLAN, no auto-create before confirm.

4. "Redis 跳表这里我卡住了"
   Expected: existing LEARNING_ASSIST route still wins when the sentence is explicit.

5. Confirm suggestion twice with same Idempotency-Key.
   Expected: one Workflow run.

6. Confirm while another active Workflow exists.
   Expected: friendly conflict, no second Workflow.

7. Agent suggests a Workflow on the first round with zero observations.
   Expected: backend rejects, forces a question or direct answer.

8. Two concurrent confirms with different Idempotency-Keys.
   Expected: exactly one Workflow run, the other gets the consumed / same workflowRunId result.

9. Start a new Agent dialogue while an old suggestion card is pending.
   Expected: old Agent Run is CANCELLED; clicking the old card cannot start a Workflow.
```

## Acceptance Criteria

V2.1 is complete when:

- Agent can produce `SUGGEST_WORKFLOW` after readonly observations.
- Backend only allows learning Workflow suggestions.
- Suggestions are persisted in Agent Run context.
- No Workflow starts before explicit user confirmation.
- Confirm starts exactly one existing Workflow.
- Cancel does not start Workflow.
- Active Workflow conflict is blocked.
- First-round `SUGGEST_WORKFLOW` with zero observations is rejected by the backend.
- Concurrent confirm with different Idempotency-Keys starts exactly one Workflow.
- Stale `WAITING_WORKFLOW_CONFIRM` runs are cancelled when a new Agent Run starts.
- Frontend can render and operate the suggestion card.
- Existing Workflow approve/reject/retry/cancel behavior remains unchanged.

## Later Evolution

After V2.1:

```text
V2.2: Agent Run / Step history API and backend inspection endpoints
V2.3: frontend Agent step timeline
V2.4: controlled write actions with confirmation, starting from learning progress update
V2.5: article-side Workflow suggestions after learning bridge is stable
V3: multiple domain agents behind one runtime
V4: evaluate LangGraph / LangChain4j only if local runtime maintenance becomes costly
V5: Multi-Agent only when there are real independent roles and measurable benefit
```

## Interview Summary

Agent Runtime V1 proved the bounded readonly loop. Agent Runtime V2.1 proves the next production boundary: the Agent can recommend a business process based on observations, but cannot execute it directly. The backend validates the suggested Workflow, persists the pending transition, requires user confirmation, applies idempotency, and then reuses the existing Workflow Runtime for actual business execution.
