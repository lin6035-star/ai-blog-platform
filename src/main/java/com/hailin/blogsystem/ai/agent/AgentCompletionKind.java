package com.hailin.blogsystem.ai.agent;

/**
 * Agent Run 的**完成类型**（第四步·阶段 1，2026-09-30）。
 *
 * <p><b>为什么需要它</b>：子 run 有四种收尾都落 {@code COMPLETED}（正常回答 / 空答案兜底 /
 * 证据不足保守收尾 / 步数到顶总结），外加终局失败也是 {@code COMPLETED}，
 * 而 {@link AgentRunResult} 原先只有 `status + finalAnswer`——
 * 外层任务编排**无法区分「用户目标达成了」与「只是流程跑完了」**。
 * 若直接按 `COMPLETED -> SUCCEEDED` 回填，汇总会输出
 * 「已全部完成：目前收集到的信息还不足以让我给出可靠结论」——那正是第一刀明令禁止的谎报。
 *
 * <p><b>为什么只覆盖 COMPLETED</b>：`FAILED` / `WAITING_USER` 已由
 * {@code AiAgentRunStatus} 表达，在这里再放一遍等于同时维护两套状态机（项目一贯反对双重状态机）。
 *
 * <p><b>判据只读枚举</b>：不解析 `finalAnswer` 文案——文案是展示层的东西，不能承载程序判断。
 */
public enum AgentCompletionKind {

    /** 正常回答：FINAL_ANSWER 有正文，且证据收敛门放行（或该域没有验证器）。 */
    ANSWER,

    /** 提取不到回答正文、落了领域兜底文案——流程跑完，但用户没拿到分析。 */
    EMPTY_ANSWER_FALLBACK,

    /** 证据不足，主动不硬答（V3.11 保守收尾）。 */
    CONSERVATIVE_SHUTDOWN,

    /** 步数到顶后的 summarize / 拼接：有阶段性内容，但没经过最终答案同等级的证据放行。 */
    MAX_STEPS_SUMMARY,

    /** 终局失败（目标不存在 / 无权访问等业务终局），run 状态仍是 COMPLETED。 */
    TERMINAL_FAILURE
}
