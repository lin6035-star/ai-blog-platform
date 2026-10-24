package com.hailin.blogsystem.ai.task.plan;

import com.hailin.blogsystem.ai.task.TaskPlan;

/**
 * 任务路由决策（第三刀·2c）——{@link TaskPlanningFacade} 的输出，聊天入口据此分流。
 *
 * <p>把"走新路径 / 普通聊天 / 显式旧路径兼容 / 明确拒绝"做成一个显式四态，而不是让聊天入口散着一堆 if：
 * 判据属于编排层的知识。
 */
public record TaskRoute(
        Route route,
        TaskPlan plan,
        String reason
) {

    public enum Route {
        /** 计划里每一步都有执行器 → 交给 TaskOrchestrationService */
        ORCHESTRATE,
        /** 不是任务（普通问答 / 闲聊）→ 走原有聊天路径 */
        CHAT,
        /**
         * **显式**走旧兼容路径。
         *
         * <p>与"不认识的兜底"不是一回事：只有**确认旧路径能完整处理**时才用它——
         * 单任务未迁移（"分析这篇文章"），或未覆盖的只是 `ARTICLE_ANALYSIS`
         * （"点赞 + 分析"，第二刀已验证那个组合）。
         */
        LEGACY,
        /** 无法完整承接 → 给用户明确说明，**不执行半截** */
        CTA
    }

    public static TaskRoute orchestrate(TaskPlan plan) {
        return new TaskRoute(Route.ORCHESTRATE, plan, null);
    }

    public static TaskRoute chat(String reason) {
        return new TaskRoute(Route.CHAT, null, reason);
    }

    public static TaskRoute legacy(String reason) {
        return new TaskRoute(Route.LEGACY, null, reason);
    }

    public static TaskRoute cta(String reason) {
        return new TaskRoute(Route.CTA, null, reason);
    }

    public boolean isOrchestrate() {
        return route == Route.ORCHESTRATE;
    }
}
