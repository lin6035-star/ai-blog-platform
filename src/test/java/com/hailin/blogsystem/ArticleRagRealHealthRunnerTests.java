package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagConsistencyService;
import com.hailin.blogsystem.ai.rag.index.ArticleRagIndexHealthService;
import com.hailin.blogsystem.entity.vo.ArticleRagConsistencyReportVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 active alias 的只读巡检门；默认不运行。 */
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
@EnabledIfSystemProperty(named = "runRealRagHealth", matches = "true")
class ArticleRagRealHealthRunnerTests {

    @Autowired
    private ArticleRagIndexHealthService healthService;

    @Autowired
    private ArticleRagConsistencyService consistencyService;

    @Test
    void activeAliasPassesCanaryAndArticleConsistencyChecks() {
        healthService.inspect();
        ArticleRagIndexHealthService.Snapshot health = healthService.current();
        ArticleRagConsistencyReportVO consistency = consistencyService.check();

        System.out.printf(
                "RAG_HEALTH state=%s cosine=%s published=%d indexed=%d missing=%s extra=%s%n",
                health.state(),
                health.canaryScore(),
                consistency.getPublishedArticleCount(),
                consistency.getIndexedArticleCount(),
                consistency.getMissingInEs(),
                consistency.getExtraInEs()
        );

        assertThat(health.state()).isEqualTo(ArticleRagIndexHealthService.State.HEALTHY);
        assertThat(health.canaryScore()).isGreaterThan(0.9D);
        assertThat(consistency.getMissingInEs()).isEmpty();
        assertThat(consistency.getExtraInEs()).isEmpty();
    }
}
