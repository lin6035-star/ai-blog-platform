package com.hailin.blogsystem.ai.task;

/**
 * 步骤结果码（第三刀）。
 *
 * <p>沿用第一刀的 {@code ArticleInteractionResultCode} 语义：程序判断只读 code，
 * **不解析 summary 的自然语言**。汇总文案由 {@link TaskResultRenderer} 按 code 渲染。
 *
 * <p>{@link #ALREADY_SATISFIED} 属于成功——"已经点赞过"是达成目标，不是失败。
 */
public enum StepCode {

    /** 动作已实际应用（状态发生了改变） */
    APPLIED,

    /** 目标状态本已满足，无需变更——属于成功 */
    ALREADY_SATISFIED,

    /** 目标不存在（文章 / 作者查不到） */
    TARGET_NOT_FOUND,

    /** 无权限（不是自己的文章、未登录等） */
    FORBIDDEN,

    /** 与同批次其他步骤冲突（如同时点赞和取消点赞） */
    CONFLICT,

    /** 系统异常 */
    INTERNAL_ERROR,

    /**
     * 分析类步骤得到了**经证据门放行的答案**（第四步，2026-09-30）。
     *
     * <p>不复用 {@link #APPLIED}：那个码的语义是「动作已实际应用（状态发生了改变）」，
     * 而分析是只读的，不改任何状态。
     */
    ANSWERED,

    /**
     * 证据不足，步骤**主动不硬答**（第四步）。
     *
     * <p>它既不是系统故障（{@link #INTERNAL_ERROR}），也不是达成目标——
     * 所以 {@code StepState} 落 {@code FAILED}，让汇总说「未完成」而不是「已完成」。
     * 对应子 run 的 {@code COMPLETED + CONSERVATIVE_SHUTDOWN}。
     */
    INSUFFICIENT_EVIDENCE,

    /**
     * 只拿到阶段性结果（例如步数到顶后的总结 / 拼接），**未证明完整达成目标**（第四步）。
     *
     * <p>内容可以展示给用户，但必须留在「部分结果 / 未完成」区域。
     * 对应子 run 的 {@code COMPLETED + MAX_STEPS_SUMMARY}。
     */
    INCOMPLETE_RESULT
}
