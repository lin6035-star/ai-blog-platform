package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LearningAgentStateTests {

    @Test
    void firstDecisionHasNoExtraContext() {
        LearningAgentState state = new LearningAgentState("学习 Redis", 5);

        assertThat(state.decisionContext()).isEmpty();
    }

    @Test
    void dashboardResultIsDeduplicatedAndLatestFactWinsWithoutReplayingSummary() {
        LearningAgentState state = new LearningAgentState("查看计划", 5);
        AgentStepDecision decision = decision(AgentStepActionType.QUERY_LEARNING_DASHBOARD,
                Map.of("planRef", "Redis"));

        state.reduce(decision, AgentActionResult.of("旧计划结果：非常长的展示文本")
                .with("status", "SUCCESS")
                .with("planRef", "Redis")
                .with("planCount", 1)
                .with("activePlanCount", 2)
                .with("matchedCount", 1)
                .with("selectedPlanId", 11L));
        state.reduce(decision, AgentActionResult.of("新计划结果：非常长的展示文本")
                .with("status", "SUCCESS")
                .with("planRef", "Redis")
                .with("planCount", 1)
                .with("activePlanCount", 1)
                .with("matchedCount", 1)
                .with("selectedPlanId", 11L));

        assertThat(state.decisionContext())
                .contains("activePlanCount=1")
                .doesNotContain("旧计划结果")
                .doesNotContain("新计划结果");
    }

    @Test
    void failedMemoryDoesNotBecomeEvidence() {
        LearningAgentState state = new LearningAgentState("查我的偏好", 5);
        AgentStepDecision decision = decision(AgentStepActionType.QUERY_MEMORY,
                Map.of("question", "回答风格"));

        state.reduce(decision, AgentActionResult.of("记忆检索失败")
                .with("status", "FAILED")
                .with("query", "回答风格")
                .with("hitCount", 0));

        assertThat(state.verificationEvidence()).doesNotContain("记忆检索失败");
        assertThat(state.decisionContext()).contains("失败");
    }

    @Test
    void sameEmptyQueryIsNotRepeatedAsNewEvidence() {
        LearningAgentState state = new LearningAgentState("查文章知识", 5);
        AgentStepDecision decision = decision(AgentStepActionType.SEARCH_RAG,
                Map.of("keyword", "量子计算"));
        AgentActionResult empty = AgentActionResult.of("没有命中")
                .with("status", "SUCCESS")
                .with("keyword", "量子计算")
                .with("hitCount", 0);

        state.reduce(decision, empty);
        long firstRevision = state.evidenceRevision(Verdict.MissingEvidence.RAG);
        state.reduce(decision, empty);

        assertThat(state.evidenceRevision(Verdict.MissingEvidence.RAG)).isEqualTo(firstRevision);
        assertThat(state.lastStepHadNewEvidence()).isFalse();
        assertThat(state.decisionContext()).contains("站内知识（关键词无命中）");
        assertThat(state.decisionContext().split("站内知识（关键词无命中）", -1)).hasSize(2);
    }

    @Test
    void eachEvidenceSlotStaysWithinItsBudget() {
        LearningAgentState state = new LearningAgentState("查看学习计划", 5);
        for (int i = 0; i < 20; i++) {
            state.reduce(
                    decision(AgentStepActionType.QUERY_LEARNING_DASHBOARD,
                            Map.of("planRef", "计划" + i)),
                    AgentActionResult.of("不应进入状态的完整展示文本".repeat(80))
                            .with("status", "SUCCESS")
                            .with("planRef", "计划" + i)
                            .with("planCount", 20)
                            .with("activePlanCount", 10)
                            .with("matchedCount", 1)
                            .with("selectedPlanId", (long) i));
        }

        String context = state.decisionContext();
        int start = context.indexOf("【学习计划】");
        int end = context.indexOf("【", start + 1);

        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end - start).isLessThanOrEqualTo(1820);
        assertThat(context).doesNotContain("不应进入状态的完整展示文本");
    }

    private AgentStepDecision decision(AgentStepActionType actionType, Map<String, Object> input) {
        return new AgentStepDecision(actionType, input, null);
    }
}
