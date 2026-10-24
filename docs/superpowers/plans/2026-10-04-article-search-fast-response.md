# Article Search Fast Response Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让单目标 `ARTICLE_SEARCH` 在 RAG 检索后由后端直接返回文章列表，完全跳过 Prompt、Memory、正文模型和 CHAT 计费。

**Architecture:** 新增无状态 `ArticleSearchResponseRenderer`，先按 `articleId` 保序去重并生成最多 5 个 `ArticleRagReferenceVO`，再以同一引用列表渲染正文编号。`AiMessageServiceImpl` 在登录和游客普通聊天流的最前面识别 `ARTICLE_SEARCH`，复用统一检索/渲染结果，分别完成已有的持久化或临时 DTO/SSE 协议。

**Tech Stack:** Java 17、Spring Boot、Project Reactor、JUnit 5、AssertJ、Mockito、MyBatis-Plus/H2 集成测试

---

### Task 1: 确定性文章搜索渲染器

**Files:**
- Create: `blog-system/src/main/java/com/hailin/blogsystem/ai/rag/ArticleSearchResponseRenderer.java`
- Test: `blog-system/src/test/java/com/hailin/blogsystem/ai/rag/ArticleSearchResponseRendererTests.java`

- [x] **Step 1: 写渲染器失败测试**

覆盖同文多 chunk 保序去重、最多 5 篇、正文 `[n]` 与引用顺序一致、snippet 空值和空结果：

```java
ArticleSearchResponse response = renderer.render("Redis", List.of(
        context(1L, "Redis 实战", 0, "  缓存\n一致性  "),
        context(1L, "重复 chunk", 1, "不会重复"),
        context(2L, "Redis 秒杀", 0, "库存预扣")));

assertThat(response.references()).extracting(ArticleRagReferenceVO::getArticleId)
        .containsExactly(1L, 2L);
assertThat(response.content()).contains("[1] Redis 实战", "[2] Redis 秒杀")
        .doesNotContain("重复 chunk");
```

- [x] **Step 2: 运行 RED**

Run: `./mvnw.cmd "-Dtest=ArticleSearchResponseRendererTests" test`

Expected: `FAIL`，原因是 `ArticleSearchResponseRenderer` 尚不存在。

- [x] **Step 3: 最小实现渲染器**

实现固定契约：

```java
@Component
public class ArticleSearchResponseRenderer {
    private static final int LIMIT = 5;

    public ArticleSearchResponse render(String query, List<ArticleRagContext> contexts) {
        Map<Long, ArticleRagReferenceVO> deduplicated = new LinkedHashMap<>();
        for (ArticleRagContext context : safeContexts(contexts)) {
            if (context != null && context.articleId() != null) {
                deduplicated.putIfAbsent(context.articleId(), ArticleRagReferenceVO.from(context));
            }
            if (deduplicated.size() == LIMIT) break;
        }
        List<ArticleRagReferenceVO> references = List.copyOf(deduplicated.values());
        return references.isEmpty()
                ? new ArticleSearchResponse(emptyText(query), references)
                : new ArticleSearchResponse(resultText(query, references), references);
    }

    public ArticleSearchResponse unavailable() {
        return new ArticleSearchResponse("文章搜索暂时不可用，请稍后重试。", List.of());
    }

    public record ArticleSearchResponse(String content, List<ArticleRagReferenceVO> references) {}
}
```

正文只读取 `references`：标题为空显示“未命名文章”，snippet 为空则不输出摘要；查询词为空时显示“相关公开文章”，避免把 `null` 写给用户。

- [x] **Step 4: 运行 GREEN**

Run: `./mvnw.cmd "-Dtest=ArticleSearchResponseRendererTests" test`

Expected: `BUILD SUCCESS`，渲染器用例全部通过。

### Task 2: 登录用户搜索快路径

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Create: `blog-system/src/test/java/com/hailin/blogsystem/ArticleSearchFastPathTests.java`

- [x] **Step 1: 写登录流失败测试**

使用 `@SpringBootTest` + `@MockBean` 固定分类与 Planner：

```java
when(planner.decide(eq(MESSAGE), eq(intent), any(PageContextDTO.class), eq(USER_ID), any(AiSessions.class)))
        .thenReturn(AgentDecision.builder()
                .action(AgentAction.CHAT)
                .intent("ARTICLE_SEARCH")
                .retrievalMode("ARTICLE_SEARCH")
                .build());
when(articleRagSearchService.search(MESSAGE, intent)).thenReturn(
        new ArticleRagSearchResult(ArticleRagSearchStrategy.HYBRID, contexts));
```

断言 `CHAT_STATUS -> PARAM -> DATA... -> STOP`、正文和 references 顺序一致、数据库保存 user/assistant、assistant `token_count` 等于分类消耗，并验证以下组件零调用：

```java
verify(promptService, never()).buildPrompt(anyString(), any(), any());
verify(modelService, never()).streamChat(any(), anyString(), any());
verify(billingService, never()).reserve(anyString(), anyString(), any(), anyLong());
```

- [x] **Step 2: 运行 RED**

Run: `./mvnw.cmd "-Dtest=ArticleSearchFastPathTests" test`

Expected: `FAIL`，旧路径仍调用 `buildPrompt` / `streamChat`。

- [x] **Step 3: 实现登录快路径**

在 `streamNormalChatFlow()` 创建 `AtomicBoolean`、解析 QA、构建 Prompt 之前增加：

```java
if (routeDecision.usesRetrieval("ARTICLE_SEARCH")) {
    return streamUserArticleSearch(
            message, rawPageContextJson, userId, sessionId, session, intent, routeUsage);
}
```

`streamUserArticleSearch()` 负责：

```java
ArticleSearchResponse response = searchAndRenderArticleResponse(message, intent);
save(userMessage);
AiMessages assistant = saveStreamAssistantMessage(sessionId, userId, rawPageContextJson, response.content());
assistant.setTokenCount(routeUsage == null ? 0L : (long) routeUsage.getTotalTokens());
updateById(assistant);
return Flux.concat(Flux.just(param), chunkEvents(response.content()), Flux.just(stop(response.references())));
```

仅当 token 大于 0 时更新 `token_count`。该方法不调用记忆候选提取、摘要压缩或 Tool Registry，因为这是一条确定性只读展示链，不存在模型回答或 Tool Calling。

- [x] **Step 4: 实现异常出口与耗时日志**

统一 helper 捕获 `articleRagSearchService.search()` 的 `RuntimeException`，记录堆栈并返回 `renderer.unavailable()`，不得回退正文模型。用 `AiStageTimer` 从检索开始计时，并在首个 `DATA` 前只记录一次：

```java
log.info("[PERF-AI] stage=article_search_response durationMs={} outcome={} references={}",
        timer.elapsedMillis(), outcome, response.references().size());
```

日志不包含 query、正文、Prompt 或用户身份。

- [x] **Step 5: 运行登录流 GREEN**

Run: `./mvnw.cmd "-Dtest=ArticleSearchFastPathTests" test`

Expected: 登录成功、空结果、MySQL fallback 及异常用例通过，且模型/Prompt/计费均为零调用。

### Task 3: 游客搜索快路径

**Files:**
- Modify: `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java`
- Modify: `blog-system/src/test/java/com/hailin/blogsystem/ArticleSearchFastPathTests.java`

- [x] **Step 1: 写游客流失败测试**

清空 `UserContext` 后发起相同请求，断言 `CHAT_STATUS -> PARAM -> DATA... -> STOP`、引用存在、数据库消息数不变，并验证 `buildPrompt`、`streamChat`、`reserve` 均未调用。

- [x] **Step 2: 运行 RED**

Run: `./mvnw.cmd "-Dtest=ArticleSearchFastPathTests" test`

Expected: `FAIL`，旧游客路径仍构建 Prompt 并调用正文模型。

- [x] **Step 3: 实现游客快路径**

在 `streamGuestChatFlow()` 最前面增加：

```java
if (guestDecision.usesRetrieval("ARTICLE_SEARCH")) {
    return streamGuestArticleSearch(message, intent, guestSession, userMessage);
}
```

`streamGuestArticleSearch()` 复用 `searchAndRenderArticleResponse()` 和 `splitIntoChunks()`，构造临时 assistant DTO 与 `STOP.references`，不执行任何数据库写入。

- [x] **Step 4: 运行 GREEN**

Run: `./mvnw.cmd "-Dtest=ArticleSearchFastPathTests" test`

Expected: 游客快路径用例通过，数据库保持无新增消息。

### Task 4: 定向回归与文档状态

**Files:**
- Modify: `docs/agent/ai-assistant-latency-first-batch-design.md`

- [x] **Step 1: 运行受影响测试**

Run: `./mvnw.cmd "-Dtest=ArticleSearchResponseRendererTests,ArticleSearchFastPathTests,ArticleRagSearchServiceTests,TaskStepExecutorTests,TaskArticleReferenceSupportTests,ArticleInteractionStreamTests" test`

Expected: `BUILD SUCCESS`，不运行无 `-Dtest` 的全量测试或在线分类门。

- [x] **Step 2: 静态检查**

Run: `git diff --check`

Expected: 无 whitespace error；允许 Windows 的 LF/CRLF 提示。

- [x] **Step 3: 更新实施记录**

把设计稿顶部状态改为“第二批第一刀已实施、定向验证通过”，在第 12 节补充实际测试类、测试数和未运行边界；没有真实启动和三次代表请求时，不写“2-4 秒已达成”。

- [x] **Step 4: 不自动提交**

按项目约定仅展示变更摘要。只有姐夫明确要求后，才执行 `git commit` 或 `git push`。
