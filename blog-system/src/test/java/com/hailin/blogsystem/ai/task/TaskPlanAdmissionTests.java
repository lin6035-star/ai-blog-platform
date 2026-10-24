package com.hailin.blogsystem.ai.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这份计划要不要过 AI 长任务准入」的判据测试（第四步·阶段 0，2026-09-30）。
 *
 * <p><b>锁的是什么</b>：只有含**长 LLM 步骤**的计划才该占「8 在跑 + 32 排队」的长任务池与每用户名额。
 * 纯确定性计划（写动作 / 检索 / 客户端导航）都是秒级完成，若也进池，
 * 等于让几个点赞请求把真正需要几十秒的 Agent 任务挤出队列。
 *
 * <p>反向也要锁：含分析的混合计划**必须**进池——「点赞 + 分析」里写副作用不能发生在准入之前，
 * 否则会出现「点赞已提交、分析却被 429 拒掉」的半执行窗口。
 */
class TaskPlanAdmissionTests {

    @Test
    @DisplayName("只有长 LLM 步骤才算长任务：分析 / Workflow 黑盒是，确定性步骤不是")
    void onlyLongRunningLlmStepsNeedAdmission() {
        assertThat(StepType.ARTICLE_ANALYSIS.isLongRunningLlm()).isTrue();
        // 尚未接入，但按语义提前定死——接入时不必回头改这条判据
        assertThat(StepType.WORKFLOW_HANDOFF.isLongRunningLlm()).isTrue();

        assertThat(StepType.ARTICLE_WRITE.isLongRunningLlm()).isFalse();
        assertThat(StepType.ARTICLE_SEARCH.isLongRunningLlm()).isFalse();
        assertThat(StepType.NAVIGATE.isLongRunningLlm()).isFalse();
        assertThat(StepType.ARTICLE_QUERY.isLongRunningLlm()).isFalse();
    }

    @Test
    @DisplayName("纯确定性计划不进长任务池（点赞 + 跳转 / 点赞 + 搜索）")
    void deterministicPlanSkipsAdmission() {
        assertThat(planOf(StepType.ARTICLE_WRITE, StepType.NAVIGATE).requiresLongTaskAdmission()).isFalse();
        assertThat(planOf(StepType.ARTICLE_WRITE, StepType.ARTICLE_SEARCH).requiresLongTaskAdmission()).isFalse();
        assertThat(planOf(StepType.ARTICLE_WRITE).requiresLongTaskAdmission()).isFalse();
    }

    @Test
    @DisplayName("含分析的计划必须进池——混合计划里写副作用不能发生在准入之前")
    void planWithAnalysisNeedsAdmission() {
        assertThat(planOf(StepType.ARTICLE_WRITE, StepType.ARTICLE_ANALYSIS).requiresLongTaskAdmission()).isTrue();
        assertThat(planOf(StepType.ARTICLE_ANALYSIS).requiresLongTaskAdmission()).isTrue();
    }

    private TaskPlan planOf(StepType... types) {
        List<TaskStep> steps = new java.util.ArrayList<>();
        for (int i = 0; i < types.length; i++) {
            steps.add(new TaskStep(
                    "s" + (i + 1),
                    types[i],
                    Map.of(),
                    new StepDependency(List.of(), List.of(), OnFailure.CONTINUE),
                    List.of("证据" + (i + 1))));
        }
        return new TaskPlan("测试请求", steps, List.of(), List.of());
    }
}
