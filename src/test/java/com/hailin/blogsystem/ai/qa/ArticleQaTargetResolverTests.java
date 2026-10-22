package com.hailin.blogsystem.ai.qa;

import com.hailin.blogsystem.ai.agent.ArticleSessionAnchorService;
import com.hailin.blogsystem.ai.qa.ArticleQaTargetResolver.QaTarget;
import com.hailin.blogsystem.ai.qa.ArticleQaTargetResolver.Signal;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA 目标决议测试（V3.9）：候选先加载后成候选 + 措辞信号 → 决议矩阵。
 * 读权限本身（loadReadable / resolveReadable）在 ArticleSessionAnchorServiceTests 验证，
 * 这里只 mock 锚 service 的返回，聚焦矩阵行为。
 */
class ArticleQaTargetResolverTests {

    private ArticleSessionAnchorService anchorService;
    private ArticleQaTargetResolver resolver;

    @BeforeEach
    void setUp() {
        anchorService = mock(ArticleSessionAnchorService.class);
        resolver = new ArticleQaTargetResolver(anchorService);
    }

    private static PageContextDTO page(String articleId) {
        PageContextDTO ctx = new PageContextDTO();
        ctx.setPageType("article-detail");
        ctx.setArticleId(articleId);
        return ctx;
    }

    private static Articles article(Long id, Long authorId, String title) {
        Articles article = new Articles();
        article.setId(id);
        article.setAuthorId(authorId);
        article.setTitle(title);
        return article;
    }

    private void stubPage(Long id, Articles result) {
        when(anchorService.loadReadable(id, 100L)).thenReturn(result);
    }

    private void stubAnchor(Articles result) {
        when(anchorService.resolveReadable(10L, 100L)).thenReturn(result);
    }

    // ===================== 词集边界（classifySignal） =====================

    @Test
    void recentReferenceRequiresArticleDeixisTogether() {
        assertThat(ArticleQaTargetResolver.classifySignal("把刚刚那篇总结一下")).isEqualTo(Signal.S2_STRONG_RECENT);
        assertThat(ArticleQaTargetResolver.classifySignal("刚才文章里说的那个点再讲讲")).isEqualTo(Signal.S2_STRONG_RECENT);
        // 时间近指但无文章指代 = 对话内容追问，不算会话文章近指
        assertThat(ArticleQaTargetResolver.classifySignal("把刚才说的那个观点再展开讲讲")).isEqualTo(Signal.S0_NONE);
        assertThat(ArticleQaTargetResolver.classifySignal("刚刚你提到的结论是什么")).isEqualTo(Signal.S0_NONE);
    }

    @Test
    void classifySignalCoversAllTiers() {
        assertThat(ArticleQaTargetResolver.classifySignal("这篇讲了什么")).isEqualTo(Signal.S1_STRONG_PAGE);
        assertThat(ArticleQaTargetResolver.classifySignal("当前文章重点是什么")).isEqualTo(Signal.S1_STRONG_PAGE);
        assertThat(ArticleQaTargetResolver.classifySignal("它的意思是")).isEqualTo(Signal.S1_STRONG_PAGE);
        assertThat(ArticleQaTargetResolver.classifySignal("那篇的观点是什么")).isEqualTo(Signal.S3_WEAK);
        assertThat(ArticleQaTargetResolver.classifySignal("缓存击穿是什么意思")).isEqualTo(Signal.S0_NONE);
        assertThat(ArticleQaTargetResolver.classifySignal(null)).isEqualTo(Signal.S0_NONE);
        assertThat(ArticleQaTargetResolver.classifySignal("  ")).isEqualTo(Signal.S0_NONE);
    }

    // ===================== 决议矩阵 =====================

    @Test
    void strongRecentPrefersAnchorOverPageArticle() {
        // 详情页 B + 会话锚 A + "刚刚那篇" → 锚 A（不取当前页——V3.8 指代语义延续）
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("把刚刚那篇总结一下", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(2L);
    }

    @Test
    void strongRecentWithoutAnchorPromptsAndNeverDefaultsPage() {
        // 新会话详情页说"刚刚那篇"（无锚）：澄清，不许硬套当前页——V3.8 老大实测场景回归
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(null);

        QaTarget target = resolver.resolve("刚刚那篇讲了什么", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).contains("刚刚");
        assertThat(target.promptNote()).contains("当前页 B");
        assertThat(target.promptNote()).contains("不要把这篇当");
    }

    @Test
    void recentWithoutAnchorAndWithoutPagePrompts() {
        stubAnchor(null);

        QaTarget target = resolver.resolve("刚才那篇讲了什么", null, 10L, 100L);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).isNotBlank();
    }

    @Test
    void anchorIsOnlyCandidateWhenNoPageArticle() {
        stubAnchor(article(2L, 999L, "他人的公开文章"));

        QaTarget target = resolver.resolve("刚才那篇讲了什么", null, 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(2L);
    }

    @Test
    void strongPagePrefersCurrentPageArticle() {
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("这篇的重点是什么", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(1L);
    }

    @Test
    void noDeixisDefaultsToCurrentPage() {
        // 详情页高频问句（无指代词）语境隐含当前页——现状行为回归
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("缓存击穿是什么意思", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(1L);
    }

    @Test
    void weakDeixisWithBothCandidatesAsksUser() {
        // 双候选 + "那篇"：不猜，追问带两个权威标题
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("那篇的观点是什么", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).contains("当前页 B");
        assertThat(target.promptNote()).contains("会话里的 A");
        assertThat(target.promptNote()).contains("确认");
    }

    @Test
    void weakDeixisWithSingleCandidateUsesItWithoutAsking() {
        // 锚不存在时"那篇"大概率是当前页口语 → 唯一候选直接用，不打断
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(null);

        QaTarget target = resolver.resolve("那篇讲了什么", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(1L);
    }

    @Test
    void weakDeixisWithOnlyAnchorUsesAnchor() {
        stubAnchor(article(2L, 999L, "他人的公开文章"));

        QaTarget target = resolver.resolve("那篇讲了什么", null, 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(2L);
    }

    @Test
    void conversationRetrospectiveQuestionIsNotRecentArticleReference() {
        // "刚才说的观点" = 对话内容追问（无文章指代）→ 不算会话近指，保持页面优先
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("把刚才说的那个观点再展开讲讲", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(1L);
    }

    @Test
    void guestHasNoAnchorPath() {
        when(anchorService.resolveReadable(10L, null)).thenReturn(null);
        when(anchorService.loadReadable(1L, null)).thenReturn(null);

        QaTarget target = resolver.resolve("那篇讲了什么", page("1"), 10L, null);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).isNotBlank();
    }

    @Test
    void guestRecentReferenceIsNotEnforcedWithoutSession() {
        // 游客（sessionId null）无"会话最近文章"概念：详情页问"刚才那篇"不触发澄清规则，
        // 按当前页口语处理（loadReadable 游客口径只读 PUBLISHED——这里 mock 直接给可读文章）
        when(anchorService.loadReadable(1L, null)).thenReturn(article(1L, 999L, "公开文章"));

        QaTarget target = resolver.resolve("刚才那篇讲了什么", page("1"), null, null);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(1L);
    }

    @Test
    void unreadablePageCandidateFallsBackToAnchor() {
        // 页面 ID 存在但不可读（他人隐藏/已删除/脏 ID）→ 无 PC，走锚
        stubPage(999L, null);
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("那篇讲了什么", page("999"), 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(2L);
    }

    @Test
    void unreadablePageCandidateAndNoAnchorPrompts() {
        stubPage(999L, null);
        stubAnchor(null);

        QaTarget target = resolver.resolve("刚才那篇讲了什么", page("999"), 10L, 100L);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).isNotBlank();
    }

    @Test
    void malformedPageArticleIdIsTreatedAsNoCandidate() {
        // 非数字 ID：loadReadable 不被调用，直接视为无 PC
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("刚才那篇讲了什么", page("abc"), 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(2L);
    }

    @Test
    void othersPublishedAnchorIsReadableForQa() {
        // readable 语义：锚里他人公开文章也能续问（owned resolve 会挡，QA 必须放行）
        stubAnchor(article(7L, 999L, "他人的公开文章"));

        QaTarget target = resolver.resolve("那篇讲了什么", null, 10L, 100L);

        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getAuthorId()).isEqualTo(999L);
    }

    // ===================== 跨级指代（2026-10-18）：单锚下无解 → 不猜 =====================

    @Test
    void crossLevelWordsAreDetected() {
        assertThat(ArticleQaTargetResolver.isCrossLevel("再前面那篇讲了什么")).isTrue();
        assertThat(ArticleQaTargetResolver.isCrossLevel("再往前那篇文章")).isTrue();
        assertThat(ArticleQaTargetResolver.isCrossLevel("更早那篇的观点")).isTrue();
        assertThat(ArticleQaTargetResolver.isCrossLevel("上上篇讲了什么")).isTrue();
        assertThat(ArticleQaTargetResolver.isCrossLevel("倒数第二篇是什么")).isTrue();
    }

    @Test
    void crossLevelRequiresArticleDeixisTogether() {
        // "更早说的那个观点" = 对话内容追问（无文章指代）→ 不判跨级（同 S2 的同现约束）
        assertThat(ArticleQaTargetResolver.isCrossLevel("你更早说的那个观点再讲讲")).isFalse();
        assertThat(ArticleQaTargetResolver.isCrossLevel("再往前看看")).isFalse();
        assertThat(ArticleQaTargetResolver.isCrossLevel(null)).isFalse();
        assertThat(ArticleQaTargetResolver.isCrossLevel("  ")).isFalse();
    }

    @Test
    void previousArticleWordingIsNotCrossLevel() {
        // 「前一篇 / 上一篇」刻意不收：它可能就是"上一轮说的那篇"（= 最近锚），
        // 收进来会把正常近指误伤成追问
        assertThat(ArticleQaTargetResolver.isCrossLevel("前一篇讲了什么")).isFalse();
        assertThat(ArticleQaTargetResolver.isCrossLevel("刚才那篇讲了什么")).isFalse();
    }

    @Test
    void crossLevelWithOnlyAnchorPromptsInsteadOfSilentlyUsingAnchor() {
        // 本条修的就是旧失败模式：以前"再前面那篇"含"那篇" → S3 + 唯一候选（锚）→ 静默返回最近那篇
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("再前面那篇讲了什么", null, 10L, 100L);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).contains("再前面");
        assertThat(target.promptNote()).contains("会话里的 A");
        assertThat(target.promptNote()).contains("不要回答任何文章内容");
    }

    @Test
    void crossLevelWithOnlyPagePromptsInsteadOfSilentlyUsingPage() {
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(null);

        QaTarget target = resolver.resolve("再前面那篇讲了什么", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).contains("当前页 B");
        assertThat(target.promptNote()).contains("不要回答任何文章内容");
    }

    @Test
    void crossLevelWithBothCandidatesDoesNotOfferTwoWayChoice() {
        stubPage(1L, article(1L, 100L, "当前页 B"));
        stubAnchor(article(2L, 100L, "会话里的 A"));

        QaTarget target = resolver.resolve("上上篇讲了什么", page("1"), 10L, 100L);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).contains("当前页 B").contains("会话里的 A");
        // 与 S3 双候选追问（askWhich）不同：不问"是哪一篇"——用户要的第三个本来就不在候选里，
        // 摆出两个错候选让他选只会更糟；只引导他说出标题
        assertThat(target.promptNote()).doesNotContain("确认指哪一篇");
        assertThat(target.promptNote()).contains("标题");
    }

    @Test
    void crossLevelWithNoCandidateAlsoPrompts() {
        stubPage(999L, null);
        stubAnchor(null);

        QaTarget target = resolver.resolve("再前面那篇讲了什么", page("999"), 10L, 100L);

        assertThat(target.hasArticle()).isFalse();
        assertThat(target.promptNote()).contains("再前面");
    }

    // ===================== 序位指代（2026-10-19：修复「详细总结第一篇」无反应） =====================

    private void stubRagCandidates(ArticleSessionAnchorService.RagCandidate... candidates) {
        when(anchorService.resolveRagArticleCandidates(10L, 100L))
                .thenReturn(java.util.List.of(candidates));
    }

    private static ArticleSessionAnchorService.RagCandidate candidate(Long articleId, String title) {
        return new ArticleSessionAnchorService.RagCandidate(articleId, title);
    }

    @Test
    void positionalReferenceMapsToRagCandidateOrder() {
        stubRagCandidates(candidate(21L, "第一篇"), candidate(22L, "第二篇"));
        when(anchorService.loadReadable(22L, 100L)).thenReturn(article(22L, 100L, "第二篇"));

        QaTarget target = resolver.resolvePositional("详细总结第二篇文章", null, 10L, 100L);

        assertThat(target).isNotNull();
        assertThat(target.hasArticle()).isTrue();
        assertThat(target.article().getId()).isEqualTo(22L);
    }

    @Test
    void positionalReferenceFirstMapsToHead() {
        stubRagCandidates(candidate(21L, "第一篇"), candidate(22L, "第二篇"));
        when(anchorService.loadReadable(21L, 100L)).thenReturn(article(21L, 100L, "第一篇"));

        QaTarget target = resolver.resolvePositional("详细总结第一篇", null, 10L, 100L);

        assertThat(target).isNotNull();
        assertThat(target.article().getId()).isEqualTo(21L);
    }

    @Test
    void positionalReferenceDoesNotStealDetailPage() {
        // 详情页有文章时不抢：「这篇」语义优先（bug 场景是首页/聊天窗）
        QaTarget target = resolver.resolvePositional("总结第一篇", page("1"), 10L, 100L);
        assertThat(target).isNull();
    }

    @Test
    void positionalReferenceOutOfRangeDoesNotGuess() {
        stubRagCandidates(candidate(21L, "唯一一篇"));
        assertThat(resolver.resolvePositional("总结第五篇", null, 10L, 100L)).isNull();
    }

    @Test
    void positionalReferenceLastResolvesToTail() {
        stubRagCandidates(candidate(21L, "a"), candidate(22L, "b"));
        when(anchorService.loadReadable(22L, 100L)).thenReturn(article(22L, 100L, "b"));

        QaTarget target = resolver.resolvePositional("总结最后一篇", null, 10L, 100L);

        assertThat(target).isNotNull();
        assertThat(target.article().getId()).isEqualTo(22L);
    }

    @Test
    void positionalReferenceIgnoresUnreadableCandidate() {
        // 候选已删/转私有：loadReadable 返回 null → 不猜（走原路由让用户澄清）
        stubRagCandidates(candidate(21L, "已删文章"));
        assertThat(resolver.resolvePositional("总结第一篇", null, 10L, 100L)).isNull();
    }

    @Test
    void positionalReferenceRequiresSession() {
        // 游客没有会话锚
        assertThat(resolver.resolvePositional("总结第一篇", null, null, 100L)).isNull();
    }

    @Test
    void positionalIndexParserCoversChineseAndArabicNumbers() {
        assertThat(ArticleQaTargetResolver.parsePositionalIndex("详细总结第一篇")).isEqualTo(1);
        assertThat(ArticleQaTargetResolver.parsePositionalIndex("总结第 2 篇")).isEqualTo(2);
        assertThat(ArticleQaTargetResolver.parsePositionalIndex("第三篇讲了什么")).isEqualTo(3);
        assertThat(ArticleQaTargetResolver.parsePositionalIndex("展开最后一篇")).isEqualTo(-1);
        // 必须带「篇」：「第三条」是对话内容追问，不是文章序位
        assertThat(ArticleQaTargetResolver.parsePositionalIndex("第三条是什么")).isNull();
        assertThat(ArticleQaTargetResolver.parsePositionalIndex("总结一下")).isNull();
        assertThat(ArticleQaTargetResolver.parsePositionalIndex(null)).isNull();
    }
}
