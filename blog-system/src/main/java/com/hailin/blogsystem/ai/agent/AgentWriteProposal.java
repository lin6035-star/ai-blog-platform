package com.hailin.blogsystem.ai.agent;

/**
 * Agent 写动作提案（V2.4）。
 *
 * 安全模型：Agent 只能提案，后端裁判（确定性匹配）+ 用户确认后才执行。
 * LLM 只给标题类信息（planRef/stageTitle/taskTitle/newTitle），不给索引——
 * 阶段/任务由后端按标题匹配定位（匹配不到拒绝或追问，不猜）。
 *
 * V3.4 起跨域复用：尾部 articleId/articleTitle 为文章域字段（学习域动作不填，null）。
 * 字段随域增长互不相干——V3.5 域注册重构触发证据（收口文档见 agent-module-acceptance-checklist）。
 */
public record AgentWriteProposal(
        String actionType,
        String planRef,
        String stageTitle,
        String taskTitle,
        boolean done,
        String newTitle,
        String articleId,
        String articleTitle,
        String payYuan
) {

    /** 写动作类型（V2.4）：勾选 / 取消勾选已有任务（done 生效） */
    public static final String TYPE_UPDATE_TASK_DONE = "UPDATE_TASK_DONE";

    /** 写动作类型（V3.1）：向计划指定阶段追加用户点名的新任务（done 忽略） */
    public static final String TYPE_ADD_LEARNING_TASK = "ADD_LEARNING_TASK";

    /** 写动作类型（V3.3）：把已有任务重命名（taskTitle=旧名定位，newTitle=用户给的新名，done 忽略） */
    public static final String TYPE_UPDATE_LEARNING_TASK = "UPDATE_LEARNING_TASK";

    /**
     * 写动作类型（V3.4，文章域）：改自己文章标题。
     * articleId = 定位锚（必须来自页面上下文，后端查库验归属）；
     * articleTitle = 提案时刻的 DB 权威旧标题（并发防护锚，confirm stale 校验 + 条件更新都以它为 expected old value）；
     * newTitle = 用户原话的新标题；done 忽略。
     */
    public static final String TYPE_UPDATE_ARTICLE_TITLE = "UPDATE_ARTICLE_TITLE";

    /**
     * 写动作类型（V3.7，文章域可见性批次）：把当前自己的文章设为隐藏（前置 = 当前 PUBLISHED）。
     * articleId + articleTitle（DB 权威标题，确认卡展示用）；前置状态从动作方向推导，proposal 不加状态锚；
     * 执行由 updateArticleVisibility 的 WHERE status = expectedStatus 原子收口。
     */
    public static final String TYPE_HIDE_ARTICLE = "HIDE_ARTICLE";

    /** 写动作类型（V3.7，文章域可见性批次）：把当前自己的文章公开/取消隐藏（前置 = 当前 HIDDEN）。 */
    public static final String TYPE_PUBLISH_ARTICLE = "PUBLISH_ARTICLE";

    /**
     * 写动作类型（2026-09-21，钱包域）：给钱包充值自定义金额。
     *
     * <p>{@code payYuan} = 充值金额（**元**，整数串）。它是<b>从用户原话里确定性解析出来的</b>
     * （见 {@code WalletAmountParser}），不是 LLM 给的——金额是事实不是语义，
     * 让它过一遍 LLM 就多一次「模型编一个用户没说的数字、卡片就弹出来」的机会。
     *
     * <p>其余字段对钱包域无意义（null / false）。**到账额度刻意不在这里快照**：
     * 它是兑换比例（配置）派生出来的，提案时刻算好、confirm 时刻配置可能已经变了，
     * 卡片上说「到账 10000」而实际到账 20000 是没法解释的。权威数字由执行器写在结果文案里。
     */
    public static final String TYPE_RECHARGE_WALLET = "RECHARGE_WALLET";
}
