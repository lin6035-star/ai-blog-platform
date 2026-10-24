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

    /**
     * 2026-10-18 修：计划内容必须进决策上下文（Token 优化后 summary 不回放）。
     *
     * <p>事故：payload 只有元数据 → 模型回答"观察仅含计划元数据" → 重复查询被去重跳过。
     */
    @Test
    void dashboardPlanDetailFromPayloadReachesDecisionContextWithoutReplayingSummary() {
        LearningAgentState state = new LearningAgentState("分析我的Agent学习计划", 5);
        AgentStepDecision decision = decision(AgentStepActionType.QUERY_LEARNING_DASHBOARD,
                Map.of("planRef", "两个月 Agent 开发入门到实战计划"));

        state.reduce(decision, AgentActionResult.of("不应回放的展示文本")
                .with("status", "SUCCESS")
                .with("planRef", "两个月 Agent 开发入门到实战计划")
                .with("planCount", 9)
                .with("activePlanCount", 9)
                .with("matchedCount", 1)
                .with("selectedPlanId", 21L)
                .with("planDetail",
                        "目标计划《两个月 Agent 开发入门到实战计划》状态=ACTIVE\n"
                                + "总进度：0/31\n"
                                + "- 阶段《第1周：建立 Agent 与大模型应用基础》：[ ] 阅读站内文章；[ ] 完成最小调用；"));

        assertThat(state.decisionContext())
                .contains("计划内容：")
                .contains("总进度：0/31")
                .contains("第1周：建立 Agent 与大模型应用基础")
                .doesNotContain("不应回放的展示文本");
    }

    /** 点名未命中/歧义时，候选计划名也要进上下文——否则模型没法重试正确标题或向用户确认。 */
    @Test
    void dashboardPlanCandidatesFromPayloadReachDecisionContext() {
        LearningAgentState state = new LearningAgentState("查看学习计划", 5);
        AgentStepDecision decision = decision(AgentStepActionType.QUERY_LEARNING_DASHBOARD,
                Map.of("planRef", "RocketMQ"));

        state.reduce(decision, AgentActionResult.of("展示文本")
                .with("status", "SUCCESS")
                .with("planRef", "RocketMQ")
                .with("planCount", 2)
                .with("activePlanCount", 2)
                .with("matchedCount", 0)
                .with("selectedPlanId", null)
                .with("planCandidates", "1. 《Redis 学习计划》状态=ACTIVE\n2. 《Java 并发计划》状态=ACTIVE\n"));

        assertThat(state.decisionContext())
                .contains("候选计划：")
                .contains("Redis 学习计划")
                .contains("Java 并发计划");
    }

    /** 记忆与 RAG 同理：只给 hitCount 等于"知道有、看不到内容"。 */
    @Test
    void memoryAndRagContentFromPayloadReachDecisionContext() {
        LearningAgentState state = new LearningAgentState("分析计划", 5);

        state.reduce(decision(AgentStepActionType.QUERY_MEMORY, Map.of("question", "学习偏好")),
                AgentActionResult.of("记忆摘要")
                        .with("status", "SUCCESS")
                        .with("query", "学习偏好")
                        .with("hitCount", 1)
                        .with("memoryContent", "- 常驻记忆(PREFERENCE)：回答直接一点\n"));
        state.reduce(decision(AgentStepActionType.SEARCH_RAG, Map.of("keyword", "Agent 工具调用")),
                AgentActionResult.of("检索结果")
                        .with("status", "SUCCESS")
                        .with("keyword", "Agent 工具调用")
                        .with("hitCount", 1)
                        .with("ragContent", "1. 《Agent 工具调用实践》(7)：模型选择工具并调用\n"));

        assertThat(state.decisionContext())
                .contains("记忆内容：").contains("回答直接一点")
                .contains("检索片段：").contains("Agent 工具调用实践");
    }

    private AgentStepDecision decision(AgentStepActionType actionType, Map<String, Object> input) {
        return new AgentStepDecision(actionType, input, null);
    }
}
