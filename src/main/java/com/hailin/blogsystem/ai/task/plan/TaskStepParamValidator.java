package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.LogValues;
import com.hailin.blogsystem.ai.task.StepType;
import com.hailin.blogsystem.ai.task.executor.NavigateExecutor;
import com.hailin.blogsystem.entity.dto.ArticleInteractionAction;

import java.util.Map;

/**
 * 步骤参数的二级校验（C0.1，2026-10-01）。
 *
 * <p><b>为什么必须有它</b>：分类器 prompt 已规定 {@code ARTICLE_WRITE} 只承接六项低风险动作，
 * 但弱模型仍会越界输出——页面手测实测把"修改标题"拆成
 * {@code ARTICLE_WRITE(action=changeTitle)}，一路进到执行器才被拒。
 * 那已经不是"校验"，而是"执行失败"，而且失败文案还把内部值吐给了用户。
 * 此前 {@code TaskPlanner} 只校验外层 {@code StepType} 与执行器是否存在。
 *
 * <p><b>校验粒度</b>：只校验**执行器真正依赖的键**，不做"所有未知字段一律拒绝"的全字段白名单。
 * 执行器对缺失参数本来就有默认值，全字段白名单会让校验比执行还严——用"安全"换"误杀"。
 * 这里要防的是**越界值**，不是未知键。
 *
 * <p><b>返回值只进内部日志</b>：它含模型给的原始值，绝不能进用户回复（脱敏见 C0.2）。
 */
final class TaskStepParamValidator {

    private TaskStepParamValidator() {
    }

    /**
     * @param params 候选目标给的参数（可为 null）
     * @return {@code null} = 通过；非 null = 不通过原因（含单行限长后的原始值，**仅供日志**）
     */
    static String validate(StepType type, Map<String, Object> params) {
        if (type == null) {
            return "步骤类型为空";
        }
        Map<String, Object> safe = params == null ? Map.of() : params;
        return switch (type) {
            case ARTICLE_WRITE -> validateArticleWrite(safe);
            case ARTICLE_SEARCH -> validateArticleSearch(safe);
            case NAVIGATE -> validateNavigate(safe);
            // 只读 / 分析类没有必填参数；未接入的类型由 registry.supports 拦在前面
            case ARTICLE_QUERY, ARTICLE_ANALYSIS, WORKFLOW_HANDOFF -> null;
        };
    }

    private static String validateArticleWrite(Map<String, Object> params) {
        String action = text(params, "action");
        if (action == null || action.isBlank()) {
            return "缺少 action";
        }
        if (ArticleInteractionAction.fromClassifierValue(action).isEmpty()) {
            return "action 不在文章交互白名单内（原值 " + safeForLog(action) + "）";
        }
        return null;
    }

    private static String validateArticleSearch(Map<String, Object> params) {
        String query = text(params, "query");
        if (query == null || query.isBlank()) {
            return "缺少 query";
        }
        return null;
    }

    private static String validateNavigate(Map<String, Object> params) {
        String target = text(params, "target");
        if (target == null || target.isBlank()) {
            return "缺少 target";
        }
        // 第一版只允许执行器**实际支持**的目标，不能复用全局导航的七类白名单——
        // 那是前端能力清单，不是这个执行器的能力（校验严于执行同样会造成误杀）
        if (!NavigateExecutor.TARGET_USER_PROFILE.equals(target)) {
            return "target 不在导航白名单内（原值 " + safeForLog(target) + "）";
        }
        return null;
    }

    private static String text(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    /** 日志安全化：单行 + 限长（C0.2 起统一委托 {@link LogValues}，不再各写一份） */
    private static String safeForLog(String raw) {
        return LogValues.safe(raw);
    }
}
