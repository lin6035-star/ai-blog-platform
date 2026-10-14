package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章域结构化状态测试（P2.2，Token 设计稿 §7.2 / §7.4 / §7.5 / §7.6）。
 *
 * <p>它锁的是**决策上下文的契约**：以前每步重放整条 observation 流，现在按语义槽位组装。
 * 现有 Runtime 测试用 mock decider（不检查上下文），所以这层契约必须由本类单独守住——
 * 否则"上下文里少了关键事实"这种退化只有线上才暴露。
 */
class ArticleAgentStateTests {

    private ArticleAgentState state() {
        return new ArticleAgentState("分析一下这篇文章写得怎么样", 6);
    }

    private AgentStepDecision decision(AgentStepActionType type) {
        return AgentStepDecision.of(type);
    }

    private AgentActionResult articleResult(String summary, String focus, Boolean focusHit) {
        return AgentActionResult.of(summary)
                .with("articleId", "12")
                .with("articleTitle", "Redis 缓存设计")
                .with("owned", true)
                .with("focus", focus)
                .with("focusHit", focusHit);
    }

    @Test
    @DisplayName("读文章结构 -> 目标对象与文章证据槽位都填上")
    void articleQueryFillsTargetAndEvidence() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("结构摘要：10 节 / 1.3 万字", null, null));

        String context = state.decisionContext();

        assertThat(context)
                .as("目标与步数由 Decider 的 user prompt 单独携带，槽位里不能重复")
                .doesNotContain("【目标】")
                .doesNotContain("【步数】");
        assertThat(context).contains("【已确认目标文章】ID 12《Redis 缓存设计》");
        assertThat(context).contains("【文章结构】");
        assertThat(context).contains("结构摘要：10 节 / 1.3 万字");
        assertThat(context).contains("已完成动作");
        assertThat(context).contains("已读文章结构");
    }

    @Test
    @DisplayName("第 1 步之前为空上下文（与旧路径 clipContext(空) 一致，不凭空加格式开销）")
    void emptyStateProducesEmptyContext() {
        assertThat(state().decisionContext()).isEmpty();
    }

    @Test
    @DisplayName("聚焦未命中 -> 进「仍缺的证据」，且不进文章证据槽位")
    void focusMissBecomesMissingEvidence() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("未能找到与「缓存击穿」相关的内容", "缓存击穿", false));

        String context = state.decisionContext();

        assertThat(context).contains("【仍缺的证据】");
        assertThat(context).contains("文章内容");
        assertThat(context).contains("查过「缓存击穿」但没找到");
        assertThat(context).doesNotContain("【文章结构】");
        assertThat(context).doesNotContain("【聚焦片段】");
    }

    @Test
    @DisplayName("聚焦命中 -> 进文章证据槽位，键含 focus（不同 focus 可并存）")
    void focusHitGoesToEvidenceSlot() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("聚焦片段：互斥锁兜底", "缓存击穿", true));

        String context = state.decisionContext();
        assertThat(context).contains("【聚焦片段】");
        assertThat(context).contains("聚焦片段：互斥锁兜底");
        assertThat(context).contains("已查「缓存击穿」");
    }

    @Test
    @DisplayName("同一 focus 再查 -> 替换而不是堆积（§7.5 同键新内容替换旧内容）")
    void sameFocusReplacesInsteadOfAccumulating() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("旧片段", "缓存击穿", true));
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("新片段", "缓存击穿", true));

        String context = state.decisionContext();

        assertThat(context).contains("新片段");
        assertThat(context).doesNotContain("旧片段");
    }

    @Test
    @DisplayName("记忆未命中 -> 明确写进缺失证据（避免模型反复空转）")
    void memoryMissIsRecorded() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：\n").with("query", "写作偏好").with("hitCount", 0));

        String context = state.decisionContext();
        assertThat(context).contains("已查写作偏好（无命中）");
        assertThat(context).contains("写作偏好（记忆里没有命中，不必再查）");
    }

    @Test
    @DisplayName("记忆命中 -> 进记忆证据槽位，并清掉对应的缺失项")
    void memoryHitClearsMissing() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：\n- 语义记忆：喜欢简洁").with("query", "写作偏好").with("hitCount", 2));

        String context = state.decisionContext();
        assertThat(context).contains("【记忆证据】");
        assertThat(context).contains("喜欢简洁");
        assertThat(context).contains("已查写作偏好（命中 2 条）");
        assertThat(context).doesNotContain("记忆里没有命中");
    }

    @Test
    @DisplayName("站内知识检索 -> 命中进槽位；无命中记缺失")
    void ragHitAndMiss() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.SEARCH_RAG),
                AgentActionResult.of("1. 《缓存设计》(3)：互斥回源").with("keyword", "缓存").with("hitCount", 1));
        state.reduce(decision(AgentStepActionType.SEARCH_RAG),
                AgentActionResult.of("站内没有检索到").with("keyword", "量子计算").with("hitCount", 0));

        String context = state.decisionContext();
        assertThat(context).contains("【站内知识】");
        assertThat(context).contains("互斥回源");
        assertThat(context).contains("站内知识（关键词无命中）");
    }

    @Test
    @DisplayName("动作失败 -> 完成列表记「失败」+ 最近失败一句（不污染证据槽位）")
    void failureRecordedAsLatestError() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                AgentActionResult.of("这篇文章不存在或已删除，无法为你分析。").with("failed", true));

        String context = state.decisionContext();
        assertThat(context).contains("QUERY_ARTICLE（失败）");
        assertThat(context).contains("【最近失败】");
        assertThat(context).contains("这篇文章不存在或已删除");
        assertThat(context).doesNotContain("【文章结构】");
        assertThat(context).doesNotContain("【聚焦片段】");
    }

    @Test
    @DisplayName("★2026-10-11 回归：多轮 focus 补查后，文章结构摘要必须仍在（它被挤掉 = 证据门空转）")
    void overviewSurvivesAfterMultipleFocusQueries() {
        ArticleAgentState state = state();

        // 真实数据：结构摘要 1889～2313 字符、单条聚焦片段上限 1200——合槽时一条片段就能占满槽位
        String overview = "结构摘要：" + "节".repeat(900) + "|小标题：基础用法/原理/总结";
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE), articleResult(overview, null, null));
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("聚焦片段：" + "基".repeat(1200), "基础用法", true));
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("聚焦片段：" + "总".repeat(1200), "总结", true));

        String evidence = state.verificationEvidence();

        assertThat(evidence)
                .as("Verifier 判「文章里到底有没有这段内容」只能靠小标题结构——它一旦被片段挤掉，"
                        + "就会连续判「需要全文确认」，最后 6 步耗尽且没有回答")
                .contains("【文章结构】")
                .contains("小标题：基础用法/原理/总结");
        assertThat(evidence)
                .as("最新的聚焦片段仍要在（局部细节最新优先）")
                .contains("【聚焦片段】")
                .contains("总".repeat(50));
    }

    @Test
    @DisplayName("★2026-10-11 回归：同一缺失类型连续被拒达上限后，骨架必须放行草案")
    void consecutiveSameTypeRejectionsExhaustVerifier() {
        ArticleAgentState state = state();

        assertThat(state.verifierRejectionExhausted())
                .as("没被拒过不能触发放行")
                .isFalse();

        state.noteVerifierRejection(Verdict.MissingEvidence.ARTICLE_CONTENT);
        assertThat(state.verifierRejectionExhausted())
                .as("第一次拒绝是正常的促补查信号，仍要拦")
                .isFalse();

        state.noteVerifierRejection(Verdict.MissingEvidence.ARTICLE_CONTENT);
        assertThat(state.verifierRejectionExhausted())
                .as("连续第二次指向同一类缺失：再拦只会耗尽步数（实测 3 拦 / 6 步 / 18300 token 无回答）")
                .isTrue();
    }

    @Test
    @DisplayName("换缺失类型要重置连续计数——那是模型找到新方向，不算死路")
    void rejectionTypeChangeResetsConsecutiveCount() {
        ArticleAgentState state = state();

        state.noteVerifierRejection(Verdict.MissingEvidence.ARTICLE_CONTENT);
        state.noteVerifierRejection(Verdict.MissingEvidence.MEMORY);
        assertThat(state.verifierRejectionExhausted()).isFalse();

        state.noteVerifierRejection(Verdict.MissingEvidence.MEMORY);
        assertThat(state.verifierRejectionExhausted())
                .as("同一类型（MEMORY）连续两次才算死路")
                .isTrue();
    }

    @Test
    @DisplayName("槽位超预算时**最新优先**保留（与 P0-a 修过的裁剪取向一致）")
    void slotKeepsNewestWhenOverBudget() {
        ArticleAgentState state = state();
        String old = "旧".repeat(1000);
        String fresh = "新".repeat(1000);

        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult(old, "第一处", true));
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult(fresh, "第二处", true));

        String context = state.decisionContext();

        // 聚焦片段槽位预算 1500：两条各 1000 装不下，必须留下**最新**那条。
        // 结构摘要另有独立预算、不参与这里的淘汰（见 overviewSurvivesAfterMultipleFocusQueries）
        assertThat(context).contains("新".repeat(50));
        assertThat(context).doesNotContain("旧".repeat(50));
    }

    @Test
    @DisplayName("不同 run 的状态互不影响（per-run 硬约束）")
    void statesAreIndependent() {
        ArticleAgentState first = state();
        ArticleAgentState second = state();

        first.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("第一条 run 的证据", null, null));

        assertThat(first.decisionContext()).contains("第一条 run 的证据");
        assertThat(second.decisionContext())
                .as("另一个 run 的状态必须干净")
                .doesNotContain("第一条 run 的证据")
                .isEmpty();
    }

    @Test
    @DisplayName("P2.3 修订：拒绝 / 拦截反馈必须进决策上下文（原先只进 observations，模型看不到）")
    void feedbackReachesDecisionContext() {
        ArticleAgentState state = state();
        state.noteFeedback("系统提示：你的回答依据不足（缺 文章内容）：缺正文细节。请先补充查询再回答");

        String context = state.decisionContext();

        // 反馈独立成槽、**只出现一次**——原先同时进 missingEvidence 与 latestError，
        // 同一句在 prompt 里出现两遍，且 latestError 逃出所有预算
        assertThat(context).contains("【系统反馈】");
        assertThat(context).contains("回答依据不足");
        assertThat(context).doesNotContain("【最近失败】");
        assertThat(context).doesNotContain("【仍缺的证据】");
        assertThat(context.split("回答依据不足", -1).length - 1)
                .as("同一句反馈只能出现一次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("P2.3 修订：同键同内容 -> 无新证据（Verifier 的 R3 放行规则改用这个信号）")
    void repeatedEvidenceIsNotNew() {
        ArticleAgentState state = state();

        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE), articleResult("同一段正文", "缓存击穿", true));
        assertThat(state.lastStepHadNewEvidence()).as("首次当然算新证据").isTrue();

        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE), articleResult("同一段正文", "缓存击穿", true));
        assertThat(state.lastStepHadNewEvidence())
                .as("同键同内容 = 没有新证据，R3 才敢放行——这是结构化替代文本比对的地方")
                .isFalse();

        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE), articleResult("换了一段正文", "缓存击穿", true));
        assertThat(state.lastStepHadNewEvidence()).as("内容变了就是新证据").isTrue();
    }

    @Test
    @DisplayName("P2.3 修订：单条证据超预算必须截断（否则「槽位预算」是假的）")
    void oversizedSingleEvidenceIsTruncated() {
        ArticleAgentState state = state();
        String huge = "长".repeat(2000);   // 远超 Memory 槽位预算 600

        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of(huge).with("query", "写作偏好").with("hitCount", 1).with("status", "SUCCESS"));

        String context = state.decisionContext();

        assertThat(context.length())
                .as("单条 2000 字符不能把上下文撑爆——修订前会整条塞进去")
                .isLessThan(1500);
        assertThat(context).contains("…");
    }

    @Test
    @DisplayName("P2.3 修订：记忆检索失败不能说成「没有命中」")
    void memoryFailureIsNotTreatedAsNoHit() {
        ArticleAgentState failed = state();
        failed.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：\n- 语义记忆检索失败\n")
                        .with("query", "写作偏好").with("hitCount", 0).with("status", "FAILED"));

        String failedContext = failed.decisionContext();
        assertThat(failedContext).contains("检索服务失败");
        assertThat(failedContext)
                .as("故障不能被说成事实结论——那会让模型以为「不必再查」")
                .doesNotContain("记忆里没有命中");

        ArticleAgentState partial = state();
        partial.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：\n- 语义记忆：喜欢简洁\n- 情景记忆检索失败\n")
                        .with("query", "写作偏好").with("hitCount", 1).with("status", "PARTIAL_FAILURE"));

        String partialContext = partial.decisionContext();
        assertThat(partialContext).contains("有来源检索失败");
        assertThat(partialContext).contains("喜欢简洁");
    }

    @Test
    @DisplayName("P2.3 修订：同一动作成功重试后清除旧的最近失败")
    void successfulRetryClearsLatestErrorForSameAction() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：语义记忆检索失败，情景记忆检索失败")
                        .with("query", "写作偏好").with("hitCount", 0).with("status", "FAILED"));
        assertThat(state.decisionContext()).contains("【最近失败】").contains("检索失败");

        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：喜欢简洁")
                        .with("query", "写作偏好").with("hitCount", 1).with("status", "SUCCESS"));

        assertThat(state.decisionContext())
                .as("同一动作已经成功，旧故障不能继续误导后续决策和总结")
                .doesNotContain("【最近失败】")
                .doesNotContain("语义记忆检索失败");
    }

    @Test
    @DisplayName("P2.3 修订：补到证据后旧反馈必须消失（否则 Verifier 一直看到旧结论，自我强化）")
    void feedbackClearedAfterEvidenceSupplemented() {
        ArticleAgentState state = state();
        state.noteFeedback("系统提示：你的回答依据不足（缺 文章正文细节）：缺正文细节",
                AgentRunState.FeedbackClearKey.ARTICLE_CONTENT);
        assertThat(state.decisionContext()).contains("回答依据不足");

        // 带 focus 的补查成功，且拿到了**新**内容
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("聚焦片段：互斥锁兜底", "缓存击穿", true));

        assertThat(state.decisionContext())
                .as("补到正文后，指向「文章内容」的旧反馈必须被清掉")
                .doesNotContain("回答依据不足");
    }

    @Test
    @DisplayName("P2.3 修订：需先查询的临时反馈在任一只读查询产生结果后清除")
    void queryRequiredFeedbackClearsAfterReadResult() {
        ArticleAgentState state = state();
        state.noteFeedback("系统提示：没有任何查询结果，必须先执行只读查询。",
                AgentRunState.FeedbackClearKey.QUERY_REQUIRED);
        assertThat(state.decisionContext()).contains("必须先执行只读查询");

        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("结构摘要：10 节 / 1.3 万字", null, null));

        assertThat(state.decisionContext())
                .as("查询前置条件已经满足，旧拒绝不能继续误导下一轮")
                .doesNotContain("必须先执行只读查询");
    }

    @Test
    @DisplayName("P2.3 修订：Verifier 只读证据，总结仍能看到反馈与失败")
    void verifierAndSummaryUseDifferentContexts() {
        ArticleAgentState state = state();
        state.noteFeedback("系统提示：上一次回答依据不足");
        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                AgentActionResult.of("文章查询临时失败").with("failed", true));

        assertThat(state.decisionContext())
                .contains("上一次回答依据不足")
                .contains("文章查询临时失败");
        assertThat(state.verificationEvidence())
                .as("Verifier 不能把上一次 Verifier 结论或执行错误当成事实证据")
                .doesNotContain("上一次回答依据不足")
                .doesNotContain("文章查询临时失败");
        assertThat(state.summaryContext())
                .as("到顶总结需要如实解释运行期反馈和失败")
                .contains("上一次回答依据不足")
                .contains("文章查询临时失败");
    }

    @Test
    @DisplayName("P2.3 修订：RAG 换关键词命中后，旧的「无命中」也要清掉（原先只有 Memory / Article 有清理）")
    void ragMissingEvidenceClearedOnNewKeywordHit() {
        ArticleAgentState state = state();
        state.reduce(decision(AgentStepActionType.SEARCH_RAG),
                AgentActionResult.of("站内没有检索到").with("keyword", "量子计算").with("hitCount", 0));
        assertThat(state.decisionContext()).contains("站内知识（关键词无命中）");

        state.reduce(decision(AgentStepActionType.SEARCH_RAG),
                AgentActionResult.of("1. 《缓存设计》(3)：互斥回源").with("keyword", "缓存").with("hitCount", 1));

        assertThat(state.decisionContext())
                .as("换关键词命中后，缺失项不该只增不减")
                .doesNotContain("站内知识（关键词无命中）");
    }

    @Test
    @DisplayName("Verifier 拒绝后只认对应类型的新证据")
    void verifierProgressTracksMatchingEvidenceType() {
        ArticleAgentState state = state();

        assertThat(state.hasEvidenceProgressSinceRejection())
                .as("从未被拒绝时不能触发收敛拦截")
                .isTrue();

        state.noteVerifierRejection(Verdict.MissingEvidence.MEMORY);
        assertThat(state.hasEvidenceProgressSinceRejection()).isFalse();

        state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                articleResult("结构摘要：10 节", null, null));
        assertThat(state.evidenceRevision(Verdict.MissingEvidence.ARTICLE_CONTENT)).isEqualTo(1);
        assertThat(state.evidenceRevision(Verdict.MissingEvidence.MEMORY)).isZero();
        assertThat(state.hasEvidenceProgressSinceRejection())
                .as("缺记忆时补文章内容，不算满足这次拒绝")
                .isFalse();

        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：喜欢简洁")
                        .with("query", "写作偏好").with("hitCount", 1).with("status", "SUCCESS"));

        assertThat(state.evidenceRevision(Verdict.MissingEvidence.MEMORY)).isEqualTo(1);
        assertThat(state.evidenceRevision(Verdict.MissingEvidence.OTHER)).isEqualTo(2);
        assertThat(state.hasEvidenceProgressSinceRejection()).isTrue();
    }

    @Test
    @DisplayName("首次权威空结果算进展；重复空结果、失败和部分失败不算")
    void evidenceRevisionIgnoresRepeatedEmptyAndFailedRetrievals() {
        ArticleAgentState state = state();

        state.noteVerifierRejection(Verdict.MissingEvidence.MEMORY);
        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：无命中")
                        .with("query", "写作偏好").with("hitCount", 0).with("status", "SUCCESS"));
        assertThat(state.evidenceRevision(Verdict.MissingEvidence.MEMORY)).isEqualTo(1);
        assertThat(state.hasEvidenceProgressSinceRejection()).isTrue();

        state.noteVerifierRejection(Verdict.MissingEvidence.MEMORY);
        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆摘要：无命中")
                        .with("query", "写作偏好").with("hitCount", 0).with("status", "SUCCESS"));
        assertThat(state.evidenceRevision(Verdict.MissingEvidence.MEMORY)).isEqualTo(1);
        assertThat(state.hasEvidenceProgressSinceRejection()).isFalse();

        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("记忆检索失败")
                        .with("query", "另一查询").with("hitCount", 0).with("status", "FAILED"));
        state.reduce(decision(AgentStepActionType.QUERY_MEMORY),
                AgentActionResult.of("部分命中但有来源失败")
                        .with("query", "第三查询").with("hitCount", 1).with("status", "PARTIAL_FAILURE"));
        assertThat(state.evidenceRevision(Verdict.MissingEvidence.MEMORY)).isEqualTo(1);
        assertThat(state.hasEvidenceProgressSinceRejection()).isFalse();

        state.noteVerifierRejection(Verdict.MissingEvidence.RAG);
        state.reduce(decision(AgentStepActionType.SEARCH_RAG),
                AgentActionResult.of("站内没有检索到")
                        .with("keyword", "量子计算").with("hitCount", 0));
        assertThat(state.evidenceRevision(Verdict.MissingEvidence.RAG)).isEqualTo(1);
        assertThat(state.hasEvidenceProgressSinceRejection()).isTrue();

        state.noteVerifierRejection(Verdict.MissingEvidence.RAG);
        state.reduce(decision(AgentStepActionType.SEARCH_RAG),
                AgentActionResult.of("站内没有检索到")
                        .with("keyword", "量子计算").with("hitCount", 0));
        assertThat(state.evidenceRevision(Verdict.MissingEvidence.RAG)).isEqualTo(1);
        assertThat(state.hasEvidenceProgressSinceRejection()).isFalse();
    }

    @Test
    @DisplayName("多步累积时结构化上下文明显短于 observation 重放（P2.2 的收益所在）")
    void structuredContextIsShorterThanObservationReplay() {
        int steps = 6;
        String chunk = "缓存".repeat(500);   // 每步观察 ~1000 字符

        ArticleAgentState state = state();
        java.util.List<String> observations = new java.util.ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            String focus = "第" + i + "节";
            String summary = "[QUERY_ARTICLE]\n" + chunk;
            observations.add(summary);
            state.reduce(decision(AgentStepActionType.QUERY_ARTICLE),
                    articleResult(chunk, focus, true));
        }

        // 旧路径：整条 observation 流按总预算裁剪（6000 字符，最早优先丢弃）
        String replayed = AgentContextClipper.latestWindow(observations, 6000);
        String structured = state.decisionContext();

        System.out.println("[p2.2] 6 步累积：observation 重放 = " + replayed.length()
                + " 字符，结构化上下文 = " + structured.length() + " 字符");

        assertThat(structured.length())
                .as("结构化上下文必须比 observation 重放短")
                .isLessThan(replayed.length());
        // 槽位预算合计守住上限（2026-10-11 起文章域分槽）：
        // 文章结构 2400 + 聚焦片段 1500 + 记忆 600 + 站内 800 + 已完成动作 300 + 缺失 300
        // + 系统反馈 400 = 6300，再留槽位标题开销
        assertThat(structured.length())
                .as("槽位预算必须真的兜住总量")
                .isLessThan(7000);
    }
}
