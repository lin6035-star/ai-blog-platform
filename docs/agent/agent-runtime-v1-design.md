# Agent Runtime V1 Design

> Created: 2026-08-26
> Scope: Learning Agent readonly loop MVP. This is the next stage after Agent Router V1 and Workflow Runtime V1 are stable.

## Positioning

Agent Runtime V1 is not a replacement for the existing Workflow Runtime.

```text
Workflow Runtime = fixed, confirmation-gated business flows
Agent Runtime = bounded dynamic decision loop over safe actions
```

The current Workflow Runtime is better for high-impact flows such as article creation, article optimization, learning-plan creation, progress adjustment, and learning-assist task generation. Those flows have known steps and confirmation points.

Agent Runtime V1 is for tasks where the next step depends on the previous observation. The goal is to let the AI decide what to inspect next within backend-controlled limits, then produce a final answer.

## Why This Stage Exists

Agent V1 already has many capabilities:

- intent classification
- backend planner decision
- RAG search
- user memory
- learning-plan tools
- workflow runs
- step logs
- retry / cancel / idempotency / rate limit

Agent Runtime V1 does not add a brand-new capability such as "query learning plans". The important change is who decides the next step.

```text
Before:
  backend decides one route once
  then runs a fixed chat / tool / workflow path

After:
  Agent decides one safe step
  backend executes it
  Agent observes the result
  Agent decides the next safe step
  loop stops at final answer or boundary
```

This is the local, minimal version of an Agent Runtime idea: run, step, action, observation, checkpoint, budget, and recovery. Do not introduce LangGraph, LangChain4j, CrewAI, OpenAI Agent SDK, or Multi-Agent in this stage.

## First Scenario

The first scenario is Learning Agent readonly assistance.

Example user requests:

```text
我今天继续学 Redis，帮我安排一下今天学什么
我现在学到哪了，接下来怎么学
我下一步学什么
帮我安排今天学习
我最近学 Redis 有点乱，帮我理一下
```

Expected behavior:

```text
1. decide whether learning context is needed
2. query learning dashboard or progress
3. query user memory when learning preference may matter
4. search RAG only when external article knowledge is useful
5. produce a final learning suggestion
```

The first version should usually return one normal AI message to the user. Frontend Agent Step cards are deferred. Backend still persists the run and steps for debugging and later UI.

## Non-Goals

Do not implement these in Agent Runtime V1:

- Multi-Agent
- external Agent framework integration
- automatic learning-plan creation
- automatic learning-progress updates
- automatic workflow start
- article save / publish / delete
- editor fill actions
- frontend Agent Step card UI
- long-running background continuation after the request ends

The MVP may run synchronously inside the current request, with at most five steps, then save the final answer.

## Entry Rules

Agent Runtime V1 should only receive "continue learning / today's learning arrangement" requests.

Keep these on existing routes:

```text
我想学 Redis，帮我制定学习计划
  -> LEARNING_PLAN Workflow

帮我调整第二阶段
  -> LEARNING_PROGRESS Workflow

Redis 跳表我不懂，帮我拆小一点
  -> LEARNING_ASSIST Workflow

Redis 是什么
  -> GENERAL_CHAT

帮我写一篇 Redis 文章
  -> CREATE_ARTICLE Workflow
```

Active Workflow still has the highest priority. If `ai_sessions.active_workflow_run_id` exists, user input must continue the active Workflow before Agent Runtime routing.

### Intent Boundary（2026-08-26 补充）

Add a new intent `LEARNING_AGENT` to AiIntent. The classifier prompt must spell out the boundary explicitly:

```text
我想学 Redis，帮我制定学习计划       -> LEARNING_PLAN
帮我调整第二阶段                      -> LEARNING_PROGRESS
Redis 跳表我不懂，帮我拆小一点       -> LEARNING_ASSIST
我今天继续学 Redis / 下一步学什么    -> LEARNING_AGENT
帮我安排今天学习 / 有点乱帮我理一下  -> LEARNING_AGENT
```

Routing priority (high -> low):

```text
activeWorkflow > LEARNING_ASSIST > LEARNING_PLAN / LEARNING_PROGRESS
              > LEARNING_AGENT > GENERAL_CHAT
```

Backend rules must also tighten: the broad fallback "帮我 + 学习" must no longer open LEARNING_PLAN directly. Route to Agent Runtime is decided by the LLM classifier first; rules only act as directional fallback. This matches the existing principle "LLM gives suggestions, backend is the judge".

## Action Whitelist

Do not reuse the existing `AgentAction` name. It already means top-level planner actions such as `CHAT`, `TOOL`, `WORKFLOW`, and `CTA`.

Use a separate step action enum, recommended name:

```text
AgentStepActionType
```

Allowed in V1:

```text
QUERY_LEARNING_DASHBOARD
QUERY_MEMORY
SEARCH_RAG
ASK_USER
FINAL_ANSWER
```

Forbidden in V1:

```text
UPDATE_PROGRESS
CREATE_PLAN
START_WORKFLOW
SAVE_ARTICLE
PUBLISH_ARTICLE
DELETE
FILL_EDITOR
```

`ASK_USER` is a terminal action in V1. For example, if multiple Redis-related plans are found, the Agent should ask the user to choose instead of guessing.

## Runtime Model

Recommended tables:

```text
ai_agent_runs
ai_agent_steps
```

`ai_agent_runs` fields:

```text
id
user_id
session_id
goal
status
current_step
max_steps
used_steps
context_json
final_answer
error_message
created_at
updated_at
```

`ai_agent_steps` fields:

```text
id
agent_run_id
step_no
action_type
input_json
output_json
status
duration_ms
error_message
created_at
```

Run statuses:

```text
RUNNING
COMPLETED
FAILED
CANCELLED
WAITING_USER
```

Step statuses:

```text
RUNNING
SUCCESS
FAILED
SKIPPED
```

## Loop Shape

The loop is intentionally small.

```text
create AgentRun

while usedSteps < maxSteps:
  ask LLM for next AgentStepDecision
  validate action against whitelist

  if action == FINAL_ANSWER:
    save final answer
    mark run COMPLETED
    stop

  if action == ASK_USER:
    save question
    mark run WAITING_USER
    stop

  execute readonly action
  save observation to ai_agent_steps
  append clipped observation to context

if maxSteps reached:
  produce final answer from existing observations
  mark run COMPLETED
```

The LLM suggests actions. The backend validates action type, user ownership, input shape, max steps, rate limit, and error handling.

## Memory Injection Policy（2026-08-26 补充）

Agent Runtime decision prompts must NOT reuse the normal chat prompt builder (`AiPromptServiceImpl`), which auto-injects long-term and episodic memory.

In Agent Runtime:

- the decision prompt contains only: goal, clipped observations, action whitelist with parameter docs
- memory is fetched ONLY through the explicit `QUERY_MEMORY` action
- this keeps the Agent loop honest: the Agent decides what it needs, then calls a tool

Rationale: if memory were auto-injected, `QUERY_MEMORY` would be redundant and the loop would not demonstrate real tool usage.

## Observation Rules

Observations are data returned from actions, not free-form hidden thoughts.

Rules:

- persist action input and output summaries
- clip long observations before putting them back into the next LLM prompt
- do not store full prompts
- do not store API keys, JWT, passwords, or secrets
- do not expose internal ids to end users unless the existing product already exposes them
- store enough detail to debug why the Agent chose the next step

Recommended first clipping rule:

```text
max observation chars per step: 1500
max accumulated context chars: 6000
```

## Error Handling

V1 should be conservative.

- Redis / RAG / memory failure: record failed step and continue only if existing observations are enough; otherwise produce a graceful final answer.
- LLM returns invalid JSON: retry once with a stricter repair prompt, then fail the run.
- LLM returns forbidden action: mark run FAILED and return a safe message.
- Action input misses required fields: reject the action and ask the LLM once to repair; if still invalid, fail the run.
- max steps reached: try one final LLM summary; if that also fails, fall back to the last valid observation text. The frontend must always get an answer.
- ASK_USER / WAITING_USER is a terminal state in V1: the run stops and the user's next message starts a new request. No resume mechanism in V1; keep the question + context snapshot in the run so V1.2 can add continue.

No hidden infinite loop is allowed.

## Security Boundary

The first version is readonly.

Any future write action must follow this chain:

```text
LLM suggests action
backend validates action and target ownership
backend classifies risk
high-impact action creates confirmation state
user confirms
backend executes
```

Agent Runtime must not bypass existing Workflow confirmation rules. High-impact tasks should continue to use Workflow or a confirmation-gated Agent action.

## Relationship With Existing Components

Reuse existing capabilities instead of rebuilding them.

```text
Learning dashboard -> LearningPlansService（不整包复用 AiLearningDashboardTool）
Memory             -> AiMemoryRetrieveService / AiEpisodicMemoryRetrieveService（显式 QUERY_MEMORY）
RAG                -> ArticleRagSearchService
LLM streaming      -> existing ChatClient / AiModelService patterns where suitable
Trace/logging      -> similar idea as AgentDecisionTrace and WorkflowStepLog
Rate limit         -> existing Redis AI rate limiter
```

### Dashboard Responsibility Split（2026-08-26 补充）

`AiLearningDashboardTool` exists today and mixes learning plans with memory snippets (semantic + episodic) in one tool. Do NOT reuse it wholesale inside Agent Runtime: if `QUERY_LEARNING_DASHBOARD` silently carries memory, `QUERY_MEMORY` becomes fake.

The Executor must call each service directly and return one independent observation per action:

```text
QUERY_LEARNING_DASHBOARD -> LearningPlansService (listByUser / getDetail / matchActivePlansByMessage)
QUERY_MEMORY             -> AiMemoryRetrieveService + AiEpisodicMemoryRetrieveService
SEARCH_RAG               -> ArticleRagSearchService
```

Do not put the entire loop into `AiMessageServiceImpl`. Add a focused runtime service and let `AiMessageServiceImpl` only route into it.

Recommended component names:

```text
LearningAgentRuntime
AgentStepDecision
AgentStepActionType
LearningAgentActionExecutor
AgentRunManager
AgentStepRecorder
```

## Implementation Status（2026-08-26 完成）

V1 已按本设计落地并通过测试（约 60 个新测试 0 失败），对应文件：

```text
ai/agent/AgentStepDecision.java        record(actionType, input)，of() + withInput()
ai/agent/AgentStepDecider.java         决策器接口 decide + summarize（默认返回 null）
ai/agent/LlmAgentStepDecider.java      ChatClient 非流式、温度 0.2、JSON 输出、解析修复 1 次、白名单校验
ai/agent/AgentRunResult.java           record(agentRunId, status, finalAnswer, usedSteps)
ai/agent/LearningAgentRuntime.java     核心 Loop（maxSteps=5、clipContext 1500/6000、终态计步、summarize 兜底）
ai/agent/LearningAgentActionExecutor.java / LearningAgentActionExecutorImpl.java  只读动作执行器
entity/AiAgentRun.java + AiAgentStep.java / mapper 两个 / test schema.sql 双表
```

与设计差异（实际落地时的收敛，均为加固而非偏离）：

- 入口双签 = LLM intent（LEARNING_AGENT）+ backend suggestedAction（AGENT），不是 planner 单签
- 决策器白名单外 actionType 返回 null（整轮拒绝），不是返回错误 step
- 执行器失败（Service 抛异常）→ 该步 FAILED + observation "动作执行失败"，Loop 不中断
- 终态（FINAL_ANSWER / ASK_USER）计入 usedSteps/currentStep，语义与"下一步决策"一致
- AgentPlannerSupport 管道隔离：isLearningDomain 排除 LEARNING_AGENT（防止被 Plan Workflow 误拉）
- Codex 审查 6 项 + 运行期 NPE/429 修复全部并入（见项目 CLAUDE.md 2026-08-26 条目）

## Acceptance Cases

Minimum test cases before considering V1 complete（✅ 全部已覆盖，见下方测试类）：

- User has one Redis learning plan: Agent queries learning dashboard, optionally memory, and returns today's suggested learning task.
- User has no relevant learning plan: Agent asks whether to create a plan, but does not create it.
- User has multiple Redis-related plans: Agent asks user to choose, does not guess.
- RAG fails: Agent still answers from learning plan and memory when possible.
- Memory fails: Agent still answers from learning plan when possible.
- LLM returns forbidden action: backend rejects it and stops.
- LLM loops without final answer: max step guard stops execution.
- Active Workflow exists: Agent Runtime does not intercept the message.

## Later Evolution

After the readonly Learning Agent loop is stable:

```text
V2.1: allow Agent to suggest a learning Workflow, but require user confirmation
      design: docs/agent/agent-runtime-v2-1-workflow-suggestion-design.md
V2.2: expose Agent Run / Step history in backend API
V2.3: add frontend Agent step timeline
V2.4: add controlled write actions with confirmation
V3: multiple domain agents behind one runtime
V4: evaluate whether LangGraph / LangChain4j provides enough value to replace or wrap local runtime
V5: Multi-Agent only when there are real independent roles and measurable benefit
```

## Interview Summary

Agent V1 in this project already handles intent routing and fixed, confirmation-gated Workflows. The next stage is not to add Multi-Agent, but to build a small Agent Runtime that can make bounded step-by-step decisions.

The first version is a readonly Learning Agent. It can query learning progress, memory, and RAG, observe the results, and decide whether to continue or answer. Every run and step is persisted, every action is checked against a whitelist, and the loop is bounded by max steps. This demonstrates the core production Agent Runtime ideas without giving the model unsafe write authority.
