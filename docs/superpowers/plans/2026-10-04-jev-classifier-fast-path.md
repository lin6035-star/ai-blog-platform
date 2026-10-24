# Jev Classifier Fast Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 plus 分类器前增加 Jev 三类只读快速路径，并在任何不确定或失败情况下完整回退 plus。

**Architecture:** 新增独立的 Jev HTTP Gateway、协议解析和纯函数映射器；`AiIntentClassifierImpl` 只负责编排“尝试 Jev -> 接受或回退 plus”。现有 `TaskPlanningFacade`、`AgentPlannerSupport`、Workflow 和写操作权限链保持不变。

**Tech Stack:** Java 17 `HttpClient`、Spring Boot `@ConfigurationProperties`、Jackson、JUnit 5、Mockito、AssertJ。

**Git:** 按项目约定，本计划不执行 `git commit` 或 `git push`。

---

### Task 1: 生产 Jev 协议与 HTTP Gateway

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/classifier/jev/JevDecisionGateway.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/classifier/jev/JevDecisionResult.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/classifier/jev/JevDecisionProtocol.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/classifier/jev/OpenRouterJevDecisionGateway.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ai/classifier/jev/JevDecisionProtocolTests.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ai/classifier/jev/OpenRouterJevDecisionGatewayTests.java`

- [x] **Step 1: 写协议失败测试**

覆盖请求必须显式包含 `pageContext.pageType/articleId=null`、响应缺字段时报错、概率越界时报错、合法响应映射出全部原子概率和 token。

- [x] **Step 2: 运行测试确认失败**

Run: `./mvnw.cmd "-Dtest=com.hailin.blogsystem.ai.classifier.jev.JevDecisionProtocolTests" test`

Expected: FAIL，生产协议类尚不存在。

- [x] **Step 3: 实现协议和结果对象**

复用测试 Adapter 已验证的 Jev 问题集合；保留 `0.70` 原子目标阈值，并始终发送显式空 `pageContext`：

```java
state.put("message", message);
page.put("pageType", pageContext == null ? null : pageContext.getPageType());
page.put("articleId", pageContext == null ? null : pageContext.getArticleId());
state.put("pageContext", page);
```

- [x] **Step 4: 写并运行 HTTP Gateway 测试**

使用本地 `HttpServer` 验证 Bearer Header、POST 路径、非 2xx 抛错和合法响应；不得请求真实 OpenRouter。

Run: `./mvnw.cmd "-Dtest=com.hailin.blogsystem.ai.classifier.jev.JevDecisionProtocolTests,com.hailin.blogsystem.ai.classifier.jev.OpenRouterJevDecisionGatewayTests" test`

Expected: PASS。

### Task 2: 快速路径确定性映射

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/classifier/jev/JevFastPathDecision.java`
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/classifier/jev/JevFastPathMapper.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ai/classifier/jev/JevFastPathMapperTests.java`

- [x] **Step 1: 写映射器失败测试**

锁定以下接受行为：

```text
无目标 + primary=general_chat + confidence>=0.70 -> GENERAL_CHAT
[article_qa] + 同主意图 + article-detail/articleId -> ARTICLE_DETAIL_QA
[article_analysis] + 同主意图 + article-detail/articleId -> ARTICLE_DETAIL_QA
[article_search] + 同主意图 -> ARTICLE_SEARCH
```

锁定以下回退原因：`MULTIPLE_GOALS`、`UNSUPPORTED_GOAL`、`PRIMARY_CONFLICT`、`LOW_CONFIDENCE`、`ARTICLE_CONTEXT_MISSING`。

- [x] **Step 2: 运行测试确认失败**

Run: `./mvnw.cmd "-Dtest=com.hailin.blogsystem.ai.classifier.jev.JevFastPathMapperTests" test`

Expected: FAIL，映射器尚不存在。

- [x] **Step 3: 实现纯函数映射器**

返回值必须同时携带接受的 `AiIntent` 或明确 `fallbackReason`：

```java
public record JevFastPathDecision(AiIntent intent, String fallbackReason) {
    public boolean accepted() { return intent != null; }
}
```

映射得到的三个意图统一设置 `requestKind=CHAT`、`suggestedAction=CHAT`、`risk=LOW`，不生成 `taskGoals`、动作参数或 Workflow 字段。

- [x] **Step 4: 运行映射器测试**

Run: `./mvnw.cmd "-Dtest=com.hailin.blogsystem.ai.classifier.jev.JevFastPathMapperTests" test`

Expected: PASS。

### Task 3: 配置、token 账本与分类入口回退

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/config/BlogAiProperties.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/ai/TokenUsageAccumulator.java`
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiIntentClassifierImpl.java`
- Modify: `blog-system/src/main/resources/application.yml`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/service/impl/JevFastPathClassifierTests.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/AiIntentClassifierUsageTests.java`

- [x] **Step 1: 写入口失败测试**

测试必须证明：

```text
PLUS_ONLY                 -> 不调用 Jev，调用 plus
JEV_FAST_PATH + 可接受结果 -> 返回 Jev AiIntent，不调用 plus
JEV_FAST_PATH + 复合目标   -> 调用 plus
JEV_FAST_PATH + Gateway 异常 -> 调用 plus
```

另外锁定 Jev 接受时 token 调用数为 1；Jev 后回退 plus 时调用数为 2。

- [x] **Step 2: 运行测试确认失败**

Run: `./mvnw.cmd "-Dtest=com.hailin.blogsystem.service.impl.JevFastPathClassifierTests,com.hailin.blogsystem.AiIntentClassifierUsageTests" test`

Expected: FAIL，分类入口尚未接入 Jev。

- [x] **Step 3: 增加独立引擎配置**

```java
private ClassifierEngine classifierEngine = ClassifierEngine.JEV_FAST_PATH;
private Jev jev = new Jev();

public enum ClassifierEngine { PLUS_ONLY, JEV_FAST_PATH }

@Data
public static class Jev {
    private String apiKey;
    private String endpoint = "https://openrouter.ai/api/alpha/decisions";
    private long connectTimeoutMs = 500;
    private long requestTimeoutMs = 1200;
}
```

`application.yml` 使用 `${JEV_API_KEY:}`，不得写入真实 Key；`BLOG_AI_CLASSIFIER_ENGINE=PLUS_ONLY` 是一键回退入口。

- [x] **Step 4: token 累加器支持 Jev 整数 usage**

新增空安全方法，把 `inputTokens`、`outputTokens`、总量和 `callCount` 累加；原有 Spring AI `Usage` 路径保持不变。

- [x] **Step 5: 分类器接入快速路径**

在现有首轮 plus 调用之前尝试 Jev。映射接受时立即返回最小 `AiIntent`；映射拒绝、配置缺失或任何异常时记录 `fallbackReason` 后进入原有 plus 控制流。不得把异常送入 `generalChat()`。

- [x] **Step 6: 运行入口与既有分类器定向测试**

Run: `./mvnw.cmd "-Dtest=com.hailin.blogsystem.service.impl.JevFastPathClassifierTests,com.hailin.blogsystem.AiIntentClassifierUsageTests,com.hailin.blogsystem.service.impl.ClassifierModeSwitchTests,com.hailin.blogsystem.service.impl.AiIntentClassifierBackfillTests" test`

Expected: PASS。

### Task 4: 定向回归与真实 API 验证

**Files:**
- Modify: `docs/agent/jev-classifier-fast-path-design.md`
- Modify only if milestone status changes: `AGENTS.md`, `CLAUDE.md`

- [x] **Step 1: 编译并运行全部新增离线测试**

Run:

```powershell
./mvnw.cmd "-Dtest=com.hailin.blogsystem.ai.classifier.jev.*Tests,com.hailin.blogsystem.service.impl.JevFastPathClassifierTests,com.hailin.blogsystem.AiIntentClassifierUsageTests" test
```

Expected: PASS；不调用真实 OpenRouter。

- [x] **Step 2: 运行 3 至 5 条真实 Jev 定向样例**

只选择：概念问答、当前文章问答、文章搜索、复合动作、Workflow 各一条。复合动作和 Workflow 的预期不是 Jev 判错，而是生产映射明确回退 plus。

Run: `./mvnw.cmd test "-Dtest=JevDecisionOnlineTests" "-DrunRealJev=true" "-DjevRepeat=1"`

Expected: 选中样例全部通过；不运行 67 条 plus 在线分类门。

- [x] **Step 3: 启动后端并检查生效日志**

Run: `./mvnw.cmd spring-boot:run`

Expected: 启动日志显示 `classifierEngine=JEV_FAST_PATH`、Jev endpoint 和超时，但不打印 API Key。

- [x] **Step 4: 更新状态文档**

把真实通过的测试数量、实际 P50/P95、已启用的三类白名单和一键回退环境变量写回设计稿；如果更新 `AGENTS.md`，必须同步更新 `CLAUDE.md`。
