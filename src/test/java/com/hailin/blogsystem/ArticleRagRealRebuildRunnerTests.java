package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.index.ArticleRagRebuildService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 ES / embedding 操作门。默认不运行；显式传 -DrunRealRagRebuild=true 才执行。
 * 该测试只创建新物理索引并切换 article_rag_active，不删除旧共享索引。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/blog_system?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf-8&zeroDateTimeBehavior=convertToNull&useSSL=false&allowPublicKeyRetrieval=true",
        "spring.datasource.username=myRoot",
        "spring.datasource.password=123456",
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.sql.init.mode=never",
        "blog.ai.rag.corpus.article-index-name=article_rag_active",
        "blog.ai.rag.corpus.memory-index-name=spring-ai-document-index",
        "blog.ai.rag.corpus.initialize-schema=false"
})
@EnabledIfSystemProperty(named = "runRealRagRebuild", matches = "true")
class ArticleRagRealRebuildRunnerTests {

    @Autowired
    private ArticleRagRebuildService rebuildService;

    @Autowired
    private ArticleRagIndexHealthService healthService;

    @Test
    void rebuildsIsolatedArticleIndexAndSwitchesAlias() throws Exception {
        ArticleRagRebuildService.RebuildOutcome outcome = rebuildService.rebuild();

        assertThat(outcome.physicalIndexName()).startsWith("article-rag-");
        assertThat(outcome.chunkCount()).isGreaterThan(0);
        assertThat(healthService.current().state()).isEqualTo(ArticleRagIndexHealthService.State.HEALTHY);
        assertThat(healthService.current().canaryScore()).isGreaterThan(0.9D);
    }
}
