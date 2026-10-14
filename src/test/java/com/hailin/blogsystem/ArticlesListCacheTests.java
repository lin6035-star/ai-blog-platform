package com.hailin.blogsystem;

import com.hailin.blogsystem.service.ArticlesService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ArticlesListCacheTests {

    /** 游标分页只缓存第一页，所以 key 段是 {@code first} 而不是页码 */
    private static final String FIRST_PAGE_CACHE_KEY =
            "article:list:first:size:10:category:all:sort:recommend";
    /** 带关键词的查询从不进缓存，这个 key 永远不该出现 */
    private static final String SEARCH_CACHE_KEY =
            "article:list:first:size:10:category:all:sort:recommend:keyword:Published";

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void cleanRedisKeys() {
        stringRedisTemplate.delete(FIRST_PAGE_CACHE_KEY);
        stringRedisTemplate.delete(SEARCH_CACHE_KEY);
    }

    @Test
    void cachesPublicArticleListWhenKeywordIsBlank() {
        stringRedisTemplate.delete(FIRST_PAGE_CACHE_KEY);

        articlesService.getArticles(null, 10L, null, null, "recommend");

        String json = stringRedisTemplate.opsForValue().get(FIRST_PAGE_CACHE_KEY);

        assertThat(json).isNotBlank();
        assertThat(json).contains("Published Article");
    }

    @Test
    void doesNotCachePublicArticleListWhenSearching() {
        stringRedisTemplate.delete(SEARCH_CACHE_KEY);

        articlesService.getArticles(null, 10L, "Published", null, "recommend");

        String json = stringRedisTemplate.opsForValue().get(SEARCH_CACHE_KEY);

        assertThat(json).isNull();
    }
}
