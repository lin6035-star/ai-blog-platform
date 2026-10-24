package com.hailin.blogsystem.ai.task;

/**
 * 前序步骤失败后本步的处理策略（第三刀）。
 *
 * <p>"点赞失败还要不要搜索"这类问题**必须显式定义**，不能由实现随手决定——
 * 所以它是 {@link StepDependency} 的一个独立字段，而不是揉进顺序依赖里。
 *
 * <p>判据只看**本步依赖的步骤**（{@code after} + {@code requires} 引用的那些）是否失败。
 */
public enum OnFailure {

    /** 前序失败也照常执行（搜索不依赖点赞结果，用户也只是想要两件事） */
    CONTINUE,

    /** 前序失败就跳过本步（少了必需的输入，做下去只会失败） */
    SKIP,

    /** 前序失败要问用户怎么处理——本步进 WAITING_USER，整个计划暂停 */
    CONFIRM
}
