# Article Agent Decider / Generator Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Article Agent 的判断模型只产出受证据约束的 `answerPlan`，再由普通模型流式生成一次最终正文，并保证直接 SSE、审计状态和 Token 口径一致。

**Architecture:** 共享 Decider 增加默认透传的规范化与结构校验钩子，Article Decider 用它约束 `answerPlan`；Verifier 改审要点，Article Runtime 在放行后通过专用 Generator 生成正文。Generator 始终收集权威全文，只有直接 Agent emitter 会把 chunk 映射为现有 `DATA`，Task Orchestration 的默认 emitter 保持缓冲。

**Tech Stack:** Java 17, Spring Boot 3, Spring AI `ChatClient`, Reactor, MyBatis-Plus, JUnit 5, Mockito, AssertJ

---

## File Map

- Create `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAnswerGenerator.java`: Article 专用普通模型流式生成、重试、Token 峰值和耗时日志。
- Create `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/GeneratedAnswer.java`: Generator 的权威全文、独立 usage、外部 chunk 交付标记。
- Create `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentAnswerStreamException.java`: 已对外输出部分正文后失败的专用异常。
- Modify `LlmAgentStepDecider.java` / `ArticleAgentStepDecider.java` / `ArticleDeciderPrompts.java`: `answerPlan` 协议、裁剪和 repair shape gate。
- Modify `ArticleEvidenceVerifier.java`: 优先审核 `answerPlan`，兼容旧 `answer/message`。
- Modify `AgentStepEmitter.java`: 增加默认 `emitAnswerChunk`，不破坏函数式接口。
- Modify `AbstractAgentRuntime.java` / `ArticleAgentRuntime.java`: 可覆写的正文生成钩子、终态 step 的 RUNNING→SUCCESS/FAILED 和 Generator Token 合并。
- Modify `AiMessageServiceImpl.java`: 直接 Agent chunk 映射为 `DATA`，Runtime 返回后避免重复分块；部分流失败不走成功落库链。
- Modify `ArticleAnalysisExecutor.java` only if needed to make buffering intent explicit; its lambda emitter should otherwise retain the default no-op chunk method.
- Add or modify focused tests beside the existing Agent tests; no database schema or frontend protocol changes.

### Task 1: Freeze the Article decision contract

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAgentStepDeciderTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/agent/ArticleDeciderPromptTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/LlmAgentStepDecider.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAgentStepDecider.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleDeciderPrompts.java`

- [x] **Step 1: Write failing tests for prompt and shape behavior**

Add assertions that Article normal and repair prompts require `input.answerPlan` and no longer request `input.answer`. Add mocked-decider cases for missing plan triggering one repair, repaired plan succeeding, and a plan over 1200 characters being trimmed to exactly 1200.

```java
assertThat(full.buildSystemPrompt(false))
        .contains("input.answerPlan")
        .doesNotContain("input.answer = 完整的回答文本");

when(callSpec.chatResponse())
        .thenReturn(textResponse("{\"actionType\":\"FINAL_ANSWER\",\"input\":{}}"))
        .thenReturn(textResponse("{\"actionType\":\"FINAL_ANSWER\",\"input\":{\"answerPlan\":\"结构清晰\"}}"));
assertThat(decider.decide("分析", "证据", 2, 6, usage).input().get("answerPlan"))
        .isEqualTo("结构清晰");
verify(callSpec, times(2)).chatResponse();
```

- [x] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
./mvnw -Dtest=ArticleAgentStepDeciderTests,ArticleDeciderPromptTests test
```

Expected: FAIL because the prompt still declares `answer` and the base decider has no normalization/shape hooks.

- [x] **Step 3: Implement the default hooks and Article overrides**

In `LlmAgentStepDecider`, apply both hooks after the first parse and repair parse:

```java
protected AgentStepDecision normalizeDecision(AgentStepDecision decision) { return decision; }
protected boolean isDecisionShapeValid(AgentStepDecision decision) { return true; }

private AgentStepDecision normalizeAndValidate(AgentStepDecision decision) {
    AgentStepDecision normalized = normalizeDecision(decision);
    return normalized != null && isDecisionShapeValid(normalized) ? normalized : null;
}
```

In `ArticleAgentStepDecider`, require a nonblank `answerPlan` only for `FINAL_ANSWER`, trim it, and replace values longer than 1200 characters with their first 1200 characters. Update both normal and repair Article prompt text to describe 1-5 concise points and `answerPlan`.

- [x] **Step 4: Run the focused tests and verify GREEN**

Run the same command and expect all selected tests to pass.

### Task 2: Make the Verifier review the plan

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ArticleEvidenceVerifierTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleEvidenceVerifier.java`

- [x] **Step 1: Write failing extraction and prompt tests**

Add one case where `answerPlan`, `answer`, and `message` are all present and capture the verifier user prompt. Assert only `answerPlan` is under `【拟回答要点】`. Keep a compatibility case proving an old `answer` decision still reaches the semantic gate.

```java
AgentStepDecision decision = AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
        .withInput(Map.of("answerPlan", "只审这个要点", "answer", "不要优先审这个旧正文"));
verifier.verify(run(12L, "分析文章"), decision, List.of("当前文章分析：三节"), READ_ARTICLE);
verify(requestSpec).user(userPrompt.capture());
assertThat(userPrompt.getValue())
        .contains("【拟回答要点】\n只审这个要点")
        .doesNotContain("不要优先审这个旧正文");
```

- [x] **Step 2: Run and verify RED**

```powershell
./mvnw -Dtest=ArticleEvidenceVerifierTests test
```

Expected: FAIL because current extraction is `answer -> message` and prompt names the input `回答草案`.

- [x] **Step 3: Implement plan-first extraction**

Change extraction order to `answerPlan -> answer -> message`, rename local variables and prompt labels to `拟回答要点`, while leaving rule gates, verdict parsing, fail-open and run-level verifier usage unchanged.

- [x] **Step 4: Run and verify GREEN**

Run the same focused test.

### Task 3: Add the streaming Article generator

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/GeneratedAnswer.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentAnswerStreamException.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAnswerGenerator.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAnswerGeneratorTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentStepEmitter.java`

- [x] **Step 1: Write failing generator tests**

Mock Spring AI's stream chain and verify: chunks are concatenated in order; `maxTokens(1600)` and `streamUsage(true)` are set; usage is counted once from the stream peak; no tool APIs are called; external delivery is the OR of emitter results; blank output fails; a retryable failure before external delivery retries up to two times; a failure after `emitAnswerChunk` returns true throws `AgentAnswerStreamException` without retry.

```java
AgentStepEmitter external = new AgentStepEmitter() {
    public void emit(int n, String a, String s, String m, String t) {}
    public boolean emitAnswerChunk(String chunk) { chunks.add(chunk); return true; }
};
GeneratedAnswer answer = generator.generate(run, decision, "结构化证据", external);
assertThat(answer.content()).isEqualTo("结构清晰，建议补充边界。");
assertThat(answer.deliveredAnyChunk()).isTrue();
```

- [x] **Step 2: Run and verify RED**

```powershell
./mvnw -Dtest=ArticleAnswerGeneratorTests test
```

Expected: test compilation fails because the generator types and emitter method do not exist.

- [x] **Step 3: Implement the minimal generator**

Use the default `ChatClient.Builder` without `AiJudgeModelSupport`, append `PromptTrustBoundary` to the system prompt, pass only goal/evidence/`answerPlan`, set `maxTokens(1600)` and a 60-second timeout, collect content independently of delivery, and apply `LlmErrorClassifier` retry/backoff only while no chunk has been externally delivered. Track per-attempt peak usage and emit `[PERF-AI] stage=agent_generation` without prompt/body content.

Add to `AgentStepEmitter`:

```java
default boolean emitAnswerChunk(String chunk) {
    return false;
}
```

- [x] **Step 4: Run and verify GREEN**

Run the same focused test.

### Task 4: Integrate generation with Runtime state and accounting

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAgentRuntimeVerifierTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAgentRuntimeTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAgentRuntimeDecisionContextTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AbstractAgentRuntime.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAgentRuntime.java`

- [x] **Step 1: Write failing Runtime tests**

Inject a mocked `ArticleAnswerGenerator` and assert: verifier `NEED_MORE` never invokes it; no-evidence-progress shutdown never invokes it; sufficient evidence invokes it once; generated content becomes run final answer and conclusion anchor input; final step changes `RUNNING -> SUCCESS`; failure changes the same row to `FAILED`; generator usage is added once to run and merged once into the terminal step alongside decider usage.

```java
when(generator.generate(any(), any(), anyString(), any()))
        .thenReturn(new GeneratedAnswer("最终正文", usageOf(30, 20), false));
AgentRunResult result = runtime.run(...);
assertThat(result.finalAnswer()).isEqualTo("最终正文");
verify(stepMapper).insert(argThat(step -> "RUNNING".equals(step.getStatus())));
verify(stepMapper).updateById(argThat(step -> "SUCCESS".equals(step.getStatus())));
```

- [x] **Step 2: Run and verify RED**

```powershell
./mvnw -Dtest=ArticleAgentRuntimeVerifierTests,ArticleAgentRuntimeTests,ArticleAgentRuntimeDecisionContextTests test
```

Expected: FAIL because Article Runtime still extracts full answer from the decision and inserts only a SUCCESS terminal step.

- [x] **Step 3: Implement overridable final-answer generation and step transitions**

Add a protected default generation hook in `AbstractAgentRuntime` that wraps existing `answer/message` extraction with zero extra usage. Override only in `ArticleAgentRuntime` to call `ArticleAnswerGenerator` with `runState.evidenceText()` (or clipped observations when absent). Insert a RUNNING terminal row before generation, then update the same entity to SUCCESS or FAILED. Merge generator usage into `stepUsage`, accumulate it once into the run, and call `finish` plus `onAnswerConcluded` only after successful nonblank generation. Re-throw `AgentAnswerStreamException` from `runWithPolicy`; ordinary pre-delivery generation failure may follow the existing failed-result/fallback path.

- [x] **Step 4: Run and verify GREEN**

Run the same focused Runtime test set.

### Task 5: Wire direct Agent SSE without replay

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Add or modify: the focused `AiMessageServiceImpl` Agent stream test class discovered in the current tree

- [x] **Step 1: Write a failing delivery-state test**

Drive the direct Article Agent path with a runtime/emitter that calls `emitAnswerChunk("甲")` and `emitAnswerChunk("乙")` before returning `finalAnswer="甲乙"`. Assert DATA payloads contain `甲`,`乙` exactly once and no post-run replay. Add a partial-stream exception case asserting no assistant success message, Memory/Summary extraction or `STOP` is produced.

- [x] **Step 2: Run and verify RED**

Run only the discovered focused service test class with `./mvnw -Dtest=<ClassName> test`; expect duplicate DATA or the old success fallback behavior.

- [x] **Step 3: Implement delivery tracking**

In the anonymous direct Agent emitter, override `emitAnswerChunk`, emit existing `DATA`, and atomically record whether any chunk reached the sink. After `runtime.run(...)`, execute `splitIntoChunks(agentReply, 32)` only when no answer chunk was delivered. Catch `AgentAnswerStreamException` before the broad `Throwable` branch and terminate the error stream without saving assistant text, running extractors/compression, or emitting `STOP`.

- [x] **Step 4: Run and verify GREEN**

Run the same focused service test.

### Task 6: Lock orchestration buffering and cross-domain compatibility

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ai/task/ArticleAnalysisExecutorTests.java`
- Modify only if needed: `blog-system/src/main/java/com/hailin/blogsystem/ai/task/executor/ArticleAnalysisExecutor.java`
- Existing regression tests: `blog-system/src/test/java/com/hailin/blogsystem/GeneralAgentRuntimeTests.java`
- Existing regression tests: `blog-system/src/test/java/com/hailin/blogsystem/LearningAgentRuntimeTests.java`
- Existing regression tests: `blog-system/src/test/java/com/hailin/blogsystem/LlmAgentStepDeciderTests.java`

- [x] **Step 1: Add the orchestration buffering assertion**

Capture the emitter passed to `runAnalysisOnly`, assert `emitAnswerChunk("内部正文")` returns false, and assert the executor returns only the completed `AgentRunResult.finalAnswer()` for `TaskResultRenderer` to render once.

- [x] **Step 2: Run the focused task test and verify behavior**

```powershell
./mvnw -Dtest=ArticleAnalysisExecutorTests test
```

If the new assertion passes immediately, record that it is a characterization test of the default emitter behavior; no production edit is needed. If it fails, make the smallest emitter change and rerun.

- [x] **Step 3: Run the final affected offline regression set**

```powershell
./mvnw -Dtest=ArticleAnswerGeneratorTests,ArticleAgentStepDeciderTests,ArticleDeciderPromptTests,ArticleEvidenceVerifierTests,ArticleAgentRuntimeVerifierTests,ArticleAgentRuntimeTests,ArticleAgentRuntimeDecisionContextTests,ArticleAnalysisExecutorTests,LlmAgentStepDeciderTests,GeneralAgentRuntimeTests,LearningAgentRuntimeTests test
```

Expected: all selected tests pass with zero failures. Do not run unfiltered `mvn test`, `-Dgroups=eval`, or real-model tests without separate approval.

- [x] **Step 4: Review the diff against the design**

Confirm no changes to database schema, frontend event names, classifier, Learning/General/Wallet prompts, or Workflow stream caller. Confirm `answerPlan` is bounded, Generator sees no tools, partial delivery cannot be replayed or saved as success, and direct versus orchestration delivery behavior remains distinct.
