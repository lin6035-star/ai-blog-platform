package com.hailin.blogsystem.ai.eval;

import java.util.List;

/**
 * P3 spike 样例（2026-10-01）。
 *
 * <p>只描述**输入**，不含期望输出——P3.0 是测量（拿数字），不是断言判对错。
 * 判断"新方案是否退化"用的是固定评测集，两件事刻意分开：
 * 混在一起会让人把"变慢了"看成"判错了"。
 *
 * @param layer 分层标签。P3 的结论建立在**分层**对比上（哪一层被省下来、哪一层被拖慢），
 *              总体平均会把两者抵消成一个看不出问题的数字
 */
public record P3Sample(
        String name,
        String layer,
        String message,
        String pageType,
        String pageArticleId,
        /**
         * 计划定位样例需要一个「名下有真实计划」的用户 —— 只有传了 userId，
         * 分类器才会在命中「调整/攻坚已有计划」时查列表并发出第二次调用。
         * 普通样例传 null（或省略字段），走的是单调用快路径。
         */
        Long userId,
        /**
         * 绑定的评测集样例名（可空）。
         *
         * <p>非空时，采集会先跑那条例的**硬门**（多目标完整性 / 安全反例 / 5a 双写 /
         * goalEvidence 原文包含性），**硬门不过的样例不进性能排名**。
         * 没有这一层，漏掉第二目标的方案可能凭低 Token 获胜——P3 就选错了。
         */
        String evalCaseName,
        /** 需要「名下有真实计划」时，采集前插入这些标题的 ACTIVE 计划 */
        List<String> planFixtureTitles,
        /** 未点名计划 → 断言「不许猜」：learningPlanId 必须为空 */
        Boolean planIdMustBeBlank,
        /** 点名了计划 → 断言选中的是该标题的计划 */
        String planIdMustEqualTitle
) {
}
