package com.hailin.blogsystem.search;

import com.hailin.blogsystem.search.ArticleSearchService.Highlight;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 搜索高亮拆分的单测。
 *
 * <p>{@code pickHighlight} 是纯函数（吃 ES 的高亮 map），所以不需要 ES 客户端就能覆盖各分支。
 *
 * <p><b>为什么要专门锁住这些行为</b>：高亮字段没有集成测试覆盖——
 * ES 不可用时整条链路会静默降级到 LIKE，高亮为空是"正常降级"而不是失败，
 * 所以「标题命中了但摘要行一个 em 都没有」这种退化不会让任何现有测试变红。
 */
class ArticleSearchHighlightTests {

    @Test
    void keepsFragmentNullWhenOnlyTitleMatches() {
        Highlight highlight = ArticleSearchService.pickHighlight(
                Map.of("title", List.of("Redis <em>缓存穿透</em> 实战")));

        assertThat(highlight).isNotNull();
        assertThat(highlight.title()).isEqualTo("Redis <em>缓存穿透</em> 实战");
        // 核心断言：**不回退成标题**。摘要里没命中就该让前端显示原摘要，
        // 而不是把标题当片段塞进摘要行（上一版的行为）
        assertThat(highlight.fragment()).isNull();
    }

    @Test
    void keepsTitleNullWhenOnlyBodyMatches() {
        Highlight highlight = ArticleSearchService.pickHighlight(
                Map.of("content", List.of("……布隆过滤器可以挡住<em>缓存穿透</em>……")));

        assertThat(highlight).isNotNull();
        assertThat(highlight.title()).isNull();
        assertThat(highlight.fragment()).isEqualTo("……布隆过滤器可以挡住<em>缓存穿透</em>……");
    }

    @Test
    void returnsBothWhenTitleAndSummaryMatch() {
        Highlight highlight = ArticleSearchService.pickHighlight(Map.of(
                "title", List.of("<em>Redis</em> 实战"),
                "summary", List.of("一篇讲 <em>Redis</em> 的文章")));

        assertThat(highlight.title()).isEqualTo("<em>Redis</em> 实战");
        assertThat(highlight.fragment()).isEqualTo("一篇讲 <em>Redis</em> 的文章");
    }

    @Test
    void prefersSummaryOverContentForFragment() {
        Highlight highlight = ArticleSearchService.pickHighlight(Map.of(
                "summary", List.of("摘要里的<em>命中</em>"),
                "content", List.of("正文里的<em>命中</em>")));

        // 摘要比正文靠前，信息密度也更高（正文片段是 120 字符的随机切片）
        assertThat(highlight.fragment()).isEqualTo("摘要里的<em>命中</em>");
    }

    @Test
    void picksFirstFragmentWhenSameFieldMatchesMultipleTimes() {
        Highlight highlight = ArticleSearchService.pickHighlight(
                Map.of("content", List.of("第一处<em>命中</em>", "第二处<em>命中</em>")));

        // 卡片位置有限，多片段是搜索页/详情页的形态；这里只取第一处
        assertThat(highlight.fragment()).isEqualTo("第一处<em>命中</em>");
    }

    @Test
    void returnsNullWhenNothingMatched() {
        assertThat(ArticleSearchService.pickHighlight(null)).isNull();
        assertThat(ArticleSearchService.pickHighlight(Map.of())).isNull();
        // 有 key 但片段列表是空的：ES 有时会带上空列表，等价于没命中
        assertThat(ArticleSearchService.pickHighlight(Map.of("title", List.of()))).isNull();
    }

    @Test
    void returnsTitleOnlyWhenContentFieldIsEmptyList() {
        Highlight highlight = ArticleSearchService.pickHighlight(Map.of(
                "title", List.of("<em>Redis</em>"),
                "content", List.of()));

        assertThat(highlight.title()).isEqualTo("<em>Redis</em>");
        assertThat(highlight.fragment()).isNull();
    }
}
