package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.rag.ArticleRagRankResult;
import com.hailin.blogsystem.ai.rag.ArticleRagRelevanceGate;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagRelevanceGateTests {

    private final BlogAiProperties properties = new BlogAiProperties();
    private final ArticleRagRelevanceGate gate = new ArticleRagRelevanceGate(properties);

    @Test
    void acceptsOnlyModelCandidatesAtOrAboveCalibratedThreshold() {
        ArticleRagContext accepted = context(1L, "Redis 缓存");
        ArticleRagContext rejected = context(2L, "Kafka 消息队列");
        ArticleRagRankResult rankResult = new ArticleRagRankResult(
                ArticleRagRankResult.Status.MODEL_APPLIED,
                List.of(
                        new ArticleRagRankResult.Candidate(accepted, 0.75D, 1),
                        new ArticleRagRankResult.Candidate(rejected, 0.7499D, 2)
                )
        );

        ArticleRagRelevanceGate.Result result = gate.apply("缓存一致性", rankResult);

        assertThat(result.acceptedContexts()).containsExactly(accepted);
        assertThat(result.decisions()).extracting(ArticleRagRelevanceGate.Decision::reason)
                .containsExactly(
                        ArticleRagRelevanceGate.Reason.ACCEPT_RERANK,
                        ArticleRagRelevanceGate.Reason.REJECT_LOW_RELEVANCE
                );
    }

    @Test
    void rerankUnavailableOnlyAllowsExactNormalizedTitleEvidence() {
        ArticleRagContext exactTitle = context(1L, "Java中的@Builder注解详解：优雅创建对象的建造者模式");
        ArticleRagContext semanticOnly = context(2L, "Java 语言全景");
        ArticleRagRankResult rankResult = ArticleRagRankResult.unavailable(
                List.of(exactTitle, semanticOnly)
        );

        ArticleRagRelevanceGate.Result result = gate.apply(
                "Java中的@Builder注解详解：优雅创建对象的建造者模式",
                rankResult
        );

        assertThat(result.acceptedContexts()).containsExactly(exactTitle);
        assertThat(result.decisions()).extracting(ArticleRagRelevanceGate.Decision::reason)
                .containsExactly(
                        ArticleRagRelevanceGate.Reason.ACCEPT_STRONG_LEXICAL,
                        ArticleRagRelevanceGate.Reason.REJECT_RERANK_UNAVAILABLE
                );
    }

    /**
     * **本次放宽的核心能力**：标题只是**包含**查询词就放行，不再要求整串相等。
     *
     * <p>原判定是 {@code query.equals(title)} —— 等于从未生效：谁会拿"缓存"两个字当标题？
     * 而标题恰恰是区分"**主题就是它**"和"**顺带提到了它**"最可靠的信号。
     *
     * <p>这里刻意让"顺带提到"的那条**分数更高**（0.7182 > 0.6786，取真实实测值）：
     * 这正是只出 1 篇的成因——《MySQL 核心机制》顺带提了缓存却排前面，
     * 而主题就是缓存的《Redis 缓存工程化实践》被它压在下面、过不了分数门。
     */
    @Test
    void acceptsTitlesThatContainTheQueryInsteadOfRequiringExactMatch() {
        ArticleRagContext themed = context(1L, "Redis 缓存工程化实践：从读写策略到一致性治理");
        ArticleRagContext mentionedOnly = context(2L, "MySQL 核心机制与工程实战：面向进阶开发者的深度解析");

        ArticleRagRankResult rankResult = new ArticleRagRankResult(
                ArticleRagRankResult.Status.MODEL_APPLIED,
                List.of(
                        new ArticleRagRankResult.Candidate(themed, 0.6786D, 1),
                        new ArticleRagRankResult.Candidate(mentionedOnly, 0.7182D, 2)
                )
        );

        ArticleRagRelevanceGate.Result result = gate.apply("缓存", rankResult);

        // 标题含"缓存" → 凭标题证据放行，**哪怕它的分数比另一条更低**
        assertThat(result.acceptedContexts()).containsExactly(themed);
        assertThat(result.decisions()).extracting(ArticleRagRelevanceGate.Decision::reason)
                .containsExactly(
                        ArticleRagRelevanceGate.Reason.ACCEPT_STRONG_LEXICAL,
                        ArticleRagRelevanceGate.Reason.REJECT_LOW_RELEVANCE
                );
    }

    /** 多词查询按空白拆词后**逐词**判断，所以词序无关（整串 contains 会漏掉词序倒置的情况） */
    @Test
    void matchesMultiWordQueriesTokenByTokenRegardlessOfOrder() {
        ArticleRagContext theArticle = context(1L, "Redis 缓存工程化实践");
        ArticleRagRankResult rankResult = ArticleRagRankResult.unavailable(List.of(theArticle));

        // 查询词序与标题相反
        ArticleRagRelevanceGate.Result result = gate.apply("缓存 Redis", rankResult);

        assertThat(result.acceptedContexts()).containsExactly(theArticle);
    }

    /**
     * 查询里残留的标点不该把标题证据判死。
     *
     * <p>不是假想：日志里真的出现过 {@code lexicalQuery=Redis ？} —— 停用词表剔完之后
     * 留下了问号。那个 token 经 normalize 是空串，应当跳过而不是判失败。
     */
    @Test
    void ignoresPunctuationLeftInTheQuery() {
        ArticleRagContext theArticle = context(1L, "Redis 缓存工程化实践");
        ArticleRagRankResult rankResult = ArticleRagRankResult.unavailable(List.of(theArticle));

        ArticleRagRelevanceGate.Result result = gate.apply("Redis 缓存 ？", rankResult);

        assertThat(result.acceptedContexts()).containsExactly(theArticle);
    }

    /** 查询只有标点（normalize 后全空）→ 不构成任何证据，要交回给分数门 */
    @Test
    void doesNotTreatBlankQueryAsTitleEvidence() {
        ArticleRagContext anyArticle = context(1L, "Redis 缓存工程化实践");
        ArticleRagRankResult rankResult = ArticleRagRankResult.unavailable(List.of(anyArticle));

        ArticleRagRelevanceGate.Result result = gate.apply("？？", rankResult);

        assertThat(result.acceptedContexts()).isEmpty();
        assertThat(result.decisions()).extracting(ArticleRagRelevanceGate.Decision::reason)
                .containsExactly(ArticleRagRelevanceGate.Reason.REJECT_RERANK_UNAVAILABLE);
    }

    private ArticleRagContext context(long id, String title) {
        return new ArticleRagContext(id, title, 0, "content");
    }
}
