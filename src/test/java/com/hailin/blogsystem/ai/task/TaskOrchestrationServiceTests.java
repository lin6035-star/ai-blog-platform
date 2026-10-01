package com.hailin.blogsystem.ai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.mapper.AiAgentRunMapper;
import com.hailin.blogsystem.mapper.AiAgentStepMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编排落库测试（第三刀·2b）。
 *
 * <p>**真 mapper + 真事务，假执行器**：run/step 的落库形态是本步的核心，必须真实验证；
 * 但执行器用假的——测试不该真的去点赞（会写业务数据、也依赖 ES/Redis）。
 */
@SpringBootTest
class TaskOrchestrationServiceTests {

    private static final Long USER = 771L;
    private static final Long SESSION = 772L;

    @Autowired
    private AiAgentRunMapper runMapper;
    @Autowired
    private AiAgentStepMapper stepMapper;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<Long> createdRuns = new ArrayList<>();
    private TaskOrchestrationService service;

    /** 假执行器：固定返回，不碰业务数据 */
    private record FakeExecutor(StepType type, StepResult result) implements TaskStepExecutor {
        @Override
        public StepType supports() {
            return type;
        }

        @Override
        public StepResult execute(TaskExecutionContext context, TaskStep step) {
            return result;
        }
    }

    /** 按 StepType 指定返回值——按位置传参容易错位 */
    private void wireExecutors(Map<StepType, StepResult> byType) {
        List<TaskStepExecutor> executors = new ArrayList<>();
        byType.forEach((type, result) -> executors.add(new FakeExecutor(type, result)));
        service = new TaskOrchestrationService(
                new TaskOrchestrator(new TaskStepExecutorRegistry(executors)),
                runMapper, stepMapper, objectMapper, transactionTemplate);
    }

    private static Map<StepType, StepResult> happyPath() {
        Map<StepType, StepResult> map = new LinkedHashMap<>();
        map.put(StepType.ARTICLE_WRITE, StepResult.applied("s1", "点赞成功", Map.of()));
        map.put(StepType.NAVIGATE, StepResult.dispatched("s2", "跳转到作者主页", Map.of()));
        return map;
    }

    @BeforeEach
    void setUp() {
        wireExecutors(happyPath());
    }

    @AfterEach
    void tearDown() {
        for (Long runId : createdRuns) {
            stepMapper.delete(new LambdaQueryWrapper<AiAgentStep>()
                    .eq(AiAgentStep::getAgentRunId, runId));
            runMapper.deleteById(runId);
        }
        createdRuns.clear();
    }

    private TaskStep step(String id, StepType type, StepDependency dependency) {
        return new TaskStep(id, type, Map.of("action", "likeArticle"), dependency, List.of("原话片段"));
    }

    /** 执行并登记 runId，供断言与清理 */
    private OrchestrationOutcome execute(TaskPlan plan) {
        OrchestrationOutcome outcome = service.run(USER, SESSION, plan, null);
        createdRuns.add(latestRunId());
        return outcome;
    }

    private Long latestRunId() {
        AiAgentRun latest = runMapper.selectOne(new LambdaQueryWrapper<AiAgentRun>()
                .eq(AiAgentRun::getUserId, USER)
                .orderByDesc(AiAgentRun::getId)
                .last("LIMIT 1"));
        return latest == null ? null : latest.getId();
    }

    private List<AiAgentStep> stepsOf(Long runId) {
        return stepMapper.selectList(new LambdaQueryWrapper<AiAgentStep>()
                .eq(AiAgentStep::getAgentRunId, runId)
                .orderByAsc(AiAgentStep::getStepNo));
    }

    // ==================== 落库形态 ====================

    @Test
    void createsRunAndOneStepPerExecutedStep() throws Exception {
        execute(new TaskPlan("点赞这篇文章，再跳转到作者主页", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none()),
                step("s2", StepType.NAVIGATE, StepDependency.after("s1"))
        ), List.of()));

        Long runId = latestRunId();
        AiAgentRun run = runMapper.selectById(runId);
        assertThat(run.getStatus()).isEqualTo(AiAgentRunStatus.COMPLETED.name());
        assertThat(run.getMaxSteps()).isEqualTo(2);
        assertThat(run.getUsedSteps()).isEqualTo(2);

        List<AiAgentStep> steps = stepsOf(runId);
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getStepNo()).isEqualTo(1);
        assertThat(steps.get(1).getStepNo()).isEqualTo(2);
        // 统一用 TASK_STEP 标识，真实类型在 inputJson.type
        assertThat(steps.get(0).getActionType()).isEqualTo("TASK_STEP");
        JsonNode input = objectMapper.readTree(steps.get(0).getInputJson());
        assertThat(input.path("type").asText()).isEqualTo("ARTICLE_WRITE");
        assertThat(input.path("stepId").asText()).isEqualTo("s1");
        assertThat(objectMapper.readTree(steps.get(0).getOutputJson())
                .path("state").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void failedStepIsRecordedAsFailedWithReason() {
        wireExecutors(Map.of(StepType.ARTICLE_WRITE,
                StepResult.failed("s1", StepCode.TARGET_NOT_FOUND, "没有找到这篇文章")));

        execute(new TaskPlan("点赞", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none())
        ), List.of()));

        List<AiAgentStep> steps = stepsOf(latestRunId());
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(steps.get(0).getErrorMessage()).contains("没有找到这篇文章");
    }

    @Test
    void dispatchedStepIsRecordedAsSuccessButKeepsRealState() {
        wireExecutors(Map.of(StepType.NAVIGATE,
                StepResult.dispatched("s1", "跳转到作者主页", Map.of("target", "userProfile"))));

        execute(new TaskPlan("跳转", List.of(
                step("s1", StepType.NAVIGATE, StepDependency.none())
        ), List.of()));

        AiAgentStep step = stepsOf(latestRunId()).get(0);
        // step 表记 SUCCESS（执行侧没出错），但 outputJson 保留 DISPATCHED 的真实语义
        assertThat(step.getStatus()).isEqualTo("SUCCESS");
        assertThat(step.getOutputJson()).contains("DISPATCHED");
    }

    @Test
    void skippedStepIsRecordedAsSkippedNotFailed() {
        wireExecutors(Map.of(StepType.NAVIGATE,
                StepResult.skipped("s1", "没有定位到当前文章，无法确定作者")));

        execute(new TaskPlan("跳转", List.of(
                step("s1", StepType.NAVIGATE, StepDependency.none())
        ), List.of()));

        AiAgentStep step = stepsOf(latestRunId()).get(0);
        // 没机会执行 ≠ 执行失败
        assertThat(step.getStatus()).isEqualTo("SKIPPED");
        assertThat(step.getStatus()).isNotEqualTo("FAILED");
    }

    @Test
    void pausedOutcomeLeavesRunRunning() {
        wireExecutors(Map.of(StepType.ARTICLE_WRITE,
                StepResult.waitingConfirm("s1", "需要确认是否隐藏")));

        OrchestrationOutcome outcome = execute(new TaskPlan("隐藏", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none())
        ), List.of()));

        assertThat(outcome.paused()).isTrue();
        // 暂停不是完成：run 停在 RUNNING，等后续能力处理（第一版不自动续跑）
        assertThat(runMapper.selectById(latestRunId()).getStatus())
                .isEqualTo(AiAgentRunStatus.RUNNING.name());
    }
}
