# Prompt 与工具暴露收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收口普通聊天工具 schema，修复分类器 repair 上下文，并为模型输入中的不可信材料增加统一边界。

**Architecture:** 保留现有分类器和 Planner 作为能力选择权威层，仅调整送入回答模型的工具集合与 Prompt 包装。分类器 repair 复用首轮实际 prompt，普通聊天与 Workflow 通过短边界文本区分可信指令和外部数据。

**Tech Stack:** Java 17、Spring Boot 3、Spring AI、JUnit 5、AssertJ、Maven

---

### Task 1: 收口普通聊天工具暴露

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/AiModelServiceToolExposureTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiModelServiceImpl.java`

- [x] 写失败测试：普通聊天无注解工具，文章检索只暴露 `AiArticleTools`，Dashboard 仍独占。
- [x] 运行 `AiModelServiceToolExposureTests`，确认因当前固定暴露旧工具而失败。
- [x] 最小修改 `buildTools`，删除已由确定性链路接管的旧工具对象。
- [x] 重跑测试确认通过。

### Task 2: 让分类器 repair 自包含

**Files:**
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/service/impl/ClassifierModeSwitchTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiIntentClassifierImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/ClassifierPromptBlocks.java`

- [x] 写失败测试：`ON_DEMAND` repair 含本轮按需规则和严格修复约束，`FULL` repair 含完整规则。
- [x] 运行 `ClassifierModeSwitchTests`，确认旧短 repair prompt 失败。
- [x] 用“本轮 normal prompt + repair 后缀”组装 repair system prompt。
- [x] 重跑测试确认通过。

### Task 3: 增加不可信材料边界

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/PromptTrustBoundary.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/AiModelServiceToolExposureTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/LlmAgentStepDeciderTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ArticleEvidenceVerifierTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/LlmStreamCallerTests.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiModelServiceImpl.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/LlmAgentStepDecider.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/ArticleEvidenceVerifier.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/workflow/LlmStreamCaller.java`

- [x] 先用调用入口测试锁定外部材料是数据，当前问题、目标和用户修改意见仍是指令输入。
- [x] 运行测试确认失败。
- [x] 增加短而统一的边界文本，不改现有字段、JSON schema 或步骤输出要求。
- [x] 重跑测试确认通过。

### Task 4: 定向回归

**Files:**
- Verify only

- [x] 运行相关 Prompt、工具、Workflow 定向测试。
- [x] 运行 `git diff --check`。
- [x] 检查差异，不覆盖工作区已有 5b/P3 改动，不提交 Git。
