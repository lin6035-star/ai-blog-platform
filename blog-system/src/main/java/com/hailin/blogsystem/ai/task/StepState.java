package com.hailin.blogsystem.ai.task;

/**
 * 任务步骤状态（第三刀）。
 *
 * <p>两类容易混淆的语义在这里被刻意分开：
 * <ul>
 *   <li>{@link #DISPATCHED}：客户端步骤由前端执行，**后端不知道它有没有成功**，只能算「已发起」。
 *       这不是 SUCCEEDED，汇总里必须说"已发起"而不是"已完成"。</li>
 *   <li>{@link #SKIPPED}：前置条件没满足（{@code requires} 拿不到数据），**不是失败**——
 *       它没机会执行。汇总里必须说明为什么跳过。</li>
 * </ul>
 *
 * <p>{@link #WAITING_CONFIRM} / {@link #WAITING_USER} 不是失败也不是完成：本步停在等待态，
 * 整个 TaskPlan 暂停，后续步骤保持 {@link #PENDING} 不继续。
 */
public enum StepState {

    /** 尚未轮到（前置未满足或计划已暂停） */
    PENDING,

    /** 正在执行 */
    RUNNING,

    /** 后端拿到了真实结果并已应用 */
    SUCCEEDED,

    /** 执行了但失败（对象不存在 / 无权限 / 冲突 / 内部错误） */
    FAILED,

    /** 前置条件未满足，**没有执行**——不是失败 */
    SKIPPED,

    /** 客户端步骤已发起，**后端不知道结果**——不是 SUCCEEDED */
    DISPATCHED,

    /** 本步需要用户确认，整个 TaskPlan 暂停 */
    WAITING_CONFIRM,

    /** 本步需要用户补充信息，整个 TaskPlan 暂停 */
    WAITING_USER
}
