# AI / Agent 域上线前审计（04）

- 审计日期：2026-10-17
- 审计人：ai-audit（team task-4）
- 范围：`blog-system/src/main/java/com/hailin/blogsystem/ai/**`（193 文件）、`controller/AiController.java`、`AiAgentRunController.java`、`AiWorkflowController.java`、`AgentRunDevController.java`、`security/AiRateLimitInterceptor.java`、`security/AiRateLimiter.java`、`utils/ClientIpUtils.java`、`config/WebMvcConfig.java`、`config/BlogAiProperties.java`、`config/AiTaskAsyncConfig.java`、`ai/billing/**`、`service/impl/AiMessageServiceImpl.java`、`service/impl/AiWorkflowStreamServiceImpl.java`、`service/impl/AiWorkflowRunServiceImpl.java`、`service/impl/AiBillingServiceImpl.java`、`service/impl/AiModelServiceImpl.java`、`ai/rag/**`、`ai/memory/**`、`ai/tool/**`、`application.yml`、`application-prod.yml`、`deploy/nginx.conf`
- 方法：纯静态只读审计（未修改任何业务代码），逐条对照既有文档 `docs/计费覆盖审计.md`、`docs/agent/*`、`docs/superpowers/*`，只报「仍存在且未闭环」的问题；对已登记且已确认修复的不重复报。
- 威胁模型：上线公网、AI 全部走阿里云百炼（按量计费）、单实例 Tomcat + Nginx、无独立 WAF/网关。

---

## 0. 结论（TL;DR）

**有 2 条 P0、10 条 P1 必须在上线前处理。**

最致命的两条是同一根链上的：

1. **游客可以匿名、免费、无限量地调用 AI**。`/api/ai/chat/stream` 被显式排除在 JWT 之外（游客设计如此），但游客限流只按 IP、每 IP 5 次/分钟、**没有任何日额度/全局预算**，而 IP 又取自可被客户端伪造的 `CF-Connecting-IP` / `X-Forwarded-For`（`deploy/nginx.conf` 把客户端这份头原样转发，源站直连未封禁——`docs/superpowers/specs/2026-07-13-comment-ip-location-design.md:17` 自己登记过「限制源站直连」是待办，至今未做）。一次游客请求 = 1 次分类器（走判断链强模型 `qwen3.7-plus`）+ 1 次正文生成（输出上限 4000 tokens），全部由平台买单。**登录用户也可以用「不带 Authorization 头」的方式降到游客通道，完全绕开计费。**
2. **第一跳分类器在 Tomcat 请求线程上同步执行，AI 没有全局并发闸门**。分类器是阻塞 `.call()`（plus 路径实测 7.8~12s，见 `BlogAiProperties.Jev` 注释），发生在 `streamChat()` 返回 Flux **之前**。Tomcat 默认 200 工作线程，仓库没有任何 `server.tomcat.threads.*` 配置。伪造 IP 后游客并发不受限 → 约 200 个并发请求即可把全站 Tomcat 线程占满，**AI 故障/被刷会演变成整站（登录、文章、评论全部）不可用**。

计费补全（B1-B4，文档标记 ✅）仍有 **3 条入口没有计费壳**，其中 2 条与文档「已补全」的结论直接冲突（见 §2 P1-1 / P1-2）。

---

## 1. P0（上线即可能被利用：账单 / 整站可用性）

### P0-1 游客免费 AI + 限流身份可伪造 + 无日额度 → 上线即可被刷爆账单

**位置**

- `blog-system/src/main/java/com/hailin/blogsystem/config/WebMvcConfig.java:42-46`（`/api/ai/chat/stream` 只挂 optional JWT）、`:60-70`（JWT 拦截 `/api/ai/**` 时显式 exclude `/api/ai/chat/stream`）
- `blog-system/src/main/java/com/hailin/blogsystem/security/AiRateLimitInterceptor.java:70-91`（游客按 `ClientIpUtils.getClientIp(request)` 限流，限额 = 登录用户一半）
- `blog-system/src/main/java/com/hailin/blogsystem/utils/ClientIpUtils.java:12-24`
- `blog-system/deploy/nginx.conf:28-36`
- `blog-system/src/main/java/com/hailin/blogsystem/config/BlogAiProperties.java:203-226`（只有 4 个滑动桶，无日额度/全局预算）
- `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java:242-244`（`userId == null` → `streamGuestChat`）

**证据**

```java
// WebMvcConfig.java:65-70 —— 游客可直达 chat/stream
registry.addInterceptor(jwtInterceptor)
        .addPathPatterns(... "/api/ai/**" ...)
        .excludePathPatterns(... "/api/ai/chat/stream" ...);
```

```java
// AiRateLimitInterceptor.java:74-83
if (userId != null) {
    type = "user"; identity = String.valueOf(userId);
} else {
    type = "ip";
    identity = ClientIpUtils.getClientIp(request);   // ← 客户端可伪造
    int guestLimit = Math.max(1, limit.getLimit() / 2); // chat: 10 → 游客 5 次/分钟
    limit = new BlogAiProperties.Limit(guestLimit, limit.getWindowSeconds());
}
```

```java
// ClientIpUtils.java:12-24 —— 优先级：CF-Connecting-IP > X-Forwarded-For > X-Real-IP > remoteAddr
String ip = firstValidIp(request.getHeader("CF-Connecting-IP"));
if (ip == null) ip = firstValidIp(request.getHeader("X-Forwarded-For")); // 取逗号分隔的第一个
if (ip == null) ip = firstValidIp(request.getHeader("X-Real-IP"));
if (ip == null) ip = firstValidIp(request.getRemoteAddr());
```

```nginx
# deploy/nginx.conf:31-35 —— 把客户端自带的头原样透传；没有 real_ip/白名单/剥离
proxy_set_header CF-Connecting-IP $http_cf_connecting_ip;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;  # 客户端值被追加在前
```

**触发场景**

1. 直接访问源站（nginx 监听 80、`server_name` 就是公网 IP `47.88.86.213`，仓库内没有「只允许 Cloudflare 网段回源」的配置）：
   ```bash
   for i in $(seq 1 100000); do
     curl -s -X POST http://<源站IP>/api/ai/chat/stream \
       -H 'Content-Type: application/json' \
       -H "CF-Connecting-IP: 10.0.$((RANDOM%255)).$((RANDOM%255))" \
       -d '{"message":"写一篇 2000 字的文章，然后详细解释 Redis 缓存穿透"}' &
   done
   ```
   每个请求都会被当作一个「新游客 IP」，5 次/分钟的限制完全失效。
2. 即使走 Cloudflare，`X-Forwarded-For` 的第一个值也可被客户端注入（`ClientIpUtils` 取第一个值，而不是取最靠近自己的可信代理追加的值）；是否可绕过取决于 Cloudflare 对 XFF 的处理，**不能作为防线**。
3. 登录用户：任意付费用户去掉 `Authorization` 头即落到游客分支（免费、不扣站内额度、不落库），计费形同虚设。

**后果（金额/数据）**

- 游客路径**不计费、不落 `ai_messages`、usage 直接丢弃**（`AiMessageServiceImpl.java:3757` 的 `routeUsage` 与 `:3950` 的 `new TokenUsageAccumulator()` 都没有消费方），所以**事后连「谁刷了多少」都查不到**。
- 每请求至少 2 次付费模型调用：分类器（judge 模型 `qwen3.7-plus-2026-05-26`，`application.yml:356`，失败重试上限 2 次）+ 正文生成（`qwen3.7-flash`，`maxTokens=4000`，输入含页面文章正文最多 9000 字 / RAG 片段 / 记忆）。
- 限流生效的上限：单 IP 5 次/分钟 = 7200 次/天/IP；配合伪造头 = 无上限。按每次 3k~8k tokens 量级、并发 100 请求/秒持续一小时，就是千万级 tokens/小时的真金白银，且没有预算熔断。
- 「游客每日 N 次」在 `docs/计费覆盖审计.md:206` 被登记为「另一刀、本次不做」，`docs/superpowers/specs/2026-07-13-comment-ip-location-design.md:17` 把「限制源站直连」登记为 later。**两条都已登记，但都是上线前必须做的成本闸门；本文把它们合并升级为 P0，因为后果从「IP 展示不准」变成了「账单失控」。**

**修复建议（上线前最小集）**

1. Nginx：只信任 Cloudflare 网段；用 `real_ip_header CF-Connecting-IP; set_real_ip_from <CF 段>; real_ip_recursive on;`，或干脆改成 `proxy_set_header CF-Connecting-IP $remote_addr`，并**禁止源站直连**（安全组只放行 CF 回源段）。
2. `ClientIpUtils` 改成**只信服务端可控值**：优先 `X-Real-IP`（由 Nginx `$remote_addr` 覆盖），且服务端需校验头部合法性；不要再优先客户端可设置的 `CF-Connecting-IP`。
3. 新增**游客日额度 + 全局日预算**（Redis 计数器/计数器桶，超限直接拒绝并告警）；这是「游客免费」这个产品决策能否上线的唯一硬条件。
4. 在 AI 入口加全局限流/并发保护（Tomcat 层、Nginx `limit_req`、或应用内信号量），保证 AI 被刷时不影响非 AI 请求。
5. 把「分类器」移出请求线程（见 P0-2），否则限流数量调得再小也挡不住「慢请求占线程」。

---

### P0-2 首跳分类器同步占用 Tomcat 线程 + AI 无全局并发闸门 → 百余并发即可拖垮整站

**位置**

- `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java:220-247`（`streamChat` 在返回 Flux 之前同步调用 `streamGuestChat` / `streamUserChat`）
- `AiMessageServiceImpl.java:299-301`（登录用户：`aiIntentClassifier.classify(...)` 同步阻塞）
- `AiMessageServiceImpl.java:3757-3773`（游客：同样先跑分类器 + Planner，再返回 Flux）
- `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiIntentClassifierImpl.java:99-116`（`callOnce` → `.call()` 阻塞；Jev 失败回退 plus）
- `blog-system/src/main/java/com/hailin/blogsystem/config/BlogAiProperties.java:74-86`（注释实测：Jev 失败回退 plus 单次 7.8~12s）
- `application.yml:97-135`（`connect-timeout: 30s` / `read-timeout: 60s`；分类器走 `qwen3.7-plus`）
- `application.yml:12-16`（`server` 只配了 port/context-path，**没有 Tomcat 线程/连接配置**）
- `AiMessageServiceImpl.java:536-542`（普通聊天主体虽然包了 `Flux.defer`，但分类器在 defer 之外；`buildPrompt` + 记忆召回 join(≤3s) + RAG ES 查询也在订阅线程上同步执行）

**证据**

```java
// AiMessageServiceImpl.java:242-244 —— 分类器/Planner 在方法体里先执行，然后才 return Flux
Flux<AiChatEventVO> events = userId == null
        ? streamGuestChat(message, aiChatDTO.getPageContext())   // 内部先 classify（:3758），同步阻塞
        : streamUserChat(...);                                   // 内部先 classify（:301），同步阻塞
return events.doOnSubscribe(...);
```

```java
// AiIntentClassifierImpl.java:238-241 —— 判断链走强模型，且是 .call()
.options(aiJudgeModelSupport.applyTo(OpenAiChatOptions.builder()).build())
...
.call()
```

**触发场景**

- 任意来源（游客/登录用户都行，游客还可换伪造 IP）发起大量并发 `/api/ai/chat/stream`。每个请求在 Tomcat 工作线程上要等：Jev（≤3s，若是白名单意图）或 plus 分类器（实测 7.8~12s，最坏等 30s 连接超时 / 60s 读超时）→ Planner → 订阅后还要拼 prompt（记忆召回 3s 超时 + ES 同步查询）→ 才释放线程。
- `AiOrchestrationTaskAdmission` **只覆盖 Agent/Workflow 长任务**（`blog-system/src/main/java/com/hailin/blogsystem/ai/task/AiOrchestrationTaskAdmissionImpl.java` 的调用点只有 Agent/Task/Workflow），**普通聊天和游客聊天完全不在准入体系内**，没有任何全局限流/队列。

**后果**

- 约 200 个并发慢请求即可占满 Tomcat 默认 200 线程 → 全站（登录、文章列表、评论、后台接口）全部排队超时。这是「AI 依赖故障/被刷拖垮整站」的最短路径，也是账单风险（P0-1）的放大器。
- 因为游客限流只按伪造 IP，攻击者可以用极低成本制造「200 个慢请求持续在线」。

**修复建议**

1. 分类/Planner/RAG 全部移出请求线程：`Flux.defer(() -> ...)` 包住 `streamUserChat/streamGuestChat` 的入口逻辑，或整体切到有界线程池（注意 `UserContext`/MDC 传递）。
2. 同时给 AI 入口加**全局并发信号量**（例如 16~32，超限直接 429/503）与每 IP/每用户并发上限；Nginx 层加 `limit_req` 作为第二道。
3. 给分类器加独立超时（现在继承全局 60s read-timeout，对交互式首跳过长），超时直接降级 GENERAL_CHAT。
4. 把 `/api/ai/chat/stream` 的 `Tomcat` 线程占用时长纳入上线压测（与 `docs/agent/agent-admission-stress-report.md` 的容量算法不同：那份只覆盖 worker 池，不覆盖聊天线程）。

---

## 2. P1（明显风险：成本漏口 / 资源 / 可用性 / 降级）

### P1-1 计费漏口①：Workflow「续跑 reject」直连 service，未经计费壳（文档称 B1 已覆盖 reject）

**位置**

- `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java:2915-2998`（`streamContinueActiveWorkflow`）
- 同文件 `:2969-2978`：

```java
// WAITING_REQUIREMENT_CONFIRM：用户输入当作补充需求，走 reject 流程
if (status == AiWorkflowStatus.WAITING_REQUIREMENT_CONFIRM) {
    try {
        workflow = aiWorkflowRunService.reject(workflowRunId, message);   // ← 没有 reserve / settle
    } catch (Exception e) { ... }
    return buildWorkflowStopFlux(...);
}
```

- 对比：计费壳只加在 `AiWorkflowStreamServiceImpl.java:164-271`（approve/reject/retry 的 SSE 独立端点），续跑走的是 `AiWorkflowRunService.reject` 直连，不经过那里。
- `docs/计费覆盖审计.md:48-49` 曾把它列为「未计费清单 #5」；`:218` 声称 B1（approve/reject/retry）已实施 ✅，但这条入口并未闭环。

**触发场景 / 后果**

- 用户发起一个「需求不明确」的文章创作 Workflow（进入 `WAITING_REQUIREMENT_CONFIRM`），然后在聊天框里发补充需求（而不是点按钮）→ `doReject` 会真实执行 `MEMORY_RETRIEVE + RAG_SEARCH + GENERATE_OUTLINE`（`CreateArticleWorkflowHandler.java:309-346`），全部是 LLM/embedding 真消耗，**一分钱不扣**。
- 生成大纲的输出 tokens 由模型决定（无 maxTokens 上限约束时可能上千 tokens），叠加记忆/RAG embedding/rerank；用 10 次/分钟的 chat 配额，每个「创建(小扣费) → 续跑(免费)」循环都能白拿一次大纲生成。
- 这属于「文档说补全了、这条入口没接」的典型；面试/复盘口径也会失真（真实成本低于账面）。

**修复建议**

- 在 `streamContinueActiveWorkflow` 的 reject 分支外包一层 `AiRequestBillingSupport`：按 run 的 token 差量预扣/结算（与 `AiWorkflowStreamServiceImpl.settleBilling` 完全同构，建议直接抽成公共方法），bizId 用 `WF:{runId}:CONTINUE:{msgId}`。
- 或者更简单：让聊天续跑也走 `AiWorkflowStreamService` 的计费路径。

### P1-2 计费漏口②：Agent 建议确认启动 Workflow 不计费（且该端点不在 AI 限流清单里）

**位置**

- `blog-system/src/main/java/com/hailin/blogsystem/ai/agent/AgentRunSuggestionService.java:155-222`（`confirmWithLock` → `:212 admission.runAdmitted(taskRequest, () -> createWorkflow(run, suggestion))`）
- 同文件 `:257-298` `createWorkflow(...)` → `aiWorkflowRunService.createLearningPlanWorkflow/.../createArticleOptimizeWorkflow`
- `AiWorkflowRunServiceImpl.java:88-110`（`@Transactional` 创建入口）、`:293-348`（`createWorkflowInternal` → `:319 result = handler.runInitialSteps(...)`，首次 LOAD/ANALYZE/GENERATE_PLAN 就是几十秒的 LLM）
- 该文件全文没有任何 `AiBillingService` / `AiRequestBillingSupport` / `BillingHandle` 引用（`grep billingSupport|BillingHandle|aiBillingService` 在 `ai/agent/` 无命中）
- 限流缺口：`WebMvcConfig.java:74-82` 的限流路径不含 `/api/ai/agent-runs/**`；`AiAgentRunController.java:32` 的映射就是 `/api/ai/agent-runs`

**触发场景 / 后果**

- 任意登录用户在 Agent 结果里拿到「建议启动 Workflow」确认卡后，点确认 → 后端同步跑完整 workflow 初始链（学习计划 / 学习调整 / 难点拆解 / 文章优化，含 LLM 分析 + 方案生成），**不预扣、不结算、不限流**。
- `runAdmitted` 是同步执行（该入口需要同步返回快照给前端渲染），所以还会**在 Tomcat 请求线程上占用几十秒**；被确认的 workflow 后续步骤由独立 approve 端点推进（那些有计费）。
- 结果：每创建一次 Agent run（聊天侧已计费，但成本远低于 workflow 初始链）就能白拿一次完整 workflow 初始化；「计费补全 B1/B2/B3」的台账漏了这一条。

**修复建议**

- `confirmWithLock` 内、`runAdmitted` 前用 `billingSupport.reserve(BIZ_WORKFLOW_ACTION, "WF:START:CANDIDATE:" + agentRunId, userId)`，执行完按 `session.activeWorkflowRunId` 的 run token 差量结算（可复用 `AiMessageServiceImpl.settleWorkflowStartBilling` 的逻辑，抽公共组件）。
- 把 `/api/ai/agent-runs/**` 纳入限流（至少 confirm 类 POST 进 workflow 桶/独立桶）。

### P1-3 计费漏口③：5b handoff 的「用户触发续跑」剩余 LLM 步骤不计费

**位置**

- `blog-system/src/main/java/com/hailin/blogsystem/service/impl/AiMessageServiceImpl.java:824-841`：编排在 handoff 暂停时就结算了（`billingSupport.close(billingHandle, subtreeTokens(...))`）。
- `ai/task/TaskWorkflowContinuationService.java:128-160`：Workflow 终态后 AFTER_COMMIT/巡检触发 `resumeRemaining(...)` → `:145 orchestrationService.resume(...)`，**没有新的预扣**；`:150-156` 只做事实收敛。
- `ai/task/WorkflowHandoffResumeListener.java:32-43`（AFTER_COMMIT 触发）

**触发场景 / 后果**

- 用户说「先优化这篇文章，再帮我分析一下」→ 计划 = `[WORKFLOW_HANDOFF(OPTIMIZE_ARTICLE), ARTICLE_ANALYSIS]`。第一段请求在 handoff 暂停时按子树结算（只含暂停前的 token）；用户确认/推进 workflow 后，AFTER_COMMIT 自动续跑 `ARTICLE_ANALYSIS`（一个完整的 Agent run，≤5 步 LLM），这部分 token 只记入 run/消息统计，**没有账单**。
- `docs/计费覆盖审计.md:363` 只登记了「启动恢复 / 低频巡检补偿触发的续跑仍不计费（平台成本）」；**用户确认后由 AFTER_COMMIT 触发的续跑不在登记范围内**，且它是「用户可见请求链」的延续。金额不大但会持续漏，且无观测口径。

**修复建议**

- 续跑入口用一个「无用户交互的后台计费单」：`resumeRemaining` 开始时按 AGENT cap 预扣（用户归属 = rootRun.userId），结束后按新增子树 token 结算；或至少在 `ai_billing_order` 记一条 `PLATFORM` 类型账单便于对账。

### P1-4 `max-tool-rounds` 不是运行时硬上限；单请求输入无任何长度校验；超额成本由平台承担

**位置**

- `blog-system/src/main/java/com/hailin/blogsystem/config/BlogAiProperties.java:261-268`（`chatMaxTokens=4000` / `maxToolRounds=5`）
- 全仓 `getMaxToolRounds()` 只有一个读取点：`ai/billing/ChatReserveCalculator.java:44-48`（**只用于预扣估算**）；`AiModelServiceImpl.java:88-99` 的 `.tools(...).stream()` 没有任何轮数上限参数（Spring AI 内部工具循环）
- `blog-system/src/main/java/com/hailin/blogsystem/entity/dto/AiChatDTO.java`（全文 8 行，只有 `sessionId/message/pageContext`，**无 `@Valid`、无 `@Size`**）
- `service/impl/AiModelServiceImpl.java:93-99`（只有 `maxTokens` 与 60s timeout，没有输入裁剪）
- `service/impl/AiBillingServiceImpl.java:152-169`（`actual > reserved` 时按预扣封顶，只打 `[BILLING-OVERRUN]` warn）
- `ai/billing/ChatReserveCalculator.java:41-56`（预扣被 `max-reserve-per-biz-type.CHAT=5000` credit 压顶）

**触发场景 / 后果**

- ① 用户发一条几 MB 的 message（JSON body 无校验，几 MB 可被拼进分类器与主 prompt）→ 真实输入 tokens 可能超过 50 万；预扣被 5000 credit 封顶 → `actual > reserved` → 按预扣收，**超额部分平台承担**（日志只有 warn，无告警/无止损）。
- ② 工具调用：预扣按 5 轮估算，但运行时没有 5 轮上限；模型触发更多轮时每次重发完整上下文，成本按轮数倍增，超过预扣后同上。
- ③ 游客完全没有预扣，这条直接放大 P0-1。
- `maxTokens` 只对**输出**有效；输入侧的唯一裁剪是文章正文 9000 字（`AiMessageServiceImpl.java:4331`），对话历史 40 条与 RAG 片段无总预算。

**修复建议**

- `AiChatDTO.message` 加 `@Size(max=4000)`（或产品定义），超长直接 40001；`pageContext` 字段也做长度/枚举校验。
- 把 `maxToolRounds` 变成**真正的运行时上限**：给 Spring AI 工具循环设置 `maxIterations` 等价配置（或改用手动 loop 并强制 break），并对每轮累计 tokens 做硬熔断。
- 统一「输入预算」：历史消息总字符预算、RAG 片段总字符预算（现在只有篇数/条数）。
- `[BILLING-OVERRUN]` 升级为指标/告警，并把「超额成本」记录到独立账（便于上线后对账发现被刷）。

### P1-5 AI 长任务池可被少量「免费账号」占满，拒绝服务所有用户

**位置**

- `blog-system/src/main/java/com/hailin/blogsystem/config/BlogAiProperties.java:34-45`（`workerCount=8` / `queueCapacity=32` / `maxConcurrentPerUser=2`）
- `config/AiTaskAsyncConfig.java:37-50`（core=max=8，AbortPolicy）
- `ai/task/AiOrchestrationTaskAdmissionImpl.java:60-96`（全局池满 → `POOL_FULL` → 503；每用户 2 并发）
- `application.yml:269-272`（`blog.wallet.initial-credit: 10000`）
- `blog-system/src/main/java/com/hailin/blogsystem/ai/billing/AiRequestBillingSupport.java:41-55`（`WORKFLOW_ACTION` cap 10000 credit；实际结算按真实 tokens 退差额 → 一次 workflow 真正只扣几十 credit）

**触发场景 / 后果**

- 注册免费赠送 10000 credit；一次文章创作/优化 Workflow 真实消耗通常只有几十~几百 credit（差额退回），所以**一个免费账号可以跑上百次 Workflow**。
- 每个账号可并发 2 个长任务；**4 个免费账号 = 8 个 worker 全部占满**，queue 再被 32 个任务填满后，其他所有用户的 Agent/Workflow 请求全部 503/429。每个任务持续几十秒到几分钟（`@Transactional` 里跑 LLM，见 P1-9），攻击者可以持续保持饱和。
- 这不是「准入设计」的回归（池、上限、回滚实现都是对的），而是**缺少按账号的成本/频率闸门**：免费额度让攻击成本≈注册成本，池容量又只有 8。
- 同一入口 `AgentRunSuggestionService.confirm` 走的是 `runAdmitted`（同步、不进池），且在 Tomcat 线程上跑几十秒、不限流（P1-2），是另一条线程占用路径。

**修复建议**

- 新注册赠送额度上线前下调/改为「仅聊天可用」；并给长任务加「每账号每日 N 次」配额（与游客日额度同一套计数器）。
- 池满时对「已付费/有真实充值」用户保留优先队列（当前无优先级，先到先得）。
- 把 `runAdmitted` 类同步入口也纳入整体并发预算（它不受池约束，但有 Tomcat 线程约束）。

### P1-6 生产 SSE 配置缺失：Nginx 未关缓冲/未加长超时，后端未配置异步超时

**位置**

- `blog-system/deploy/nginx.conf:9-37`（全文 38 行）：`location /api/` 内**没有** `proxy_buffering off;`、`proxy_cache off;`、`proxy_read_timeout`、`proxy_http_version 1.1`、`proxy_set_header Connection ''`；也没有 HTTPS
- 后端没有任何 SSE 专用响应头：全仓 grep 无 `X-Accel-Buffering`；`AiController.java:60`、`AiWorkflowController.java:54-93` 三个 SSE 端点只声明 `produces=TEXT_EVENT_STREAM`
- `application.yml:12-16` + 全文件 grep：**没有 `spring.mvc.async.request-timeout`**（仓库也没有 `server.tomcat.*` 线程/超时配置）

**触发场景 / 后果**

- Nginx 对代理响应默认 `proxy_buffering on`：AI 流式 token 会被 Nginx 攒在缓冲区，前端看起来「卡住很久然后一大段蹦出来」，首字体验归零；workflow 的步骤事件（几十秒间隔）更容易被攒到响应结束才到。
- 默认 `proxy_read_timeout` 60s：长 Workflow（首步 LLM 几十秒 + 多步）在 Nginx 侧可能先于后端被断开。
- 没有 `X-Accel-Buffering: no` 兜底，后端无法覆盖代理行为。
- 没有 HTTPS（AI 对话内容明文传输）——这条与部署审计重叠，但 AI 域必须记录：SSE/对话内容走 HTTP 明文。
- 另外：Tomcat 在未配置 `spring.mvc.async.request-timeout` 时使用容器默认异步超时（Tomcat 默认 30s 级别），长流是否会被提前结束**需要上线前用真实长任务压测确认**；至少 Nginx 的缓冲/超时是确定性问题。

**修复建议**

- `/api/` 下为 SSE 单独 location 或统一加：`proxy_buffering off; proxy_cache off; proxy_read_timeout 600s; proxy_send_timeout 600s; proxy_http_version 1.1; proxy_set_header Connection '';`
- 后端 SSE 响应加 `X-Accel-Buffering: no` 与 `Cache-Control: no-cache`（用 `ResponseEntity`/`ServerHttpResponse` 头，或 Filter 按路径加）。
- 配 `spring.mvc.async.request-timeout`（如 10 分钟）并在 `server.tomcat` 配置合理线程数；上线前用「文章创作」全流程实测。
- 加 HTTPS（Let's Encrypt 或 Cloudflare 全程 HTTPS）。

### P1-7 取消/断连后 Agent 与 Workflow 继续消耗 LLM；与聊天「取消全额退」口径不一致

**位置**

- 聊天（退款但会继续写半截消息）：`AiMessageServiceImpl.java:1254-1284`（`doFinally`：CANCEL/ON_ERROR → `aiBillingService.release`，注释明确「取消也全额退」）
- Agent：`AiMessageServiceImpl.java:1404-1619` —— `Flux.create` 把整个 Loop 提交到准入 worker；`doFinally`（`:1616-1619`）只清 `AiToolActionRegistry`，**没有任何取消信号**；worker 里的 `runtime.run(...)` 会跑完 ≤5 步（每步 LLM），最后照常 `billingSupport.close(...)`（`:1529`）
- Workflow 动作：`AiWorkflowStreamServiceImpl.java:137-272` —— `:139-143` 的 `sink.onCancel` 只打日志；`:151-272` worker 继续执行完整 `actionInvoker` 并在 `:268-271` 结算；`emitContent` 里用 `sink.isCancelled()` 只是不发事件
- 准入名额也直到 worker 跑完才释放（`AiOrchestrationTaskAdmissionImpl.java:125-148`）

**触发场景 / 后果**

- 用户关闭页面/点停止（SSE 取消）后：聊天已产生的 token 由平台/用户承担但**全额退款**（有意取舍，已登记）；Agent 取消却**继续跑完整 Loop 并按实际扣费**；Workflow 动作取消继续跑完并按差量扣费。同一「取消」在三条路径上语义不同，用户会看到「取消后还被扣钱」。
- 攻击者可以「发起 Agent/Workflow → 立即断连」批量提交任务：每个请求都会真实消耗几个 LLM step，同时占住 8 个 worker（P1-5），而支付的钱有上限（预扣），但**平台侧真实成本已经发生**；断连不释放准入名额会让排队时间被恶意拉长。

**修复建议**

- 统一取消语义并写进计费口径：要么「全部按键面已产生用量结算」，要么「全部全额退」；三条路径共用一个 `cancel()` 处理。
- Agent/Workflow worker 接收取消信号（`sink.isCancelled()` → 抛出/break，Runtime 每步开始检查），并在取消时释放名额。
- 断连后不再继续「未开始的后续步骤」；已产生的 tokens 照实结算。

### P1-8 ES 故障时 ARTICLE_SEARCH 主链路抛异常中断（不是降级），恢复/熔断依赖 5 分钟 canary

**位置**

- `ai/rag/ArticleRagRetrieveService.java:38-94`（`similaritySearch` 无 try/catch；Spring AI 1.0.0 的 `ElasticsearchVectorStore.doSimilaritySearch` 会把 `IOException` 包成 `RuntimeException` 抛出——已用 javap 核实）
- `ai/rag/ArticleRagSearchService.java:91-196`（`:93` 直接调 `retrieve`，没有异常降级；MySQL 兜底只在 `acceptedContexts`/`keywordContexts` 为空时触发，`:152-196`）
- 健康门：`ai/rag/index/ArticleRagIndexHealthService.java:40-66`（canary 每 5 分钟一次，失败置 UNHEALTHY）、`:87-93`（只有 HEALTHY 才放行 dense）
- `application.yml:437-444`（`interval-ms: 300000`）

**触发场景 / 后果**

- ES 在两次 canary 之间挂掉（≤5 分钟窗口）→ `denseSearchAllowed()` 仍为 true → `retrieve` 抛 unchecked 异常 → 该轮 `ARTICLE_SEARCH` 聊天在 `streamNormalChatFlow` 的 `Flux.defer` 里直接 error（用户看到连接中断/错误事件），**不会降级到关键字/MySQL 兜底**。
- 反方向：ES 恢复后也要等下一次 canary（≤5 分钟）才恢复 dense，RAG 可用性抖动以 5 分钟为粒度。
- 同类依赖：rerank（阿里云外部 HTTP）失败有 `DefaultArticleRagRanker` 兜底（`GteRerankV2ArticleRagRanker.java:129` catch，已核实）；embedding 故障会让向量检索抛错（同上路径），但由于 embed query 是同步阻塞，影响面类似。

**修复建议**

- `ArticleRagRetrieveService.retrieve` 包 try/catch：异常 → 记录 + 返回空，让 `search` 走 MySQL 兜底（兜底已存在）。
- `ArticleRagSearchService.search` 在向量/关键字任一步抛异常时直接进入 `buildMysqlFallbackContexts`，不要整体失败。
- 把 canary 间隔与「故障检测延迟」解耦：失败时允许快速重试探测（如 30s 初始回退），避免 5 分钟窗口。

### P1-9 Workflow 限流桶形同虚设（文档称「chat/workflow/rag 三桶」，workflow 桶永不命中）

**位置**

- `security/AiRateLimitInterceptor.java:115-131`：

```java
// Workflow：只拦"创建"（POST），GET 状态查询不拦。
if (uri.startsWith("/api/ai/workflows/")) {
    if ("GET".equalsIgnoreCase(method)) return null;
    if (uri.contains("/cancel") || uri.contains("/approve")
        || uri.contains("/reject") || uri.contains("/retry")) return null;
    return BUCKET_WORKFLOW;
}
```

- `controller/AiWorkflowController.java:30-94`：`/api/ai/workflows` 下**只有** GET `/{id}`、GET `/{id}/steps`、POST `/{id}/cancel`、POST `/{id}/approve/stream`、POST `/{id}/reject/stream`、POST `/{id}/retry/stream` —— 没有 POST 创建端点（创建同步版已被移除，见类注释）。
- Workflow 真正创建入口在 `/api/ai/chat/stream`（`AiMessageServiceImpl` 的 5 个 `streamXxxWorkflowFromIntent`）以及 `/api/ai/agent-runs/{id}/workflow-suggestion/confirm` → 前者落 chat 桶（10/分钟），后者不限流。
- 文档口径：`docs/agent/agent-v1-acceptance-checklist.md:78`、`docs/interview/redis-interview-notes.md:679` 仍写「workflow 3/600s」生效。

**触发场景 / 后果**

- 「workflow 3 次/10 分钟」的配置实际从无请求命中；Workflow 创建的真实频率上限是 chat 桶 **10 次/分钟**，比设计值宽松 33 倍。对单个账号，创建 Workflow 的 LLM 成本（cap 10000 credit）远高于闲聊，这条限流本来是给「贵操作」设的，现在没设。
- 结合 P1-5（免费额度 + 8 worker），一个账号 10 次/分钟的 workflow 创建速度足够把长任务池持续打满。

**修复建议**

- 在 `resolveBucket` 里给「会产生 LLM 的 Workflow 启动」加显式桶：要么把 `confirm` 端点纳入，要么在 chat/stream 里按分类结果二次限流（需要在业务层做，因为拦截器不知道路由结果）；至少把 dead bucket 从文档/面试材料里删掉或改准确。
- 建议新增「WORKFLOW_START」桶（如 3/10 分钟），在 `streamXxxWorkflowFromIntent` 的 `admission.precheck` 之前校验。

### P1-10 成本观测缺口：游客 tokens 完全丢弃、无预算/告警、异常账单只有 warn

**位置**

- 游客分类器 usage：`AiMessageServiceImpl.java:3757` 创建 `routeUsage` 后无任何消费方（`streamGuestChatFlow` 不接收它）
- 游客正文 usage：`AiMessageServiceImpl.java:3950` `new TokenUsageAccumulator()` 直接丢弃
- 登录用户：`ai_messages.token_count` 有落库（`:1169-1175`、`:1516-1526`、`:812-820`），但**供应商真实成本（judge/flash 分别计费）没有按模型拆分**；`[PERF-AI] llm_usage`（`ai/LlmResponseSupport.java:40`）只覆盖非流式 `.call()` 调用点，流式正文/分类器不打印 usage
- 无预算/日耗告警：全仓 grep `budget|告警|alert|dailyLimit|quota` 无任何 AI 成本告警实现；`AiBillingServiceImpl.java:155-167` 的 `[BILLING-USAGE-MISSING]` / `[BILLING-OVERRUN]` 只有 warn 日志
- `management.tracing.sampling.probability: 1.0`（`application.yml:224-227`）在 prod 未下调（与部署审计重叠，但会放大 AI 日志量）

**触发场景 / 后果**

- 上线后被刷/出 bug 时：**游客一分钱记录都没有**，只能看到阿里云账单；`[PERF-AI]` 日志里没有 guest 维度的 token；`ai_messages` 只有登录用户口径。事后无法回答「谁在刷、哪个入口、什么模型、烧了多少」。
- 预扣/结算的 `[BILLING]` 日志是按 request 的，不是按小时/按模型聚合，无法做「成本看板」和「日预算熔断」。

**修复建议**

- 游客路径：至少把 `routeUsage`+`replyUsage` 汇总后打一条 `[PERF-AI] stage=guest_usage ipHash=? tokens=? model=?`（IP 做哈希避免日志直接落 PII），并落一张轻量 `ai_usage_daily` 表/Redis 计数（按 guest/user/模型/day），供预算熔断。
- 增加「每日预算」：Redis 计数器超过阈值（例如按成本折算）→ 一键降级为「游客禁止 / AI 只读缓存」并告警。
- 按模型拆分 usage：分类器（judge）与生成（flash）分别记，否则无法解释成本结构（judge 是 plus，单价高于 flash）。
- 日志落 `X-Forwarded-*` 前的 IP 用 `hash`，避免日志泄漏访客 IP（与安全审计协同）。

---

## 3. P2（次要风险 / 已登记但值得上线前确认）

### P2-1 Redis 故障时 AI 全站 fail-closed（429），错误语义误导

- `security/AiRateLimiter.java:93-109`：Redis 异常时按 `failOpen=false` **拒绝**（默认值见 `BlogAiProperties.java:203-212`）。
- 后果：Redis 抖动/重启时，**所有 AI 请求（含付费用户）被 429 拒绝**；`RateLimitExceededException` 的提示是「请求过于频繁」，会把「基础设施故障」误报成「你刷得太快」。成本角度这是对的（宁可拒绝也不放量），但需要：① 错误码区分（503/系统繁忙）；② 监控告警；③ 在 Redis 故障时至少让「不消耗 LLM 的查询」继续可用（现状 AI 相关全部经过该拦截器的路径都拒绝）。
- 建议：`failOpen` 保持 false（防账单），但异常时返回 503+明确文案；Redis 健康状态暴露到 `/actuator/health` 的 details（现在 `show-details: never`）。

### P2-2 RAG 调试检索接口绕过 MySQL 可见性复核，且无白名单

- `ai/rag/ArticleRagController.java:39-44`：
```java
@GetMapping("/search")  //RAG 检索调试接口
public Result<List<ArticleRagContext>> searchArticleRag(@RequestParam String question){
    List<ArticleRagContext> contexts = articleRagRetrieveService.retrieve(question);
    return Result.success(contexts);
}
```
- 对比主聊天链路：`ArticleRagSearchService.java:164` 对**所有** acceptedContexts 调 `retainCurrentlyPublished(...)`（`:292-318`，MySQL 复核 status=PUBLISHED，失败 fail-closed）。调试接口不经过它，只依赖 ES metadata `articleStatus=1`（`ArticleRagRetrieveService.java:96-110`）。
- 抓不到的场景：文章发布→隐藏/删除的 RAG 删除是**异步**的（`ArticleRagSyncService.java:76-99`，同步 3 次重试 + 延迟队列 5 次 + 超过落 Redis 失败标记转人工）；如果删除最终失败或还在重试窗口内，ES 里仍留着 `articleStatus=1` 的旧 chunk，任何登录用户都能通过该接口读到隐藏/已删文章的 `rawContent`。
- 后果：数据泄漏窗口 = 删除重试期（指数退避最长约 1 小时 × 5）+「转人工」未处理期间（可能永久）；影响范围=任何登录用户。
- 建议：该接口加 `IndexManagementAccessGuard`（与 rebuild/repair 同一白名单）、或至少经过 `retainCurrentlyPublished`；生产考虑直接关闭（它本来就是调试接口）。

### P2-3 allowed-user-ids 硬编码在 `application.yml`，prod profile 未覆盖

- `application.yml:447-452`（`blog.ai.rag.rebuild.allowed-user-ids: [2085218095032741891]`）
- `application.yml:458-460`（`blog.ai.inspection.allowed-user-ids: [2085218095032741891]`）
- `application-prod.yml` 全文未覆盖这两个配置（只覆盖 DB/Redis/mail/JWT/OAuth 等）
- 门禁实现本身正确：`rag/index/IndexManagementAccessGuard.java:23-34`、`controller/AgentRunDevController.java:78-88`（JWT 只代表登录，不等于管理员）
- 风险：① 换环境/换账号后这两个白名单全部 403（已踩过一次：`application.yml:449-451` 注释记录了旧值 `[1,2]` 导致 rebuild/repair/reindex 全部锁死）；② 若该雪花 ID 被公开或账号被盗，攻击者可触发 `rebuild`（全量 embedding 重新计算，**按量计费**）+ `repair`（真实写 ES）+ dev 面板读原始 contextJson；③ 值写在版本库里，属于「硬编码运维账号」。
- 建议：改为环境变量（生产必填、无默认值，与敏感项一致）；rebuild/repair/reindex 加频率限制（现在有 `IndexRebuildLock` 防并发，但没有「一天最多一次」的频率闸）。

### P2-4 LLM 在 `@Transactional` 内执行，长事务占用 DB 连接（已知风险，缓解有限）

- `service/impl/AiWorkflowRunServiceImpl.java:88-110`（创建入口 `@Transactional`）→ `:293-320`（`handler.runInitialSteps`，含 ANALYZE/GENERATE 等 LLM 步骤）
- `application.yml:38-45` 注释已承认：8 个 worker 会长时间占住 8 个连接，Hikari 池调到 20 留余量
- 风险：worker 打满时 8/20 连接被长事务占用（几十秒~3 分钟，叠加 `LlmStreamCaller` 3 次重试与 60s timeout）；若 MySQL 同时变慢，普通请求可能拿不到连接 → 与 P1-5 叠加放大
- 建议：至少把「LLM 步骤」移出事务（先事务内落 run 行 → 事务外跑 initial steps → 短事务更新），或把池继续调大并有连接等待监控；这是已知取舍，上线前应明确「接受」并在监控里配 Hikari 等待告警。

### P2-5 余额不足的用户仍先消耗一次分类器（judge 模型）再 402

- `AiMessageServiceImpl.java:299-301`（分类）→ `:1119-1123`（`aiBillingService.reserve` 才抛余额不足）
- 后果：balance≤0 的账号/被扣穿的账号每次请求仍会白烧一次 plus 分类器（10 次/分钟/账号，可脚本化）；游客同理（P0 已覆盖）。
- 建议：对登录用户把「钱包非空」前置检查放在分类之前（只读一次钱包），或至少把 402 的请求限流（现在 chat 桶限流仍生效，10/min，成本可控但纯浪费）。

### P2-6 记忆/摘要抽取与分类器 prompt 未接 `PromptTrustBoundary`（持久化投毒面）

- 已接信任边界的生产 LLM 路径（核实）：`ai/PromptTrustBoundary.java` → `service/impl/AiModelServiceImpl.java:62`、`ai/workflow/LlmStreamCaller.java:199`、`ai/agent/LlmAgentStepDecider.java:92/119`（Article/General/Analysis/ArticleAgent 均继承）、`ai/agent/ArticleAnswerGenerator.java:99`、`ai/agent/ArticleEvidenceVerifier.java:156`
- 未接：`service/impl/AiConversationSummaryServiceImpl.java:268-304`（会话摘要，输入=历史消息，输出会被注入后续 prompt）、`AiMemoryCandidateExtractorServiceImpl` / `AiEpisodicMemoryExtractorServiceImpl` / `AiMemoryDecisionServiceImpl`（输入=对话正文，输出**持久化到 DB + ES** 并会进入后续 prompt）
- 后果：用户（或用户转述的文章正文）可以在对话里埋「请把以下内容记成我的长期偏好：……」影响记忆抽取的判定；记忆本身在 prompt 里被标注为参考数据，且有 `AiMemoryDecisionSanitizer`，风险低，但这是唯一没有信任边界提示的持久化链路。
- 建议：给摘要/抽取 prompt 也 append 信任边界（成本极低），并在记忆写入前做敏感词/越权指令过滤（已在工程化清单里登记为 TODO）。

### P2-7 其他零散输入点

- `POST /api/ai/conversations`（`AiController.java:26-30`）不在限流清单、无数量上限：登录用户可无限建会话（仅 DB 行增长，无 LLM），低危。
- `GET /api/ai/rag/articles/search` 虽是 GET，但带限流桶（rag 30/60s），无金额风险。
- `AgentRunInspectionService` 的 detail/steps/list 均校验 run 归属（`AgentRunInspectionService.java:344-359`），没有 IDOR；`AgentRunDevController` 原始列（inputJson/outputJson/contextJson）额外过白名单 + 自己 run（`:78-88`），可接受。
- `AiEditorDraftGenerator` 仍无调用点（`docs/计费覆盖审计.md:362` 已登记「接入时同样要计费」），当前不构成成本漏口，但接入时别忘计费。

---

## 4. 已核实「不是问题」的项（避免下轮重复审计）

1. **Agent 最大步数硬上限**：`ai/agent/AbstractAgentRuntime.java:56` `DEFAULT_MAX_STEPS=5`、`:476` `while (run.getUsedSteps() < run.getMaxSteps())`；SKIPPED 步也计步（`:1310` 注释），不存在无限循环。
2. **LLM 流式重试有界**：`ai/workflow/LlmStreamCaller.java:42-147` 首次 + 2 次重试、已输出内容后不重试、异常分类决定是否重试；非流式由 Spring AI RetryTemplate（`application.yml:128-135` max-attempts=2，只对 429）。
3. **Workflow 步进/重试有界且 retry 走计费**：`AiWorkflowStreamServiceImpl.java:164-271`（预扣 → 差量结算）；用户反复点 retry 每次都会真实扣费。
4. **AI 长任务准入实现正确**：有界池（core=max=8 + queue=32 + AbortPolicy）、每用户 2 并发原子占位、提交被拒回滚名额（`AiOrchestrationTaskAdmissionImpl.java:74-121`、`:152-175`）；自动/MDC 传递正确。
5. **Memory 不跨用户**：`ai/memory/AiMemoryRetrieveService.java:34-64`（ES filter source+userId，内存再按 userId 复核）、`AiEpisodicMemoryRetrieveService.java:64-138`（userId+projectKey 双过滤）；管理接口按 userId 归属查询（`AiUserMemoryServiceImpl.java:128-177`、`AiEpisodicMemoryServiceImpl.java:146-168`）。
6. **当前文章 QA 无 IDOR**：`ai/qa/ArticleQaTargetResolver.java:161-176` → `ArticleSessionAnchorService.loadReadable/isReadable`（公开 or 本人，`ArticleSessionAnchorService.java:120-148`）；`pageContext.articleId` 是客户端可控字段，但加载时按可读性校验。
7. **工具读文章只返回公开文章**：`ai/tool/AiArticleTools.java:32-71`（status=PUBLISHED + LIMIT 3）；文章动作执行器读权限同 `loadReadable`（`ArticleAgentActionExecutorImpl.java:93-101`）。
8. **受控写动作有确认壳**：`AgentWriteActionService.java:110-149`（等待确认 + Redis 锁 + 状态 CAS + run 归属），写执行器有归属/状态前置校验（`ArticleAgentWriteActionExecutor`、`LearningAgentWriteActionExecutor`）。
9. **启动依赖韧性**：`component/AiRunStartupRecoveryRunner.java:71-87`（catch 不阻断启动）、`search/ArticleSearchIndexInitializer.java:39-56`（catch）；ES 客户端懒连接；`AiBillingExpireTask` 兜底释放悬挂预扣（`:47-68`）。缺 `ALIYUN_API_KEY` 时启动失败（`application.yml:99` 无默认值）是**有意的 fail-fast**；`BillingReserveLimitValidator.java:43-62` 对「预扣上限 vs 钱包 CHECK 下界」fail-fast 也正确。
10. **登录用户 token 三口径**：聊天正文+分类器 → `ai_messages.token_count`（`AiMessageServiceImpl.java:1169-1175`）、Agent → run/step + message（`:1516-1526`）、Workflow → run/step（`WorkflowTokenRecorder.java:28-37`、`WorkflowStepRunner.java:59-130`）；分类器在计费口径处被显式排除（`:1135-1141` 注释），与文档一致。
11. **不存在「无计费壳且未被本文列出」的生产 LLM 入口**：全仓 `ChatClient|LlmStreamCaller` 调用点逐个回溯后，除 P1-1 / P1-2 / P1-3 三条外均有计费壳或属平台附属调用（分类器/记忆抽取/摘要/embedding/rerank，`docs/计费覆盖审计.md:204-210` 已登记为平台成本）。

---

## 5. 上线前最小行动清单（按优先级）

| # | 动作 | 对应发现 | 预估工作量 |
|---|---|---|---|
| 1 | 可信 IP：Nginx `real_ip` + 源站只允许 CF/负载均衡回源；`ClientIpUtils` 只信服务端覆盖的头 | P0-1 | 0.5d |
| 2 | 游客日额度 + 全局日预算熔断（Redis 计数 + 告警/降级开关） | P0-1、P1-10 | 1d |
| 3 | 分类/Planner 移出 Tomcat 请求线程 + AI 入口全局限流（Nginx `limit_req` + 应用信号量） | P0-2 | 1.5d |
| 4 | 补 3 条计费漏口（续跑 reject / 建议确认 Workflow / handoff 续跑） | P1-1~3 | 1.5d |
| 5 | `AiChatDTO.message` 长度校验 + 工具轮数硬上限 + 输入总预算 | P1-4 | 0.5d |
| 6 | 新注册赠送额度下调/加长任务日配额（防免费账号打满池） | P1-5 | 0.5d |
| 7 | Nginx SSE 配置 + `X-Accel-Buffering` + async timeout + 长任务实测 | P1-6 | 0.5d |
| 8 | 取消语义统一 + worker 取消信号 | P1-7 | 1d |
| 9 | RAG 向量检索异常 → MySQL 兜底（try/catch 降级） | P1-8 | 0.5d |
| 10 | 修 workflow 限流桶（新增 WORKFLOW_START 桶或把 confirm 纳入）并同步文档口径 | P1-9 | 0.5d |
| 11 | 游客 usage 落观测 + 模型维度成本看板/预算告警 | P1-10 | 1d |
| 12 | P2 项按需：调试检索关白名单、allowed-user-ids 环境变量化、Redis 故障 503 语义 | P2-1~3 | 0.5d |

**上线门（Gate）建议**：在完成 #1~#3 之前，即使功能测试全部通过也不建议公网上线——这三条决定了「AI 是否会被刷爆账单」和「被刷时整站是否一起挂」。
