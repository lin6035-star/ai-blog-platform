package com.hailin.blogsystem.ai.task.executor;

import com.hailin.blogsystem.ai.task.StepDependency;
import com.hailin.blogsystem.ai.task.StepResult;
import com.hailin.blogsystem.ai.task.StepState;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.TaskExecutionContext;
import com.hailin.blogsystem.ai.task.TaskPlan;
import com.hailin.blogsystem.ai.task.TaskStep;
import com.hailin.blogsystem.ai.task.TaskStepEventSink;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.dto.AiWorkflowOptimizeArticleDTO;
import com.hailin.blogsystem.entity.dto.AiWorkflowStatus;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.service.AiWorkflowRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code WORKFLOW_HANDOFF} 执行器（5b 阶段 1）。
 *
 * <p>重点锁三件事：**父关联传对了**（否则恢复巡检找不到这条 Workflow）、
 * **重试不会创建第二条**、以及 **Workflow FAILED 映射成"可重试的等待"而不是永久失败**
 * （设计稿 §6.3 —— 这一条搞错，用户"重试一次就好"的路就被堵死了）。
 */
class WorkflowHandoffExecutorTests {

    private static final Long USER = 901L;
    private static final Long SESSION = 902L;
    private static final Long TASK_RUN = 903L;
    private static final Long WORKFLOW_RUN_ID = 555L;
    private static final String STEP_ID = "s2";

    private AiWorkflowRunService workflowRunService;
    private AiWorkflowRunMapper workflowRunMapper;
    private WorkflowHandoffExecutor executor;

    @BeforeEach
    void setUp() {
        workflowRunService = mock(AiWorkflowRunService.class);
        workflowRunMapper = mock(AiWorkflowRunMapper.class);
        executor = new WorkflowHandoffExecutor(workflowRunService, workflowRunMapper);
    }

    @Test
    @DisplayName("创建并关联后，Workflow 在等确认 → 外层步骤进 WAITING_CONFIRM")
    void createsLinkedWorkflowAndWaitsForConfirm() {
        // 第一次查（创建前）没有 → 创建 → 第二次查拿到
        when(workflowRunMapper.selectOne(any()))
                .thenReturn(null, workflowRun(AiWorkflowStatus.WAITING_PLAN_CONFIRM));

        StepResult result = executor.execute(context(page()), step("OPTIMIZE_ARTICLE"));

        assertThat(result.state()).isEqualTo(StepState.WAITING_CONFIRM);

        ArgumentCaptor<AiWorkflowOptimizeArticleDTO> captor =
                ArgumentCaptor.forClass(AiWorkflowOptimizeArticleDTO.class);
        // 5b：执行器现在传 emitter（把 Workflow 步骤事件转发到外层 SSE），所以是双参重载
        verify(workflowRunService).createArticleOptimizeWorkflow(captor.capture(), any());
        AiWorkflowOptimizeArticleDTO dto = captor.getValue();
        // 父关联必须随创建一起带上：Service 会在 Workflow 首次 save 之前写进 run，
        // 创建后再补会留下"Workflow 存在却没挂到外层步骤"的孤儿窗口
        assertThat(dto.getTaskRunId()).isEqualTo(TASK_RUN);
        assertThat(dto.getTaskStepId()).isEqualTo(STEP_ID);
        // 锚点来自 pageContext，不是模型参数
        assertThat(dto.getArticleId()).isEqualTo(42L);
        // 指令用用户原话（5a 协议不允许把 topic 塞进 params，需求本身由 Workflow 自己分析）
        assertThat(dto.getInstruction()).isEqualTo("点赞这篇文章，再帮我优化一下");
    }

    @Test
    @DisplayName("已有父关联的 Workflow → 不再创建第二条")
    void doesNotCreateSecondWorkflowWhenAlreadyLinked() {
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun(AiWorkflowStatus.WAITING_PLAN_CONFIRM));

        executor.execute(context(page()), step("OPTIMIZE_ARTICLE"));

        verify(workflowRunService, never()).createArticleOptimizeWorkflow(any(), any());
    }

    @Test
    @DisplayName("Workflow FAILED → WAITING_USER（可重试），不是永久失败")
    void failedWorkflowBecomesWaitingUserSoItCanBeRetried() {
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun(AiWorkflowStatus.FAILED));

        StepResult result = executor.execute(context(page()), step("OPTIMIZE_ARTICLE"));

        assertThat(result.state())
                .as("FAILED 对外层是「可重试故障态」——判成 FAILED 会把 retry 的路堵死")
                .isEqualTo(StepState.WAITING_USER);
    }

    @Test
    @DisplayName("终态映射：COMPLETED → 成功，CANCELLED → 失败")
    void mapsTerminalStatuses() {
        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun(AiWorkflowStatus.COMPLETED));
        assertThat(executor.execute(context(page()), step("OPTIMIZE_ARTICLE")).state())
                .isEqualTo(StepState.SUCCEEDED);

        when(workflowRunMapper.selectOne(any())).thenReturn(workflowRun(AiWorkflowStatus.CANCELLED));
        assertThat(executor.execute(context(page()), step("OPTIMIZE_ARTICLE")).state())
                .as("用户明确放弃该步骤，如实失败，由 onFailure 决定后续")
                .isEqualTo(StepState.FAILED);
    }

    @Test
    @DisplayName("白名单外的 workflowType → 拒掉，且不碰 Workflow 服务")
    void rejectsWorkflowTypeOutsideWhitelist() {
        StepResult result = executor.execute(context(page()), step("CREATE_ARTICLE"));

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        verifyNoInteractions(workflowRunService);
    }

    @Test
    @DisplayName("页面没有可信文章锚点 → 失败，不猜")
    void failsWhenNoTrustedAnchor() {
        PageContextDTO page = new PageContextDTO();
        page.setPageType("home");   // 没有 articleId

        StepResult result = executor.execute(context(page), step("OPTIMIZE_ARTICLE"));

        assertThat(result.state()).isEqualTo(StepState.FAILED);
        verifyNoInteractions(workflowRunService);
    }

    private TaskExecutionContext context(PageContextDTO page) {
        return new TaskExecutionContext(USER, SESSION, page, plan(), TASK_RUN, TaskStepEventSink.noop());
    }

    private static PageContextDTO page() {
        PageContextDTO page = new PageContextDTO();
        page.setPageType("article-detail");
        page.setArticleId("42");
        return page;
    }

    private static TaskPlan plan() {
        return new TaskPlan("点赞这篇文章，再帮我优化一下",
                List.of(step("OPTIMIZE_ARTICLE")), List.of());
    }

    private static TaskStep step(String workflowType) {
        return new TaskStep(STEP_ID, StepType.WORKFLOW_HANDOFF,
                Map.of("workflowType", workflowType), StepDependency.none(), List.of("帮我优化一下"));
    }

    private static AiWorkflowRun workflowRun(AiWorkflowStatus status) {
        AiWorkflowRun run = new AiWorkflowRun();
        run.setId(WORKFLOW_RUN_ID);
        run.setWorkflowType("OPTIMIZE_ARTICLE");
        run.setStatus(status.name());
        return run;
    }
}
