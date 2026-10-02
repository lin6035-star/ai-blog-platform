package com.hailin.blogsystem.ai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.AiAgentStep;
import com.hailin.blogsystem.entity.dto.AiAgentRunStatus;
import com.hailin.blogsystem.entity.dto.AiAgentStepStatus;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                runMapper, stepMapper, objectMapper, transactionTemplate,
                new TaskPlanSnapshotCodec(objectMapper),
                new TaskRunStateTransitions(runMapper));
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

    // ==================== 5b：预创建、等待态与恢复快照 ====================

    @Test
    void preCreatesEveryPlanStepAsPendingBeforeExecution() {
        // 3 步计划、第 1 步就进等待态：后两步**仍然有行**，状态是 PENDING
        wireExecutors(Map.of(StepType.ARTICLE_WRITE,
                StepResult.waitingConfirm("s1", "需要确认")));

        execute(new TaskPlan("三步计划", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none()),
                step("s2", StepType.NAVIGATE, StepDependency.none()),
                step("s3", StepType.ARTICLE_SEARCH, StepDependency.none())
        ), List.of()));

        List<AiAgentStep> rows = stepsOf(latestRunId());
        // 5b 之前这里只有 1 行（只落"回调过的步骤"），"还没轮到"只能靠"查不到记录"反推；
        // 而恢复恰恰要靠这些行对齐，所以它们必须是明确的持久化事实
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(AiAgentStep::getTaskStepId).containsExactly("s1", "s2", "s3");
        assertThat(rows).extracting(AiAgentStep::getStepNo).containsExactly(1, 2, 3);
        assertThat(rows.get(0).getStatus()).isEqualTo(AiAgentStepStatus.WAITING_CONFIRM.name());
        assertThat(rows.get(1).getStatus()).isEqualTo(AiAgentStepStatus.PENDING.name());
        assertThat(rows.get(2).getStatus()).isEqualTo(AiAgentStepStatus.PENDING.name());
    }

    @Test
    void waitingStepIsNotRecordedAsFailed() {
        // 5b 之前的缺陷：toStepStatus 的 `default -> FAILED` 把"等用户确认"记成了失败，
        // inspection 与前端看到的语义是错的
        wireExecutors(Map.of(StepType.ARTICLE_WRITE,
                StepResult.waitingConfirm("s1", "需要确认是否隐藏")));

        execute(new TaskPlan("隐藏", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none())
        ), List.of()));

        assertThat(stepsOf(latestRunId()).get(0).getStatus())
                .isEqualTo(AiAgentStepStatus.WAITING_CONFIRM.name());
    }

    @Test
    void usedStepsCountsOnlyExecutedStepsNotPendingPlaceholders() {
        // 3 步计划停在第 1 步：used_steps 只算 1，max_steps 仍是 3，current_step 是**计划序号**
        wireExecutors(Map.of(StepType.ARTICLE_WRITE,
                StepResult.waitingConfirm("s1", "需要确认")));

        execute(new TaskPlan("三步计划", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none()),
                step("s2", StepType.NAVIGATE, StepDependency.none()),
                step("s3", StepType.ARTICLE_SEARCH, StepDependency.none())
        ), List.of()));

        AiAgentRun run = runMapper.selectById(latestRunId());
        assertThat(run.getMaxSteps()).isEqualTo(3);
        // 旧口径把 PENDING 占位也算进去，会写成 3——三个计数对不上，恢复时无法判断"跑到哪了"
        assertThat(run.getUsedSteps()).isEqualTo(1);
        assertThat(run.getCurrentStep()).isEqualTo(1);
    }

    @Test
    void runKeepsRecoverableTaskPlanSnapshot() throws Exception {
        execute(new TaskPlan("点赞并跳转", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none()),
                step("s2", StepType.NAVIGATE, StepDependency.after("s1"))
        ), List.of()));

        JsonNode snapshot = objectMapper.readTree(runMapper.selectById(latestRunId()).getTaskPlanJson());
        assertThat(snapshot.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(snapshot.get("plan").get("goal").asText()).isEqualTo("点赞并跳转");
        assertThat(snapshot.get("plan").get("steps")).hasSize(2);
        assertThat(snapshot.get("plan").get("steps").get(1).get("stepId").asText()).isEqualTo("s2");
        // 依赖必须一起存：恢复时要靠它判断"从哪继续、哪些该跳过"
        assertThat(snapshot.get("plan").get("steps").get(1).get("after").get(0).asText()).isEqualTo("s1");
        // anchor 字段始终存在（本次 pageContext 为 null，所以值是 null）
        assertThat(snapshot.get("anchor").has("articleId")).isTrue();
    }

    @Test
    void anchorComesFromTrustedPageContextNotFromModel() throws Exception {
        PageContextDTO page = new PageContextDTO();
        page.setPageType("article-detail");
        page.setArticleId("123");

        service.run(USER, SESSION, new TaskPlan("优化", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none())
        ), List.of()), page);
        createdRuns.add(latestRunId());

        JsonNode anchor = objectMapper.readTree(runMapper.selectById(latestRunId()).getTaskPlanJson())
                .get("anchor");
        // 锚点只能来自后端校验过的 PageContext——恢复时若拿模型的参数定位文章，
        // 等于让模型决定"改哪一篇"
        assertThat(anchor.get("pageType").asText()).isEqualTo("article-detail");
        assertThat(anchor.get("articleId").asText()).isEqualTo("123");
    }

    @Test
    void handoffWaitingMovesRootRunToWaitingWorkflow() {
        // 5b：接上 handoff 之后，"在等一个**已经创建**的 Workflow"必须有独立状态。
        // 停在 RUNNING 的话，进程重启时会被启动恢复当残留标成 FAILED——重启一次就判死了。
        wireExecutors(Map.of(StepType.WORKFLOW_HANDOFF,
                StepResult.waitingConfirm("s2", "优化流程在等你确认")));

        execute(new TaskPlan("点赞并优化", List.of(
                step("s2", StepType.WORKFLOW_HANDOFF, StepDependency.none())
        ), List.of()));

        assertThat(runMapper.selectById(latestRunId()).getStatus())
                .isEqualTo(AiAgentRunStatus.WAITING_WORKFLOW.name());
    }

    @Test
    void pausedHandoffPersistsStableMessageRoutingTokens() throws Exception {
        wireExecutors(Map.of(StepType.WORKFLOW_HANDOFF,
                StepResult.waitingConfirm("s2", "优化流程在等你确认")));
        TaskPlan plan = new TaskPlan("点赞并优化", List.of(
                step("s2", StepType.WORKFLOW_HANDOFF, StepDependency.none())
        ), List.of());

        OrchestrationOutcome outcome = execute(plan);
        service.recordMessageRoutingTokens(outcome.agentRunId(), plan, 500L);

        JsonNode output = objectMapper.readTree(stepsOf(outcome.agentRunId()).get(0).getOutputJson());
        assertThat(output.path("messageRoutingTokens").asLong()).isEqualTo(500L);
        assertThat(output.path("state").asText()).isEqualTo(StepState.WAITING_CONFIRM.name());
    }

    @Test
    void nonHandoffPauseStaysRunning() {
        // 其它暂停（等用户补充信息）保持原语义：状态越多，"谁在等我"越难一眼看清
        wireExecutors(Map.of(StepType.ARTICLE_WRITE,
                StepResult.waitingUser("s1", "需要你补充一点信息")));

        execute(new TaskPlan("点赞", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none())
        ), List.of()));

        assertThat(runMapper.selectById(latestRunId()).getStatus())
                .isEqualTo(AiAgentRunStatus.RUNNING.name());
    }

    @Test
    void infrastructureFailureMarksRunFailedInsteadOfCompleted() {
        TaskPlan plan = new TaskPlan("点赞", List.of(
                step("s1", StepType.ARTICLE_WRITE, StepDependency.none())
        ), List.of());

        assertThatThrownBy(() -> service.run(USER, SESSION, plan, null, (step, result) ->
                stepMapper.delete(new LambdaQueryWrapper<AiAgentStep>()
                        .eq(AiAgentStep::getAgentRunId, latestRunId())
                        .eq(AiAgentStep::getTaskStepId, step.stepId()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("步骤行不存在");

        Long runId = latestRunId();
        createdRuns.add(runId);
        AiAgentRun run = runMapper.selectById(runId);
        assertThat(run.getStatus())
                .as("基础设施异常不能谎报成已完成")
                .isEqualTo(AiAgentRunStatus.FAILED.name());
        assertThat(run.getErrorMessage()).contains("步骤行不存在");
    }
}
