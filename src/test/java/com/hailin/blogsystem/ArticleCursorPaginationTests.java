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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 首页游标分页（keyset）的正确性。
 *
 * <p><b>为什么必须专门测"不重不漏"</b>：游标分页把"跳过 N 行"换成了"从某一行之后继续取"，
 * 而"从哪一行之后"完全依赖**排序键的全序**。只要 tiebreaker（这里是 {@code id}）漏了、
 * 或者行值比较写反，症状就是**静默地跳条或重复**——页面不报错、日志没有异常，
 * 用户只会觉得"有几篇没看到"。这类问题没有别的发现途径，只能靠翻完一遍再比对。
 *
 * <p>断言方式刻意做成"跟着游标一路翻到底，收集中间所有 id"，而不是"翻到第 N 页看它等于什么"：
 * 前者与测试库里有什么数据无关（H2 是多个测试类共享的库），只要求"我造的这几篇被恰好看到一次"。
 */
@SpringBootTest
class ArticleCursorPaginationTests {

    private static final int PROBE_COUNT = 5;
    /** 翻页时每页 2 条 —— 故意造出多页，让游标真的用起来 */
    private static final long PAGE_SIZE = 2L;
    /** 翻页上限，防止"游标永远不为 null"的 bug 把用例挂死 */
    private static final int MAX_PAGES = 500;

    private static final String CACHE_KEY_LATEST =
            RedisConstants.ARTICLE_LIST_KEY_PREFIX + "first:size:2:category:all:sort:latest";
    private static final String CACHE_KEY_RECOMMEND =
            RedisConstants.ARTICLE_LIST_KEY_PREFIX + "first:size:2:category:all:sort:recommend";

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 关掉定时任务：它会把 Redis 浏览量写回 DB，翻页中途改排序键会制造假的"漂移" */
    @MockBean
    private ArticleViewCountSyncTask articleViewCountSyncTask;

    private final List<Long> probeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        cleanCache();
        for (int i = 0; i < PROBE_COUNT; i++) {
            Articles article = new Articles();
            article.setTitle("cursor-probe-" + System.nanoTime() + "-" + i);
            article.setSummary("probe");
            article.setContent("probe content");
            article.setAuthorId(1L);
            article.setCategoryId(1L);
            article.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
            // 两个排序键都给成互不相同的值，让"谁在前"是确定的，不会因为并列而掩盖 tiebreaker 的缺失
            article.setViewCount(100_000 + i);
            article.setLikeCount(0);
            article.setFavoriteCount(0);
            article.setCommentCount(0);
            article.setShareCount(0);
            article.setPublishedAt(LocalDateTime.now().minusMinutes(i));
            article.setCreatedAt(LocalDateTime.now());
            article.setUpdatedAt(LocalDateTime.now());
            articlesService.save(article);
            probeIds.add(article.getId());
        }
    }

    @AfterEach
    void tearDown() {
        cleanCache();
        articlesService.removeByIds(probeIds);
        probeIds.clear();
    }

    @Test
    void latestCursorWalksEveryArticleExactlyOnce() {
        assertCursorWalkIsComplete("latest", CACHE_KEY_LATEST);
    }

    @Test
    void recommendCursorWalksEveryArticleExactlyOnce() {
        assertCursorWalkIsComplete("recommend", CACHE_KEY_RECOMMEND);
    }

    /** 游标模式下不算 total（省掉一条 COUNT），所以它是 null——前端也不能拿它判断有没有下一页 */
    @Test
    void cursorModeDoesNotComputeTotal() {
        PageVO<ArticleDetailVO> first = articlesService.getArticles(null, PAGE_SIZE, null, null, "latest");

        assertThat(first.getTotal()).isNull();
        assertThat(first.getPage()).isNull();
    }

    /** 游标是后端签发的不透明 token；客户端造错必须报 40001，而不是静默回到第一页 */
    @Test
    void malformedCursorIsRejected() {
        assertThatThrownBy(() -> articlesService.getArticles("!!!not-base64!!!", PAGE_SIZE, null, null, "latest"))
                .isInstanceOf(IllegalArgumentException.class);

        // 合法 Base64 但内容不是两段数字
        String badParts = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> articlesService.getArticles(badParts, PAGE_SIZE, null, null, "latest"))
                .isInstanceOf(IllegalArgumentException.class);

        // 两段但第二段不是数字
        String badNumber = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("123:abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> articlesService.getArticles(badNumber, PAGE_SIZE, null, null, "latest"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 一路翻到底：每一条只能出现一次，且造的 5 篇必须全部出现。
     *
     * <p>"重复"用 {@code Set.add} 的返回值当场断言（重复的那一刻就红），
     * "遗漏"在翻完之后统一比对——两个方向的失败模式不同，分开断言更好定位。
     */
    private void assertCursorWalkIsComplete(String sort, String cacheKeyToEvict) {
        Set<Long> seen = new LinkedHashSet<>();
        List<Long> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;

        while (pages < MAX_PAGES) {
            PageVO<ArticleDetailVO> page = articlesService.getArticles(cursor, PAGE_SIZE, null, null, sort);
            for (ArticleDetailVO vo : page.getList()) {
                assertThat(seen.add(vo.getId()))
                        .as("游标翻页出现了重复文章：id=%s（sort=%s，第 %s 页）", vo.getId(), sort, pages)
                        .isTrue();
                walked.add(vo.getId());
            }
            // 每次翻页后清掉第一页缓存，确保下一页真的是"按游标查库"查出来的，
            // 而不是被缓存里那份第一页数据糊住
            if (pages == 0) {
                stringRedisTemplate.delete(cacheKeyToEvict);
            }
            pages++;
            cursor = page.getNextCursor();
            if (cursor == null) {
                break;
            }
        }

        assertThat(pages).as("游标始终不为 null，翻页没有终点（sort=%s）", sort).isLessThan(MAX_PAGES);
        assertThat(walked).as("造的 %s 篇必须一篇不漏地被翻到（sort=%s）", PROBE_COUNT, sort)
                .containsAll(probeIds);
    }

    private void cleanCache() {
        stringRedisTemplate.delete(CACHE_KEY_LATEST);
        stringRedisTemplate.delete(CACHE_KEY_RECOMMEND);
    }
}
