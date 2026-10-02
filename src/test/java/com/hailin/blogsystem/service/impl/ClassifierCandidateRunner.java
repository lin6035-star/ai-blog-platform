package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.ClassifierProbe;
import com.hailin.blogsystem.ai.LlmResponseSupport;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.LearningPlansService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.List;
import java.util.function.Function;

/**
 * 测试侧的**同构调用链**：与 {@link AiIntentClassifierImpl#classify} 的控制流逐条对齐，
 * 唯一变量是候选方案自己怎么组织 prompt 与调用次数。
 *
 * <p><b>为什么要复刻一遍</b>：P3 的硬要求是"生产链路一行不动"。若给生产类加一个 prompt 注入点，
 * 生产代码就多了一条只有 spike 才走的分支；若让 FULL 走生产、候选走测试侧，两侧的链路差异
 * 又会混进配对差值里。所以所有候选都走这条链路，**差值里只剩方案本身的差别**。
 *
 * <p><b>调用次数由候选决定</b>：A 是一次调用（{@link ClassifierCandidate#ON_DEMAND}），
 * B 是"路由 + 提取"两次（{@link ClassifierCandidate#ROUTE_EXTRACTOR}）。
 * 所以这里提供两层入口：{@link #callOnce}（标准 AiIntent 解析 + repair）与
 * {@link #call}（底层一次调用，给 schema 不是 AiIntent 的路由段用）。
 *
 * <p><b>复刻的代价要说清楚</b>：这里是副本，不是引用。生产 {@code AiIntentClassifierImpl} 的
 * 控制流一旦变动，本类必须同步——对应关系逐段标在下面。测出来的**绝对值**因此只在本 runner
 * 内部可比（两侧同链路），**不可直接与 {@code ClassifierP3BaselineTests.collectClassifierBaseline}
 * 的生产链路基线相减**；能相减的是配对差值。
 *
 * <p>对应用的生产方法（{@code AiIntentClassifierImpl}）：{@code classify} /
 * {@code callClassifier} / {@code callOnce} / {@code markFailure} / {@code markDegraded} /
 * {@code needsPlanLocating} / {@code loadActivePlans} / {@code tryParse} /
 * {@code buildUserPrompt} / {@code cleanJson} / {@code generalChat}。
 * {@code resolveLearningPlanId} 与 {@code backfillWorkflowFields} 是包私有 static，直接复用不复制。
 */
public class ClassifierCandidateRunner {

    private final ChatClient.Builder chatClientBuilder;
    private final ObjectMapper objectMapper;
    private final LearningPlansService learningPlansService;
    private final AiJudgeModelSupport aiJudgeModelSupport;

    public ClassifierCandidateRunner(ChatClient.Builder chatClientBuilder,
                                     ObjectMapper objectMapper,
                                     LearningPlansService learningPlansService,
                                     AiJudgeModelSupport aiJudgeModelSupport) {
        this.chatClientBuilder = chatClientBuilder;
        this.objectMapper = objectMapper;
        this.learningPlansService = learningPlansService;
        this.aiJudgeModelSupport = aiJudgeModelSupport;
    }

    /**
     * 一次完整分类（等价于生产 {@code classify(message, pageContext, userId, usage, probe)}）：
     * 候选自己决定调几次模型，这里负责计划定位、planId 解析与旧字段兜底。
     */
    public AiIntent run(ClassifierCandidate candidate, String message, PageContextDTO pageContext, Long userId,
                        TokenUsageAccumulator usage, ClassifierProbe probe) {
        AiIntent aiIntent = candidate.classifyOnce(this, message, pageContext, List.of(), usage, probe,
                ClassifierProbe.CallRole.FIRST_PASS);
        if (aiIntent == null || aiIntent.getIntent() == null || aiIntent.getIntent().isBlank()) {
            markDegraded(probe);
            return generalChat();
        }

        List<LearningPlans> plans = List.of();
        if (needsPlanLocating(aiIntent)) {
            plans = loadActivePlans(userId);
            if (!plans.isEmpty()) {
                AiIntent located = candidate.classifyOnce(this, message, pageContext, plans, usage, probe,
                        ClassifierProbe.CallRole.PLAN_LOCATOR);
                if (located != null && located.getIntent() != null && !located.getIntent().isBlank()) {
                    aiIntent = located;
                } else if (probe != null) {
                    probe.markOutcome(ClassifierProbe.Outcome.SUCCESS_WITH_PLAN_LOCATOR_FALLBACK,
                            "计划定位未命中，沿用首轮结果（" + probe.getDetail() + "）");
                }
            }
        }

        AiIntentClassifierImpl.resolveLearningPlanId(aiIntent, plans);
        // 与生产同一份兜底逻辑（static，直接复用而不是复制）——两侧都吃到它，
        // 所以测的是"分类器 + 后端兜底"这个完整方案，而不是半截
        AiIntentClassifierImpl.backfillWorkflowFields(aiIntent);
        return aiIntent;
    }

    /**
     * 供候选使用：一次完整分类调用（调用 → 解析 → 失败则 repair 一次），等价于生产的 {@code callOnce}。
     *
     * @param repairAwarePrompt 传入"是否为 repair 轮"，返回该轮要用的 system prompt
     */
    public AiIntent callOnce(Function<Boolean, String> repairAwarePrompt, String message,
                             PageContextDTO pageContextDTO, List<LearningPlans> plans,
                             TokenUsageAccumulator usage, ClassifierProbe probe, ClassifierProbe.CallRole role) {
        if (probe != null) {
            probe.recordRole(role);
        }
        String json = call(repairAwarePrompt.apply(false), message, pageContextDTO, plans, usage);
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
        String repaired = call(repairAwarePrompt.apply(true), message, pageContextDTO, plans, usage);
        if (probe != null && repaired != null) {
            probe.recordResponseChars(repaired.length());
        }
        if (repaired == null) {
            markFailure(probe, ClassifierProbe.Outcome.CALL_FAILED, "repair 调用未返回内容");
            return null;
        }
        AiIntent repairedIntent = tryParse(repaired);
        if (repairedIntent == null) {
            markFailure(probe, ClassifierProbe.Outcome.PARSE_FAILED, "首轮与 repair 均解析失败");
        }
        return repairedIntent;
    }

    /**
     * 底层一次调用：**不解析、不重试**。
     *
     * <p>给输出 schema 不是 {@code AiIntent} 的调用用——候选 B 的路由段要拿
     * {@code candidateDomains}，它没有对应的 DTO，只能自己解析。
     */
    public String call(String systemPrompt, String message, PageContextDTO pageContextDTO,
                       List<LearningPlans> plans, TokenUsageAccumulator usage) {
        try {
            ChatResponse response = chatClientBuilder.build()
                    .prompt()
                    .system(systemPrompt)
                    .user(buildUserPrompt(message, pageContextDTO, plans))
                    .options(aiJudgeModelSupport.applyTo(OpenAiChatOptions.builder()).build())
                    .call()
                    .chatResponse();
            TokenUsageAccumulator.addTo(usage, LlmResponseSupport.usageOf(response));
            return LlmResponseSupport.textOf(response);
        } catch (Exception e) {
            // 429 的重试由 Spring AI 的 RetryTemplate 负责（与生产同一条路径，生产此处刻意不自己重试）
            System.out.println("[P3-CAND][CALL-FAIL] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }

    /** 解析候选自定义 schema 的 JSON（B 的路由段用） */
    public <T> T parse(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(cleanJson(json), type);
        } catch (Exception e) {
            return null;
        }
    }

    private void markFailure(ClassifierProbe probe, ClassifierProbe.Outcome outcome, String detail) {
        if (probe != null) {
            probe.markOutcome(outcome, detail);
        }
    }

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

    private boolean needsPlanLocating(AiIntent intent) {
        String intentName = intent.getIntent();
        if (!"LEARNING_PROGRESS".equals(intentName) && !"LEARNING_ASSIST".equals(intentName)) {
            return false;
        }
        String planRef = intent.getLearningPlanRef();
        return planRef != null && !planRef.isBlank();
    }

    private List<LearningPlans> loadActivePlans(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            return learningPlansService.listActiveByUserCached(userId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private AiIntent tryParse(String json) {
        return parse(json, AiIntent.class);
    }

    private String cleanJson(String raw) {
        if (raw == null) {
            return "{}";
        }
        return raw
                .replace("```json", "")
                .replace("```", "")
                .trim();
    }

    /**
     * 与生产 {@code buildUserPrompt} 逐字一致的副本（见类注释的同步约定）。
     *
     * <p>可见性放开到包私有**只为让 {@code ClassifierCandidateRunnerTests} 拿反射比对生产实现**——
     * 复刻最大的风险是"生产改了、副本没改"，注释约定拦不住它，测试可以。
     */
    String buildUserPrompt(String message, PageContextDTO pageContext, List<LearningPlans> plans) {
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

        if (plans != null && !plans.isEmpty()) {
            sb.append("\n该用户的学习计划列表（按顺序编号）：\n");
            for (int i = 0; i < plans.size(); i++) {
                sb.append(i + 1).append(". ").append(plans.get(i).getTitle()).append("\n");
            }
        } else {
            sb.append("\n（本次未提供学习计划列表，learningPlanIndex 输出 null；"
                    + "learningPlanRef / learningStageRef 仍按用户原话摘录）\n");
        }

        sb.append("\n用户问题：\n").append(message);
        return sb.toString();
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
