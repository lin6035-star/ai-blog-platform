package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.AiStageTimer;
import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.LlmResponseSupport;
import com.hailin.blogsystem.ai.ClassifierProbe;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.classifier.jev.JevDecisionGateway;
import com.hailin.blogsystem.ai.classifier.jev.JevDecisionResult;
import com.hailin.blogsystem.ai.classifier.jev.JevFastPathDecision;
import com.hailin.blogsystem.ai.classifier.jev.JevFastPathMapper;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.AiIntentClassifier;
import com.hailin.blogsystem.service.LearningPlansService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AiIntentClassifierImpl implements AiIntentClassifier
{
    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final LearningPlansService learningPlansService;
    private final AiJudgeModelSupport aiJudgeModelSupport;
    private final BlogAiProperties blogAiProperties;
    private final JevDecisionGateway jevDecisionGateway;
    private final JevFastPathMapper jevFastPathMapper;

    @Autowired
    public AiIntentClassifierImpl(ChatClient.Builder chatClientBuilder,
                                  ObjectMapper objectMapper,
                                  LearningPlansService learningPlansService,
                                  AiJudgeModelSupport aiJudgeModelSupport,
                                  BlogAiProperties blogAiProperties,
                                  JevDecisionGateway jevDecisionGateway,
                                  JevFastPathMapper jevFastPathMapper) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.learningPlansService = learningPlansService;
        this.aiJudgeModelSupport = aiJudgeModelSupport;
        this.blogAiProperties = blogAiProperties;
        this.jevDecisionGateway = jevDecisionGateway;
        this.jevFastPathMapper = jevFastPathMapper;
    }

    /** 保留既有单元测试构造方式；未注入 Gateway 时直接走 plus。 */
    public AiIntentClassifierImpl(ChatClient.Builder chatClientBuilder,
                                  ObjectMapper objectMapper,
                                  LearningPlansService learningPlansService,
                                  AiJudgeModelSupport aiJudgeModelSupport,
                                  BlogAiProperties blogAiProperties) {
        this(chatClientBuilder, objectMapper, learningPlansService, aiJudgeModelSupport,
                blogAiProperties, null, new JevFastPathMapper());
    }

    /**
     * 启动时把生效的 prompt 模式打出来。
     *
     * <p>这个开关的价值全在"出事时能不能一眼确认自己改对了"——配置写错名字、profile 覆盖、
     * 环境变量没生效，都会让"我以为切回 FULL 了"变成又一次误判。一行日志成本最低。
     */
    @PostConstruct
    void logClassifierMode() {
        log.info("[P3] 分类器 prompt 模式 = {}（FULL = 19 块全装 / ON_DEMAND = 按需装载 + 复合目标回退）",
                blogAiProperties.getClassifierMode());
        BlogAiProperties.Jev jev = blogAiProperties.getJev();
        log.info("[JEV] classifierEngine={} endpoint={} connectTimeoutMs={} requestTimeoutMs={} apiKeyConfigured={}",
                blogAiProperties.getClassifierEngine(), jev.getEndpoint(), jev.getConnectTimeoutMs(),
                jev.getRequestTimeoutMs(), jev.getApiKey() != null && !jev.getApiKey().isBlank());
    }

    @Override  //判断分类，用户想要干什么，如果是要生成文章，单独拆一个实现类专门实现
    public AiIntent classify(String message, PageContextDTO pageContextDTO, Long userId,
                             TokenUsageAccumulator usage){
        // 生产链路走这个签名；带调用账本的观测版在下面（P3.0）
        return classify(message, pageContextDTO, userId, usage, null);
    }

    @Override
    public AiIntent classify(String message, PageContextDTO pageContextDTO, Long userId,
                             TokenUsageAccumulator usage, ClassifierProbe probe){
        AiStageTimer timer = AiStageTimer.start();
        ClassifierTiming timing = new ClassifierTiming();
        try {
            AiIntent fastPathIntent = tryJevFastPath(message, pageContextDTO, usage, timing);
            if (fastPathIntent != null) {
                timing.outcome = "success";
                return fastPathIntent;
            }
            /*
             * 第一段：不带计划列表。
             *
             * 绝大多数消息（闲聊、文章问答、概念问答、创建计划）到此为止——
             * 不查库、user prompt 里也没有计划列表（普通聊天不该背这个包袱）。
             */
            AiIntent aiIntent = callOnce(message, pageContextDTO, List.of(), usage,
                    probe, ClassifierProbe.CallRole.FIRST_PASS, timing);
            if (aiIntent == null || aiIntent.getIntent() == null || aiIntent.getIntent().isBlank()) {
                log.warn("AI意图识别失败，降级为普通聊天");
                markDegraded(probe);
                timing.outcome = "degraded";
                return generalChat();
            }

        /*
         * 第二段：只有「意图是调整/攻坚已有计划」且「用户原话点了计划名」才查列表 + 重新调用。
         *
         * 为什么是重新调用而不是事后补一个序号：计划定位要模型「看着选项选」，
         * 后端字符串匹配在同分并列时会退化（"C++" 曾被切成 "c"，与「C语言系统学习计划」同分，
         * 结果弹卡让用户重选——实测踩过）。宁可让这一小撮消息多等一次调用，
         * 也不让每条消息都带上计划列表（后者是"普通聊天也塞进 prompt"）。
         */
            List<LearningPlans> plans = List.of();
            if (needsPlanLocating(aiIntent)) {
                plans = loadActivePlans(userId);
                if (!plans.isEmpty()) {
                    log.info("意图命中计划定位，二次分类带列表：planRef={}", aiIntent.getLearningPlanRef());
                    AiIntent located = callOnce(message, pageContextDTO, plans, usage,
                            probe, ClassifierProbe.CallRole.PLAN_LOCATOR, timing);
                    if (located != null && located.getIntent() != null && !located.getIntent().isBlank()) {
                        aiIntent = located;
                    } else if (probe != null) {
                        // 定位失败但首轮结果可用 → 生产落点是**成功**。
                        // 不改标记的话，报告会把这条样例算成"最终失败"，掩盖真实可用性。
                        probe.markOutcome(ClassifierProbe.Outcome.SUCCESS_WITH_PLAN_LOCATOR_FALLBACK,
                                "计划定位未命中，沿用首轮结果（" + probe.getDetail() + "）");
                    }
                }
            }

            resolveLearningPlanId(aiIntent, plans);
            backfillWorkflowFields(aiIntent);
            reconcileReadOnlyContract(aiIntent);
            log.info(
                    "AI意图识别结果: intent={}, confidence={}, "
                            + "suggestedAction={}, suggestedWorkflowType={}, "
                            + "risk={}, reason={}, planRef={}, stageRef={}, "
                            + "planIndex={}, planId={}, "
                            + "actionType={}, articleId={}, authorId={}, userId={}, "
                            + "needsThinking={}, needsThinkingReason={}, "
                            + "requestKind={}, taskGoalTypes={}",
                    aiIntent.getIntent(),
                    aiIntent.getConfidence(),
                    aiIntent.getSuggestedAction(),
                    aiIntent.getSuggestedWorkflowType(),
                    aiIntent.getRisk(),
                    aiIntent.getReason(),
                    aiIntent.getLearningPlanRef(),
                    aiIntent.getLearningStageRef(),
                    aiIntent.getLearningPlanIndex(),
                    aiIntent.getLearningPlanId(),
                    aiIntent.getActionType(),
                    aiIntent.getArticleId(),
                    aiIntent.getAuthorId(),
                    aiIntent.getUserId(),
                    aiIntent.getNeedsThinking(),
                    aiIntent.getNeedsThinkingReason(),
                    aiIntent.getRequestKind(),
                    taskGoalTypes(aiIntent.getTaskGoals())
            );

            timing.outcome = "success";
            return aiIntent;
        } finally {
            log.info("[PERF-AI] stage=classifier durationMs={} outcome={} calls={} engine={} "
                            + "jevOutcome={} fallbackReason={} jevDurationMs={}",
                    timer.elapsedMillis(), timing.outcome, timing.calls, timing.engine,
                    timing.jevOutcome, timing.fallbackReason, timing.jevDurationMs);
        }
    }

    private AiIntent tryJevFastPath(String message, PageContextDTO pageContext,
                                    TokenUsageAccumulator usage, ClassifierTiming timing) {
        if (blogAiProperties.getClassifierEngine() != BlogAiProperties.ClassifierEngine.JEV_FAST_PATH
                || jevDecisionGateway == null) {
            timing.engine = "PLUS";
            return null;
        }
        BlogAiProperties.Jev config = blogAiProperties.getJev();
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            timing.engine = "PLUS";
            timing.jevOutcome = "fallback";
            timing.fallbackReason = "API_KEY_MISSING";
            return null;
        }

        long startedAt = System.nanoTime();
        timing.calls++;
        try {
            JevDecisionResult result = jevDecisionGateway.decide(message, pageContext);
            timing.jevDurationMs = (System.nanoTime() - startedAt) / 1_000_000;
            TokenUsageAccumulator.addTo(usage, result.inputTokens(), result.outputTokens());
            JevFastPathDecision decision = jevFastPathMapper.map(result, pageContext);
            if (decision.accepted()) {
                timing.engine = "JEV";
                timing.jevOutcome = "accepted";
                log.info("[JEV] accepted intent={} goals={} durationMs={}",
                        decision.intent().getIntent(), result.acceptedGoals(), timing.jevDurationMs);
                return decision.intent();
            }
            timing.engine = "PLUS";
            timing.jevOutcome = "fallback";
            timing.fallbackReason = decision.fallbackReason();
            log.info("[JEV] fallback reason={} goals={} durationMs={}",
                    decision.fallbackReason(), result.acceptedGoals(), timing.jevDurationMs);
            return null;
        } catch (Exception e) {
            timing.jevDurationMs = (System.nanoTime() - startedAt) / 1_000_000;
            timing.engine = "PLUS";
            timing.jevOutcome = "error";
            timing.fallbackReason = e.getClass().getSimpleName();
            log.warn("[JEV] 调用失败，回退 plus: type={} durationMs={}",
                    e.getClass().getSimpleName(), timing.jevDurationMs);
            return null;
        }
    }

    private String callClassifier(String message, PageContextDTO pageContextDTO,
                                  List<LearningPlans> plans, boolean repair,
                                  TokenUsageAccumulator usage,
                                  ClassifierTiming timing) {
        timing.calls++;
        try {
            ChatResponse response = chatClientBuilder.build()
                    .prompt()
                    .system(systemPrompt(repair, message, pageContextDTO))
                    .user(buildUserPrompt(message, pageContextDTO, plans))
                    //判断链：配了 judge-model 用强模型（换模型导致判分界线漂移的实测见 AiJudgeModelSupport）
                    .options(aiJudgeModelSupport.applyTo(OpenAiChatOptions.builder()).build())
                    .call()
                    .chatResponse();
            TokenUsageAccumulator.addTo(usage, LlmResponseSupport.usageOf(response));
            return LlmResponseSupport.textOf(response);
        } catch (Exception e) {
            /*
             * 429 的重试由 Spring AI 的 RetryTemplate 负责
             * （spring.ai.retry.on-http-codes: 429 + max-attempts: 3）。
             *
             * **这里刻意不再自己重试**：分类器走的正是 .call()，框架那一层已经生效，
             * 两层叠加会变成 3×3=9 次调用、退避层层相加，用户只会等到超时。
             * 流式路径则相反——框架完全不覆盖，所以重试实现在 LlmStreamCaller 里。
             *
             * 本处只负责把**失败类型**打出来：压测时正是靠它区分了「被 429 打挂」
             * 和「模型判不准」，两者现象都是"降级成闲聊"，但根因完全不同。
             */
            log.warn("AI意图识别调用失败（失败类型 {}）", LlmErrorClassifier.classify(e), e);
            return null;
        }
    }

    /**
     * 一次完整分类：调用 → 解析 → 解析失败则 repair 重试一次 → 仍失败返回 null。
     *
     * repair 的理由：模型偶尔把规则/注释文字混进输出，一次失败就降级会让
     * 「点名单任务」这类诉求静默退化成无能力聊天。
     */
    private AiIntent callOnce(String message, PageContextDTO pageContextDTO,
                              List<LearningPlans> plans, TokenUsageAccumulator usage,
                              ClassifierProbe probe, ClassifierProbe.CallRole role,
                              ClassifierTiming timing) {
        if (probe != null) {
            probe.recordRole(role);
        }
        String json = callClassifier(message, pageContextDTO, plans, false, usage, timing);
        if (probe != null && json != null) {
            probe.recordResponseChars(json.length());
        }
        if (json == null) {
            markFailure(probe, ClassifierProbe.Outcome.CALL_FAILED, role + " 调用未返回内容");
            return null;
        }
        AiIntent intent = tryParse(json);
        if (intent != null) {
            return intent;
        }
        if (probe != null) {
            probe.recordRole(ClassifierProbe.CallRole.REPAIR);
        }
        String repaired = callClassifier(message, pageContextDTO, plans, true, usage, timing);
        if (probe != null && repaired != null) {
            probe.recordResponseChars(repaired.length());
        }
        if (repaired == null) {
            markFailure(probe, ClassifierProbe.Outcome.CALL_FAILED, "repair 调用未返回内容");
            return null;
        }
        AiIntent repairedIntent = tryParse(repaired);
        if (repairedIntent != null) {
            log.warn("AI意图识别 repair 成功：首次输出混入非 JSON 文字，已恢复");
        } else {
            markFailure(probe, ClassifierProbe.Outcome.PARSE_FAILED, "首轮与 repair 均解析失败");
        }
        return repairedIntent;
    }

    private static final class ClassifierTiming {
        private int calls;
        private String outcome = "error";
        private String engine = "PLUS";
        private String jevOutcome = "skipped";
        private String fallbackReason;
        private long jevDurationMs;
    }

    /** 记一次失败原因（最终是否降级由"用户可见落点"覆盖，原因链保留在 detail 里） */
    private void markFailure(ClassifierProbe probe, ClassifierProbe.Outcome outcome, String detail) {
        if (probe != null) {
            probe.markOutcome(outcome, detail);
        }
    }

    /**
     * 记最终降级（P3.0）。
     *
     * <p>降级是**用户可见的落点**，所以 outcome 用 {@code DEGRADED}；但真正有用的信息是
     * "为什么降级"——把它连同前面的失败原因一起写进 detail，否则报告里只剩一句"降级了"，
     * 分不清是供应商 429 还是模型输出坏了（这两者现象一样、根因完全不同）。
     */
    private void markDegraded(ClassifierProbe probe) {
        if (probe == null) {
            return;
        }
        ClassifierProbe.Outcome previous = probe.getOutcome();
        String detail = previous == ClassifierProbe.Outcome.SUCCESS
                ? "首轮返回了空 intent"
                : previous + "：" + probe.getDetail();
        probe.markOutcome(ClassifierProbe.Outcome.DEGRADED, detail);
    }

    /**
     * 是否需要"带计划列表二次分类"——即这条消息要不要付出计划列表的代价。
     *
     * 判据：意图是「调整 / 攻坚已有计划」**且**用户原话点了计划名（learningPlanRef 非空）。
     * 任一不满足就不查库、不带列表：
     * - 闲聊 / 文章 / 概念问答 → 与计划无关
     * - LEARNING_PLAN（新建计划）→ 不涉及已有计划
     * - 没点名计划（"帮我调整一下计划"）→ 给了列表也选不出，交给下游追问
     */
    private boolean needsPlanLocating(AiIntent intent) {
        String intentName = intent.getIntent();
        if (!"LEARNING_PROGRESS".equals(intentName) && !"LEARNING_ASSIST".equals(intentName)) {
            return false;
        }
        String planRef = intent.getLearningPlanRef();
        return planRef != null && !planRef.isBlank();
    }

    //该用户的 ACTIVE 计划（分类器只做选择，不创建/修改）。查库失败不阻断分类——退化为按原话摘录。
    //只在"点名了计划"的二次分类里调用（needsPlanLocating），普通聊天不经过这里。
    private List<LearningPlans> loadActivePlans(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            return learningPlansService.listActiveByUserCached(userId);
        } catch (Exception e) {
            log.warn("分类器加载用户学习计划失败，本次不注入计划列表，退化为按原话摘录", e);
            return List.of();
        }
    }

    /**
     * 把模型选中的序号映射为权威 planId（后端独占写权限）。
     *
     * 模型不直接输出 ID：雪花 ID 19 位，模型抄写容易少位/错位；序号短且可校验。
     * 越界或缺失一律丢弃（learningPlanId 保持 null），由调用方落回关键词匹配兜底——后端绝不猜。
     *
     * public static 是为了可单测：这里的两条保障（清空防伪造 / 越界丢弃）是安全边界，
     * 被"优化"掉就是漏洞，必须有测试锁住。
     */
    public static void resolveLearningPlanId(AiIntent aiIntent, List<LearningPlans> plans) {
        // 先清空：即便模型幻觉出 learningPlanId 字段，也不采信——这个字段只能由后端写
        aiIntent.setLearningPlanId(null);
        Integer index = aiIntent.getLearningPlanIndex();
        if (index == null || index < 1 || index > plans.size()) {
            return;
        }
        aiIntent.setLearningPlanId(String.valueOf(plans.get(index - 1).getId()));
    }

    /**
     * 旧字段一致性兜底（P3，2026-10-01）：**taskGoals 是对的，就让旧字段跟着它走**。
     *
     * <p><b>为什么需要它</b>：P3 配对测量抓到一条 4/4 稳定复现的失败——"搜索一下 Redis 相关文章，
     * 再优化当前这篇"：{@code taskGoals=[ARTICLE_SEARCH, WORKFLOW_HANDOFF]} 完全正确，
     * 但旧字段被裁减后的 prompt 判成了 {@code intent=ARTICLE_SEARCH / suggestedWorkflowType=null}。
     * 而**真正启动 Workflow 的是旧字段**（5a 已冻结的语义）→ 这条请求不会启动优化 Workflow，
     * 是真实的功能退化。
     *
     * <p><b>为什么不改 prompt</b>：实测过——在候选 prompt 末尾加"旧字段必须跟着 WORKFLOW_HANDOFF 走"
     * 能修好这一条，却把"帮我充10块钱"的 taskGoals 从 {@code [WALLET_RECHARGE]} 推成
     * {@code [WORKFLOW_HANDOFF]}，硬门仍是 25/26。修一条坏一条，是打地鼠；
     * 而"旧字段该填什么"本来就能从 taskGoals 确定性推导，不该交给模型。
     *
     * <p><b>边界（刻意收窄，宁可不补也不乱补）</b>：
     * <ul>
     *   <li>只在 taskGoals 里**恰好一条** WORKFLOW_HANDOFF 时生效——两条及以上时旧字段本就该留空，
     *       各自的 workflowType 由 taskGoals 携带（这是 5a 已冻结的语义，不能被这里覆盖）；</li>
     *   <li>workflowType 必须是已登记的五个之一，缺失或模型自编的值一律不采信（不猜）；</li>
     *   <li>只补旧字段，**不碰 taskGoals**——它是事实来源，不是被修正的对象。</li>
     * </ul>
     */
    static void backfillWorkflowFields(AiIntent intent) {
        if (intent == null || intent.getTaskGoals() == null) {
            return;
        }
        int handoffCount = 0;
        String workflowType = null;
        for (Map<String, Object> goal : intent.getTaskGoals()) {
            if (goal == null || !"WORKFLOW_HANDOFF".equals(String.valueOf(goal.get("type")))) {
                continue;
            }
            handoffCount++;
            if (goal.get("params") instanceof Map<?, ?> params && params.get("workflowType") != null) {
                workflowType = String.valueOf(params.get("workflowType"));
            }
        }
        if (handoffCount != 1 || workflowType == null) {
            return;
        }
        String intentName = intentForWorkflowType(workflowType);
        if (intentName == null) {
            return;
        }
        intent.setSuggestedWorkflowType(workflowType);
        intent.setSuggestedAction("WORKFLOW");
        intent.setIntent(intentName);
    }

    /** workflowType → 旧字段的 intent 名；不在登记表里返回 null（不猜） */
    private static String intentForWorkflowType(String workflowType) {
        return switch (workflowType) {
            case "CREATE_ARTICLE" -> "CREATE_ARTICLE_WORKFLOW";
            case "OPTIMIZE_ARTICLE" -> "OPTIMIZE_ARTICLE_WORKFLOW";
            case "LEARNING_PLAN" -> "LEARNING_PLAN";
            case "LEARNING_PROGRESS" -> "LEARNING_PROGRESS";
            case "LEARNING_ASSIST" -> "LEARNING_ASSIST";
            default -> null;
        };
    }

    /**
     * 只读意图一致性收口（2026-10-18 生产事故）。
     *
     * <p><b>事故原样</b>：一句话被 plus 分类器同时判成
     * 「{@code intent=LEARNING_PLAN_QUERY / suggestedAction=TOOL}」（正确，走 dashboard 只读工具）
     * 和「{@code requestKind=TASK / taskGoals=[ARTICLE_SEARCH]}」（多余）。
     * 后者的执行器抢在旧链之前执行，用户看到的是"已全部完成：没有找到与「Redis学习计划」相关的文章"。
     *
     * <p><b>为什么不改 prompt 了事</b>：任务协议（{@code EXAMPLES_AND_SCHEMA}）一直是常装块，
     * 而学习域在 {@code taskGoals} 里根本没有可表达的类型——模型只能拿最像的
     * {@code ARTICLE_SEARCH} 去凑。这是协议层的结构缺口，不能靠"让模型别写"保证。
     * 所以后端按事实判：**只读意图不可能是任务**，冲突时丢弃 taskGoals、保留主意图。
     *
     * <p>丢弃是安全的：主意图仍在，由 Planner 的既有只读链执行；
     * 被丢弃的只是模型候选，不是用户目标（判据只拦不补）。
     */
    static void reconcileReadOnlyContract(AiIntent intent) {
        if (intent == null) {
            return;
        }
        String intentName = intent.getIntent() == null ? "" : intent.getIntent().trim();
        String action = intent.getSuggestedAction() == null ? "" : intent.getSuggestedAction().trim();
        boolean readOnly = "TOOL".equalsIgnoreCase(action)
                || "LEARNING_PLAN_QUERY".equalsIgnoreCase(intentName);
        if (!readOnly || intent.getTaskGoals() == null || intent.getTaskGoals().isEmpty()) {
            return;
        }
        List<String> goalTypes = taskGoalTypes(intent.getTaskGoals());
        log.warn("[CLASSIFIER] 只读意图携带任务目标，按事实丢弃 taskGoals: "
                        + "intent={}, action={}, taskGoalTypes={}",
                intent.getIntent(), intent.getSuggestedAction(), goalTypes);
        intent.setTaskGoals(null);
        intent.setRequestKind("CHAT");
    }

    /** 仅用于日志/诊断：taskGoals 的类型串（不参与任何判定） */
    static List<String> taskGoalTypes(List<Map<String, Object>> goals) {
        List<String> types = new ArrayList<>();
        if (goals == null) {
            return types;
        }
        for (Map<String, Object> goal : goals) {
            if (goal == null) {
                continue;
            }
            Object type = goal.get("type");
            if (type != null && !String.valueOf(type).isBlank()) {
                types.add(String.valueOf(type));
            }
        }
        return types;
    }

    private AiIntent tryParse(String json) {
        try {
            return objectMapper.readValue(cleanJson(json), AiIntent.class);
        } catch (Exception e) {
            log.warn("意图 JSON 解析失败，准备重试: {}", snippet(json));
            return null;
        }
    }

    private String snippet(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    /**
     * 分类器 system prompt。
     *
     * <p>可见性从 {@code private} 放开到**包私有 static**（2026-10-01，P3.0 切块）：
     * 它不依赖任何实例字段，而"按域切块"必须先有一份**逐字基线**可比对——
     * 拿不到 prompt 就只能靠人眼，而 500+ 行靠人眼必然出错。
     * 暴露范围刻意压到最小（同包测试可见），不进任何公开 API。
     */
    /**
     * 分类器 system prompt（P3.0 起改为**按片段拼装**）。
     *
     * <p>片段由脚本从切块前的原文逐行生成（{@link ClassifierPromptBlocks}），
     * 所以 FULL 拼装与切块前**逐字一致**——由 {@code ClassifierPromptBlocksTests} 锁定。
     * 切块本身不省 Token（单调用仍要全装），它的意义是让候选 A / B 能按需装载。
     *
     * <p>可见性从 {@code private} 放开到**包私有 static**：它不依赖任何实例字段，
     * 而"按域切块"必须先有一份逐字基线可比对——拿不到 prompt 就只能靠人眼，
     * 而 500+ 行靠人眼必然出错。暴露范围刻意压到最小（同包测试可见），不进公开 API。
     */
    static String buildSystemPrompt(boolean repair){
        String normalPrompt = String.join("\n",
                ClassifierPromptBlocks.HEAD_INTENT_LIST,
                ClassifierPromptBlocks.GENERAL_AND_PROFILE,
                ClassifierPromptBlocks.ARTICLE_CONTENT,
                ClassifierPromptBlocks.NAVIGATE,
                ClassifierPromptBlocks.SEARCH,
                ClassifierPromptBlocks.CREATE_ARTICLE,
                ClassifierPromptBlocks.LEARNING_AGENT,
                ClassifierPromptBlocks.SUGGEST_FIELDS,
                ClassifierPromptBlocks.NEEDS_THINKING,
                ClassifierPromptBlocks.RISK,
                ClassifierPromptBlocks.LEARNING_PLAN,
                ClassifierPromptBlocks.CONFIDENCE_MISC,
                ClassifierPromptBlocks.ARTICLE_OPTIMIZE,
                ClassifierPromptBlocks.PROJECT_CONSULT,
                ClassifierPromptBlocks.EXAMPLES_AND_SCHEMA,
                ClassifierPromptBlocks.PAGE_ACTION_RULES,
                ClassifierPromptBlocks.EDITOR,
                ClassifierPromptBlocks.WALLET,
                ClassifierPromptBlocks.OUTPUT_FORMAT);
        return repair ? normalPrompt + "\n\n" + ClassifierPromptBlocks.REPAIR : normalPrompt;
    }

    /**
     * 本次调用要用的 system prompt——**一键回退的落点**。
     *
     * <p>{@code blog.ai.classifier-mode=FULL}（默认）走现状 19 块全装；
     * {@code ON_DEMAND} 走候选 A 的三级按需装载。切换只影响这一处选择：
     * 调用链、解析、repair、计划定位、planId 解析、旧字段兜底全部不变——
     * 这是"改配置就能回退"能成立的前提，也是它比"改代码回滚"值得做的原因。
     */
    private String systemPrompt(boolean repair, String message, PageContextDTO pageContext) {
        return systemPromptFor(blogAiProperties.getClassifierMode(), repair, message,
                pageContext == null ? null : pageContext.getPageType());
    }

    /**
     * 包私有 static：把"模式 → prompt"的选择单独拎出来，好让它能**离线单测**（不启 Spring）。
     *
     * <p>开关的价值在于"出事时一定切得回去"，而"切得回去"是可测的：给 FULL 就必须拿到
     * 与切换前逐字相同的 prompt，给 ON_DEMAND 就必须走按需装载。
     */
    static String systemPromptFor(BlogAiProperties.ClassifierMode mode, boolean repair,
                                  String message, String pageType) {
        if (mode != BlogAiProperties.ClassifierMode.ON_DEMAND) {
            return buildSystemPrompt(repair);
        }
        String normalPrompt = CandidateA.assemble(message, pageType).prompt();
        return repair ? normalPrompt + "\n\n" + ClassifierPromptBlocks.REPAIR : normalPrompt;
    }

    private String buildUserPrompt(String message, PageContextDTO pageContext, List<LearningPlans> plans) {
        StringBuilder sb = new StringBuilder();
        sb.append("页面上下文：\n");

        if (pageContext == null) {
            sb.append("无\n");
        } else {
            sb.append("pageType=").append(pageContext.getPageType()).append("\n");
            sb.append("path=").append(pageContext.getPath()).append("\n");
            sb.append("articleId=").append(pageContext.getArticleId()).append("\n");
            sb.append("authorId=").append(pageContext.getAuthorId()).append("\n");
            sb.append("userId=").append(pageContext.getUserId()).append("\n");
        }

        // 该用户真实的 ACTIVE 计划（仅标题 + 序号，不给 ID）：模型据此输出 learningPlanIndex。
        // 只有"点名了计划"的消息才会带列表上来（见 needsPlanLocating）——普通聊天不含这一段。
        if (plans != null && !plans.isEmpty()) {
            sb.append("\n该用户的学习计划列表（按顺序编号）：\n");
            for (int i = 0; i < plans.size(); i++) {
                sb.append(i + 1).append(". ").append(plans.get(i).getTitle()).append("\n");
            }
        } else {
            // 显式说明"这次没有列表"：避免模型硬编序号，同时提醒它计划名/阶段名照常按原话摘录
            sb.append("\n（本次未提供学习计划列表，learningPlanIndex 输出 null；"
                    + "learningPlanRef / learningStageRef 仍按用户原话摘录）\n");
        }

        sb.append("\n用户问题：\n").append(message);
        return sb.toString();
    }

    private String cleanJson(String raw){
        if (raw == null) {
            return "{}";
        }
        return raw
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }

    private AiIntent generalChat() {
        AiIntent intent = new AiIntent();
        intent.setIntent("GENERAL_CHAT");
        intent.setConfidence(0.0);
        intent.setSuggestedAction("CHAT");
        intent.setSuggestedWorkflowType(null);
        intent.setRisk("LOW");
        intent.setReason("分类器调用失败，降级为普通聊天");
        intent.setNeedsThinking(false);
        intent.setNeedsThinkingReason("分类器调用失败，降级为普通聊天");
        return intent;
    }

}
