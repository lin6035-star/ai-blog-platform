# Agent Feedback State Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复文章 Agent 结构化状态中的临时反馈残留、Verifier 自我污染和成功重试后旧错误残留。

**Architecture:** 反馈使用结构化清理键，不解析中文文案；Verifier 与收尾总结分别读取证据上下文和运行上下文；最近错误绑定动作类型，并在同动作成功后清除。

**Tech Stack:** Java 17、Spring Boot、JUnit 5、AssertJ、Mockito

---

### Task 1: 临时反馈生命周期

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentRunState.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAgentState.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AbstractAgentRuntime.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAgentRuntime.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ai/agent/ArticleAgentStateTests.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ArticleAgentRuntimeDecisionContextTests.java`

- [x] 先写测试：`QUERY_REQUIRED` 反馈在任一只读查询产生结果后消失，业务拒绝反馈继续保留。
- [x] 运行两类测试，确认因缺少结构化清理键而失败。
- [x] 新增反馈清理键并在索引差同步时为零观察写提案标记 `QUERY_REQUIRED`。
- [x] 运行测试确认通过。

### Task 2: Verifier 与总结上下文分离

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentRunState.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAgentState.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAgentRuntime.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AbstractAgentRuntime.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ai/agent/ArticleAgentStateTests.java`

- [x] 先写测试：Verifier 证据不含系统反馈和最近错误，总结上下文仍包含它们。
- [x] 运行测试确认当前共用 `decisionContext()` 导致失败。
- [x] 增加 `verificationEvidence()` 与 `summaryContext()`，分别接入 Verifier 和 maxSteps 总结。
- [x] 运行测试确认通过。

### Task 3: 最近错误收敛

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleAgentState.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ai/agent/ArticleAgentStateTests.java`

- [x] 先写测试：同一动作失败后重试成功，旧的 `latestError` 消失。
- [x] 运行测试确认失败。
- [x] 将最近错误绑定动作类型，并在同动作非失败结果到达时清除。
- [x] 运行测试确认通过。

### Task 4: 回归与文档

**Files:**
- Modify: `docs/agent/agent-token-optimization-design.md`

- [x] 更新 P2.3 实施记录，写清反馈清理键、双上下文和错误收敛语义。
- [x] 运行 `clean test-compile`。
- [x] 运行六类 Agent 相关测试，不跑全量。
- [x] 检查 `git diff --check`，不提交。
