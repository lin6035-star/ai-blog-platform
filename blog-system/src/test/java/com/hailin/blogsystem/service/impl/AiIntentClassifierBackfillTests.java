package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.entity.dto.AiIntent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧字段一致性兜底的**边界锁定**（离线，零成本）。
 *
 * <p>这个兜底的价值全在边界上：补得对，它把"模型漏填旧字段"这种失败变成不可能；
 * 补得宽，它会把 5a 已经冻结的语义（两条 Workflow 时旧字段留空）压成一个值，
 * 那才是真正的回归。所以下面每一条边界都要有断言。
 */
class AiIntentClassifierBackfillTests {

    @Test
    void backfillsLegacyFieldsWhenExactlyOneHandoff() {
        // 复现 P3 抓到的那条失败：taskGoals 对、旧字段判成了第一个目标
        AiIntent intent = intent("ARTICLE_SEARCH", "CHAT", null, List.of(goal("ARTICLE_SEARCH", null)));
        intent.getTaskGoals().add(goal("WORKFLOW_HANDOFF", "OPTIMIZE_ARTICLE"));

        AiIntentClassifierImpl.backfillWorkflowFields(intent);

        assertThat(intent.getSuggestedWorkflowType()).isEqualTo("OPTIMIZE_ARTICLE");
        assertThat(intent.getSuggestedAction()).isEqualTo("WORKFLOW");
        assertThat(intent.getIntent()).isEqualTo("OPTIMIZE_ARTICLE_WORKFLOW");
    }

    @Test
    void mapsEveryRegisteredWorkflowType() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("CREATE_ARTICLE", "CREATE_ARTICLE_WORKFLOW");
        expected.put("OPTIMIZE_ARTICLE", "OPTIMIZE_ARTICLE_WORKFLOW");
        expected.put("LEARNING_PLAN", "LEARNING_PLAN");
        expected.put("LEARNING_PROGRESS", "LEARNING_PROGRESS");
        expected.put("LEARNING_ASSIST", "LEARNING_ASSIST");

        expected.forEach((workflowType, intentName) -> {
            AiIntent intent = intent("GENERAL_CHAT", "CHAT", null, List.of(goal("WORKFLOW_HANDOFF", workflowType)));
            AiIntentClassifierImpl.backfillWorkflowFields(intent);
            assertThat(intent.getSuggestedWorkflowType()).as(workflowType).isEqualTo(workflowType);
            assertThat(intent.getIntent()).as(workflowType).isEqualTo(intentName);
            assertThat(intent.getSuggestedAction()).as(workflowType).isEqualTo("WORKFLOW");
        });
    }

    @Test
    void leavesLegacyFieldsAloneWhenTwoHandoffs() {
        // 5a 冻结语义：两条及以上 Workflow 时旧字段留空，各自的 workflowType 由 taskGoals 携带
        AiIntent intent = intent("GENERAL_CHAT", "CHAT", null,
                List.of(goal("WORKFLOW_HANDOFF", "OPTIMIZE_ARTICLE"),
                        goal("WORKFLOW_HANDOFF", "CREATE_ARTICLE")));

        AiIntentClassifierImpl.backfillWorkflowFields(intent);

        assertThat(intent.getSuggestedWorkflowType()).isNull();
        assertThat(intent.getSuggestedAction()).isEqualTo("CHAT");
        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
    }

    @Test
    void ignoresUnregisteredWorkflowType() {
        // 模型自编的 workflowType 不采信——宁可不补，也不要把编造的值写进真正启动 Workflow 的字段
        AiIntent intent = intent("GENERAL_CHAT", "CHAT", null, List.of(goal("WORKFLOW_HANDOFF", "MAGIC_FLOW")));

        AiIntentClassifierImpl.backfillWorkflowFields(intent);

        assertThat(intent.getSuggestedWorkflowType()).isNull();
        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
    }

    @Test
    void ignoresHandoffWithoutParams() {
        Map<String, Object> noParams = new LinkedHashMap<>();
        noParams.put("type", "WORKFLOW_HANDOFF");
        AiIntent intent = intent("GENERAL_CHAT", "CHAT", null, List.of(noParams));

        AiIntentClassifierImpl.backfillWorkflowFields(intent);

        assertThat(intent.getSuggestedWorkflowType()).isNull();
    }

    @Test
    void noopWhenNoTaskGoals() {
        AiIntent intent = intent("GENERAL_CHAT", "CHAT", null, List.of());
        AiIntentClassifierImpl.backfillWorkflowFields(intent);
        assertThat(intent.getSuggestedWorkflowType()).isNull();
        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");

        AiIntent noGoals = intent("GENERAL_CHAT", "CHAT", null, null);
        AiIntentClassifierImpl.backfillWorkflowFields(noGoals);
        assertThat(noGoals.getSuggestedWorkflowType()).isNull();

        AiIntentClassifierImpl.backfillWorkflowFields(null);
    }

    @Test
    void keepsAlreadyConsistentLegacyFields() {
        AiIntent intent = intent("OPTIMIZE_ARTICLE_WORKFLOW", "WORKFLOW", "OPTIMIZE_ARTICLE",
                List.of(goal("WORKFLOW_HANDOFF", "OPTIMIZE_ARTICLE")));

        AiIntentClassifierImpl.backfillWorkflowFields(intent);

        assertThat(intent.getSuggestedWorkflowType()).isEqualTo("OPTIMIZE_ARTICLE");
        assertThat(intent.getSuggestedAction()).isEqualTo("WORKFLOW");
        assertThat(intent.getIntent()).isEqualTo("OPTIMIZE_ARTICLE_WORKFLOW");
    }

    // ============ 只读意图一致性收口（2026-10-18 生产事故） ============

    /**
     * 事故原样：一次输出同时是「只读计划查询（TOOL）」和「TASK + 文章检索目标」。
     * 后端判事实：只读意图不可能是任务 → 丢弃 taskGoals、保留主意图。
     */
    @Test
    void dropsTaskGoalsWhenIntentIsReadOnlyToolQuery() {
        AiIntent intent = intent("LEARNING_PLAN_QUERY", "TOOL", null,
                List.of(goal("ARTICLE_SEARCH", null)));
        intent.setRequestKind("TASK");

        AiIntentClassifierImpl.reconcileReadOnlyContract(intent);

        assertThat(intent.getTaskGoals()).isNull();
        assertThat(intent.getRequestKind()).isEqualTo("CHAT");
        assertThat(intent.getIntent()).as("主意图必须保留——它是旧链执行的依据").isEqualTo("LEARNING_PLAN_QUERY");
        assertThat(intent.getSuggestedAction()).isEqualTo("TOOL");
    }

    /** 即使 suggestedAction 没写 TOOL，LEARNING_PLAN_QUERY 也属于只读意图。 */
    @Test
    void dropsTaskGoalsWhenIntentIsLearningPlanQueryRegardlessOfAction() {
        AiIntent intent = intent("LEARNING_PLAN_QUERY", "CHAT", null,
                List.of(goal("ARTICLE_SEARCH", null)));
        intent.setRequestKind("TASK");

        AiIntentClassifierImpl.reconcileReadOnlyContract(intent);

        assertThat(intent.getTaskGoals()).isNull();
        assertThat(intent.getRequestKind()).isEqualTo("CHAT");
    }

    /** 反例：文章域可执行意图必须保留 taskGoals（多目标编排依赖它）。 */
    @Test
    void keepsTaskGoalsForOrchestratableArticleIntent() {
        AiIntent intent = intent("ARTICLE_ACTION", "CHAT", null,
                List.of(goal("ARTICLE_WRITE", null)));
        intent.setRequestKind("TASK");

        AiIntentClassifierImpl.reconcileReadOnlyContract(intent);

        assertThat(intent.getTaskGoals()).hasSize(1);
        assertThat(intent.getRequestKind()).isEqualTo("TASK");
    }

    /** 反例：Workflow 意图的 taskGoals 是 5a/5b 的冻结契约，不能被误清。 */
    @Test
    void keepsTaskGoalsForWorkflowIntent() {
        AiIntent intent = intent("OPTIMIZE_ARTICLE_WORKFLOW", "WORKFLOW", "OPTIMIZE_ARTICLE",
                List.of(goal("WORKFLOW_HANDOFF", "OPTIMIZE_ARTICLE")));
        intent.setRequestKind("TASK");

        AiIntentClassifierImpl.reconcileReadOnlyContract(intent);

        assertThat(intent.getTaskGoals()).hasSize(1);
        assertThat(intent.getRequestKind()).isEqualTo("TASK");
    }

    @Test
    void reconcileIsNoopWithoutTaskGoals() {
        AiIntent readOnly = intent("LEARNING_PLAN_QUERY", "TOOL", null, null);
        AiIntentClassifierImpl.reconcileReadOnlyContract(readOnly);
        assertThat(readOnly.getTaskGoals()).isNull();

        AiIntent empty = intent("LEARNING_PLAN_QUERY", "TOOL", null, List.of());
        AiIntentClassifierImpl.reconcileReadOnlyContract(empty);
        assertThat(empty.getTaskGoals()).isEmpty();

        AiIntentClassifierImpl.reconcileReadOnlyContract(null);
    }

    private static AiIntent intent(String intentName, String suggestedAction, String suggestedWorkflowType,
                                   List<Map<String, Object>> goals) {
        AiIntent intent = new AiIntent();
        intent.setIntent(intentName);
        intent.setSuggestedAction(suggestedAction);
        intent.setSuggestedWorkflowType(suggestedWorkflowType);
        intent.setTaskGoals(goals == null ? null : new ArrayList<>(goals));
        return intent;
    }

    private static Map<String, Object> goal(String type, String workflowType) {
        Map<String, Object> goal = new LinkedHashMap<>();
        goal.put("type", type);
        if (workflowType != null) {
            goal.put("params", new LinkedHashMap<>(Map.of("workflowType", workflowType)));
        }
        return goal;
    }
}
