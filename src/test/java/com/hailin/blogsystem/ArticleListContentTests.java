package com.hailin.blogsystem;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.task.ArticleViewCountSyncTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「列表不带正文、详情必须带正文」这条契约的回归锁。
 *
 * <p><b>为什么需要专门锁它</b>：正文是 LONGTEXT，实测一页 10 篇的 JSON 里它占 92%
 * （53.6 KB → 4.1 KB）。而前端列表卡片一个字都用不到它——{@code ArticleCard} 只渲染
 * title / summary / coverUrl / 四个计数。
 *
 * <p>这个契约有两种破坏方式，代价很不对称：
 * <ul>
 *   <li>列表多带正文 —— <b>静默</b>。响应体和缓存都变胖，但没有测试会红、日志看不出来，
 *       只能等有人去量响应体才发现（本用例就是那次「量」的固化）</li>
 *   <li>详情少带正文 —— <b>显眼</b>。详情页直接空白</li>
 * </ul>
 * 所以 {@code from()} 选择默认不带、详情用 {@code fromDetail()} 显式要：
 * 让"漏改"的后果落在显眼的那一侧。本类同时锁住两个方向，改坏了立刻红。
 */
@SpringBootTest
class ArticleListContentTests {

    private static final String PROBE_CONTENT = "probe content 正文只应该出现在详情里";

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 关掉定时任务：它会把 Redis 浏览量写回 DB，与本用例无关但会制造跨用例的状态漂移 */
    @MockBean
    private ArticleViewCountSyncTask articleViewCountSyncTask;

    private Long probeId;
    private String listKey;

    @BeforeEach
    void setUp() {
        Articles article = new Articles();
        article.setTitle("content-contract-probe-" + System.nanoTime());
        article.setSummary("probe summary");
        article.setContent(PROBE_CONTENT);
        article.setAuthorId(1L);
        article.setCategoryId(1L);
        article.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setFavoriteCount(0);
        article.setCommentCount(0);
        article.setShareCount(0);
        article.setPublishedAt(LocalDateTime.now());
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesService.save(article);

        probeId = article.getId();
        listKey = RedisConstants.ARTICLE_LIST_KEY_PREFIX + "first:size:10:category:all:sort:latest";

        cleanKeys();
    }

    @AfterEach
    void tearDown() {
        cleanKeys();
        if (probeId != null) {
            articlesService.removeById(probeId);
        }
    }

    /** 转换器层面的契约：两个入口的差别**只有**正文 */
    @Test
    void fromOmitsContentWhileFromDetailKeepsIt() {
        Articles article = new Articles();
        article.setId(9L);
        article.setTitle("t");
        article.setSummary("s");
        article.setContent("c");

        ArticleDetailVO listItem = ArticleDetailVO.from(article);
        ArticleDetailVO detail = ArticleDetailVO.fromDetail(article);

        assertThat(listItem.getContent()).isNull();
        assertThat(detail.getContent()).isEqualTo("c");
        // 其余字段必须一致——否则"只是不带正文"就变成了另一种接口语义
        assertThat(detail.getTitle()).isEqualTo(listItem.getTitle());
        assertThat(detail.getSummary()).isEqualTo(listItem.getSummary());
        assertThat(detail.getId()).isEqualTo(listItem.getId());
    }

    /** 列表接口：正文一个字节都不该出现在响应体和列表缓存里 */
    @Test
    void publicListDoesNotCarryArticleContent() {
        PageVO<ArticleDetailVO> page = articlesService.getArticles(null, 10L, null, null, "latest");

        assertThat(page.getList()).isNotEmpty();
        assertThat(page.getList()).allSatisfy(vo -> assertThat(vo.getContent()).isNull());

        // 缓存是这份数据的第二个落点，必须一起瘦——不然省下的只是网络，没省下内存
        String cached = stringRedisTemplate.opsForValue().get(listKey);
        assertThat(cached).isNotNull();
        assertThat(cached).doesNotContain("正文只应该出现在详情里");
    }

    /** 详情接口：正文必须在，且缓存里也要在（否则第二次点击详情页会空白） */
    @Test
    void publicDetailStillCarriesArticleContent() {
        ArticleDetailVO fromDb = articlesService.getPublicArticleById(probeId, "9.9.9.9");

        assertThat(fromDb).isNotNull();
        assertThat(fromDb.getContent()).isEqualTo(PROBE_CONTENT);

        // 第二次读走缓存，正文不能因为"缓存里没存"而丢掉
        ArticleDetailVO fromCache = articlesService.getPublicArticleById(probeId, "9.9.9.9");
        assertThat(fromCache).isNotNull();
        assertThat(fromCache.getContent()).isEqualTo(PROBE_CONTENT);
    }

    private void cleanKeys() {
        if (probeId == null) {
            return;
        }
        stringRedisTemplate.delete(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + probeId);
        stringRedisTemplate.delete(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + probeId);
        stringRedisTemplate.delete(listKey);
        // 详情读取顺带会记 UV，用例跑完一并清掉，别给开发环境的 Redis 留垃圾
        stringRedisTemplate.delete(RedisConstants.ARTICLE_UV_KEY_PREFIX + probeId + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE));
    }
}
