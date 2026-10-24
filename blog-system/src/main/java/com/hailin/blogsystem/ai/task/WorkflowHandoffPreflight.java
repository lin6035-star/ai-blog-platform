package com.hailin.blogsystem.ai.task;

import com.hailin.blogsystem.entity.AiSessions;
import com.hailin.blogsystem.entity.AiWorkflowRun;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.mapper.AiSessionMapper;
import com.hailin.blogsystem.mapper.AiWorkflowRunMapper;
import com.hailin.blogsystem.ai.workflow.WorkflowStatusSupport;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 5b 的**执行前事实校验**（设计稿 §7.1）。
 *
 * <p><b>为什么不能把校验放进 handoff 执行器</b>：执行器是**按步骤顺序**被调用的，等轮到它时，
 * 排在它前面的低风险写步骤（点赞 / 收藏）**已经执行过了**。于是"点赞这篇文章，再帮我优化一下"
 * 会先点赞、再发现文章不是自己的或会话已有活跃 Workflow——产生一次本可避免的部分执行。
 * 所以这些**可提前知道的事实**必须在任何步骤执行之前问一次。
 *
 * <p><b>为什么落在这里而不是编排服务里</b>：它需要 {@code pageContext}（可信锚点）+ {@code userId}
 * （归属）+ {@code sessionId}（会话绑定），而这三样恰好都在聊天链路的装配点上；
 * 编排服务（{@code TaskOrchestrationService}）只负责持久化，不该承担业务事实校验。
 *
 * <p><b>返回值是给用户看的文案</b>（走 CTA 分支），所以刻意写得像人话；内部细节只进日志。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowHandoffPreflight {

    private final ArticlesService articlesService;
    private final AiSessionMapper sessionMapper;
    private final AiWorkflowRunMapper workflowRunMapper;
    private final WorkflowStatusSupport workflowStatusSupport;

    /**
     * @return {@code null} = 放行；非 null = 给用户的拒绝原因（调用方走 CTA）
     */
    public String reject(Long userId, Long sessionId, PageContextDTO pageContext, TaskPlan plan) {
        if (plan == null || plan.steps() == null) {
            return null;
        }
        TaskStep handoff = plan.steps().stream()
                .filter(step -> step.type() == StepType.WORKFLOW_HANDOFF)
                .findFirst()
                .orElse(null);
        if (handoff == null) {
            return null;   // 与 5b 无关的普通计划，不拦
        }

        // ① 白名单：规划期已校验一次，这里防"绕过 Planner 直接构造计划"的调用方
        String workflowType = stringParam(handoff, "workflowType");
        if (!WorkflowHandoffTypes.isSupported(workflowType)) {
            log.info("[TASK-PREFLIGHT] workflowType 不在 5b 白名单: type={}", workflowType);
            return "这个流程我暂时还接不了。";
        }

        // ② 锚点：只认后端校验过的页面上下文，**绝不**取分类器参数——
        //    否则等于让模型决定"改哪一篇"
        Long articleId = parseArticleId(pageContext);
        if (articleId == null) {
            return "没能确定要优化哪篇文章，打开文章详情页再说一次吧。";
        }

        // ③ 归属：这条必须在任何低风险写步骤之前问
        Articles article = articlesService.getById(articleId);
        if (article == null) {
            return "这篇文章不存在或已删除。";
        }
        if (!Objects.equals(userId, article.getAuthorId())) {
            return "只能优化自己的文章。";
        }

        // ④ 会话没有未完成的 Workflow：第二道关卡在创建时（checkActiveWorkflowConflict），
        //    这里提前拦，是为了不出现"点赞成功了、优化却没跑起来"的部分执行。
        if (hasActiveWorkflow(sessionId)) {
            return "当前会话还有一个没完成的流程，先把它处理完再试。";
        }
        return null;
    }

    /**
     * 会话上是否挂着未完成的 Workflow。
     *
     * <p>口径刻意与 {@code WorkflowRunManager.checkActiveWorkflowConflict} 保持一致
     * （终态包含 FAILED）：5b 确实要给"关联外层任务、父 run 仍在等待的 FAILED Workflow"开窄例外，
     * 但那个例外属于**冲突入口**（设计稿 §6.3）——在第二处再实现一套谓词，
     * 就等于把同一个判断拆成两份，迟早对不上。
     */
    private boolean hasActiveWorkflow(Long sessionId) {
        if (sessionId == null) {
            return false;
        }
        AiSessions session = sessionMapper.selectById(sessionId);
        if (session == null || session.getActiveWorkflowRunId() == null) {
            return false;
        }
        AiWorkflowRun run = workflowRunMapper.selectById(session.getActiveWorkflowRunId());
        return run != null && !workflowStatusSupport.isFinished(run.getStatus());
    }

    private static String stringParam(TaskStep step, String key) {
        Object value = step.input() == null ? null : step.input().get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 页面文章 id 是字符串，解析失败一律当"没有锚点"——不猜、不降级 */
    private static Long parseArticleId(PageContextDTO pageContext) {
        if (pageContext == null || pageContext.getArticleId() == null) {
            return null;
        }
        try {
            return Long.valueOf(pageContext.getArticleId().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
