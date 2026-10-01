package com.hailin.blogsystem.ai.task;

/**
 * 任务步骤类型（第三刀：分层任务编排）。
 *
 * <p>**封闭集合**：新增能力要显式加枚举 + 注册执行器 + 补验收，而不是往 Planner 里加分支——
 * 这正是前两刀「一种组合 = 一条分支」被反例证伪后换来的结构。
 *
 * <p>第一版只实现标 {@code 1} 的三个（确定性执行器）；`ARTICLE_QUERY` / `ARTICLE_ANALYSIS`
 * 已进入协议模型但属于后续接入能力，其中 `ARTICLE_ANALYSIS` 是父子 run 的触发点。
 */
public enum StepType {

    /** 文章低风险写动作（点赞 / 收藏 / 关注）。第一版：[1] */
    ARTICLE_WRITE,

    /** 站内文章检索。第一版：[1] */
    ARTICLE_SEARCH,

    /** 客户端导航命令（跳转作者主页等）——由前端执行，结果只能是 DISPATCHED。第一版：[1] */
    NAVIGATE,

    /** 读取文章内容。后续接入 */
    ARTICLE_QUERY,

    /** 文章分析（内部 ReAct，会引入父子 run）。后续接入 */
    ARTICLE_ANALYSIS,

    /** 交给既有 Workflow 的黑盒步骤——不拆那 5 条固定流程。后续接入 */
    WORKFLOW_HANDOFF;

    /**
     * 该步骤是否会发起**长 LLM 调用**——决定一份计划要不要过 AI 长任务准入（第四步）。
     *
     * <p>确定性 / 只读步骤（写动作、站内检索、客户端导航）都是秒级完成；
     * 让它们去占「8 在跑 + 32 排队」的长任务池与每用户名额，只会挤掉真正需要资源的 Agent 任务。
     * {@code WORKFLOW_HANDOFF} 接入后同样为 true——那也是一段长流程。
     *
     * <p>判据放在枚举旁边而不是聊天入口：入口不该知道"哪种步骤贵"。
     */
    public boolean isLongRunningLlm() {
        return this == ARTICLE_ANALYSIS || this == WORKFLOW_HANDOFF;
    }
}
