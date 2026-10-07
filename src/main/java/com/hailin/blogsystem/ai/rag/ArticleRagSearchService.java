package com.hailin.blogsystem.ai.rag;

import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.entity.dto.ArticleRagContext;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchResult;
import com.hailin.blogsystem.entity.dto.ArticleRagSearchStrategy;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.service.ArticlesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
// 统一封装文章 RAG 搜索策略：向量 + keyword 混合检索 → rerank → MySQL 兜底。
public class ArticleRagSearchService {

    private static final List<String> ARTICLE_SEARCH_STOP_WORDS = List.of(
            "有没有", "关于", "相关", "文章", "内容", "找一下", "搜索", "推荐",
            "一下", "什么", "怎么", "如何", "这个", "那个", "一篇", "几篇",
            "帮我", "请问", "的",
            // 中文语气词，避免污染 keyword 检索
            "啊", "呢", "吗", "吧", "哦", "呀", "哈", "嘛", "欸", "呗", "喽"
    );

    private final ArticleRagRetrieveService articleRagRetrieveService;
    private final ArticleRagRanker articleRagRanker;
    private final ArticlesService articlesService;
    private final ArticleRagKeywordRetriever articleRagKeywordRetriever;
    private final ArticleRagTraceRecorder traceRecorder;
    private final ArticleRagRelevanceGate relevanceGate;

    public ArticleRagSearchService(
            ArticleRagRetrieveService articleRagRetrieveService,
            ArticleRagRanker articleRagRanker,
            ArticlesService articlesService,
            ArticleRagKeywordRetriever articleRagKeywordRetriever,
            ArticleRagTraceRecorder traceRecorder,
            ArticleRagRelevanceGate relevanceGate
    ) {
        this.articleRagRetrieveService = articleRagRetrieveService;
        this.articleRagRanker = articleRagRanker;
        this.articlesService = articlesService;
        this.articleRagKeywordRetriever = articleRagKeywordRetriever;
        this.traceRecorder = traceRecorder;
        this.relevanceGate = relevanceGate;
    }

    public ArticleRagSearchResult search(String question, AiIntent intent){
        long totalStartedAt = System.nanoTime();
        String intentName = intent == null ? null : intent.getIntent();

        if (!shouldSearchArticleRag(intent)) {
            log.info(
                    "RAG 跳过文章检索，intent={}",
                    intent == null ? null : intent.getIntent()
            );
            ArticleRagSearchResult result =
                    new ArticleRagSearchResult(ArticleRagSearchStrategy.EMPTY, List.of());
            recordTrace(new ArticleRagRetrievalTrace(
                    question,
                    intentName,
                    null,
                    false,
                    "INTENT_NOT_ARTICLE_SEARCH",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    result.strategy(),
                    false,
                    false,
                    0L,
                    0L,
                    0L,
                    elapsedMs(totalStartedAt)
            ));
            return result;
        }

        // 1. ARTICLE_SEARCH：向量 + keyword
        long vectorStartedAt = System.nanoTime();
        List<ArticleRagContext> vectorContexts = articleRagRetrieveService.retrieve(question);
        long vectorElapsedMs = elapsedMs(vectorStartedAt);

        String lexicalQuery = null;
        List<ArticleRagContext> keywordContexts = List.of();
        List<ArticleRagContext> contexts = vectorContexts;
        boolean keywordFallbackTriggered = false;
        long keywordElapsedMs = 0L;
        if (isArticleSearch(intent)) {
            lexicalQuery = getArticleSearchKeyword(question, intent);
            long keywordStartedAt = System.nanoTime();
            keywordContexts = articleRagKeywordRetriever.retrieve(lexicalQuery);
            keywordElapsedMs = elapsedMs(keywordStartedAt);
            contexts = mergeContexts(vectorContexts, keywordContexts);

            log.info(
                    "ARTICLE_SEARCH 使用混合检索，keyword={}, keywordFallback={}, vector={}, keywordHit={}, merged={}",
                    lexicalQuery,
                    keywordFallbackTriggered,
                    vectorContexts.size(),
                    keywordContexts.size(),
                    contexts.size()
            );
        }

        String rankQuestion = isArticleSearch(intent) ? lexicalQuery : question;
        long rankStartedAt = System.nanoTime();
        ArticleRagRankResult rankResult = articleRagRanker.rankWithEvidence(rankQuestion, contexts);
        ArticleRagRelevanceGate.Result gateResult = relevanceGate.apply(lexicalQuery, rankResult);
        List<ArticleRagRelevanceGate.Decision> allGateDecisions = new ArrayList<>(gateResult.decisions());
        List<ArticleRagContext> rankedContexts = rankResult.contexts();
        List<ArticleRagContext> acceptedContexts = gateResult.acceptedContexts();

        if (isArticleSearch(intent) && acceptedContexts.isEmpty() && keywordContexts.isEmpty()) {
            keywordFallbackTriggered = true;
            long fallbackStartedAt = System.nanoTime();
            keywordContexts = articleRagKeywordRetriever.retrieveFallback(lexicalQuery);
            keywordElapsedMs += elapsedMs(fallbackStartedAt);
            contexts = mergeContexts(vectorContexts, keywordContexts);
            rankResult = articleRagRanker.rankWithEvidence(rankQuestion, contexts);
            gateResult = relevanceGate.apply(lexicalQuery, rankResult);
            allGateDecisions.addAll(gateResult.decisions());
            rankedContexts = rankResult.contexts();
            acceptedContexts = gateResult.acceptedContexts();
        }
        acceptedContexts = retainCurrentlyPublished(acceptedContexts);
        long rankElapsedMs = elapsedMs(rankStartedAt);


        log.info(
                "RAG {}检索完成，vector={}, ranked={}, accepted={}, keywordFallback={}",
                isArticleSearch(intent) ? "混合" : "向量",
                vectorContexts.size(),
                rankedContexts.size(),
                acceptedContexts.size(),
                keywordFallbackTriggered
        );

        ArticleRagSearchResult result;
        if (!acceptedContexts.isEmpty()) {
            ArticleRagSearchStrategy strategy = isArticleSearch(intent) ? ArticleRagSearchStrategy.HYBRID : ArticleRagSearchStrategy.VECTOR;
            result = new ArticleRagSearchResult(strategy, acceptedContexts);
        } else if(isArticleSearch(intent)){
            //MySQL兜底
            List<ArticleRagContext> fallbackContexts = buildMysqlFallbackContexts(intent);
            List<ArticleRagContext> acceptedFallbackContexts = relevanceGate.apply(
                    lexicalQuery,
                    ArticleRagRankResult.unavailable(fallbackContexts)
            ).acceptedContexts();
            acceptedFallbackContexts = retainCurrentlyPublished(acceptedFallbackContexts);
            if (!acceptedFallbackContexts.isEmpty()) {
                result = new ArticleRagSearchResult(ArticleRagSearchStrategy.MYSQL_FALLBACK, acceptedFallbackContexts);
            } else {
                result = new ArticleRagSearchResult(ArticleRagSearchStrategy.EMPTY, List.of());
            }
        } else {
            result = new ArticleRagSearchResult(ArticleRagSearchStrategy.EMPTY, List.of());
        }

        recordTrace(new ArticleRagRetrievalTrace(
                question,
                intentName,
                lexicalQuery,
                true,
                null,
                ArticleRagRetrievalTrace.candidatesOf(vectorContexts),
                ArticleRagRetrievalTrace.candidatesOf(keywordContexts),
                ArticleRagRetrievalTrace.candidatesOf(contexts),
                ArticleRagRetrievalTrace.candidatesOf(rankedContexts),
                ArticleRagRetrievalTrace.candidatesOf(result.contexts()),
                ArticleRagRetrievalTrace.decisionsOf(allGateDecisions),
                result.strategy(),
                true,
                keywordFallbackTriggered,
                vectorElapsedMs,
                keywordElapsedMs,
                rankElapsedMs,
                elapsedMs(totalStartedAt)
        ));

        return result;
    }

    private boolean isArticleSearch(AiIntent intent) {
        return intent != null && "ARTICLE_SEARCH".equals(intent.getIntent());
    }

    private boolean shouldSearchArticleRag(AiIntent intent) {
        return isArticleSearch(intent);
    }

    private String getArticleSearchKeyword(String question, AiIntent intent) {
        String rawKeyword;
        if (intent != null && intent.getKeyWord() != null && !intent.getKeyWord().isBlank()) {
            rawKeyword = intent.getKeyWord().trim();
        } else {
            rawKeyword = question == null ? "" : question.trim();
        }

        String keyword = rawKeyword;
        for (String stopWord : ARTICLE_SEARCH_STOP_WORDS) {
            keyword = keyword.replace(stopWord, " ");
        }
        keyword = keyword.replaceAll("\\s+", " ").trim();

        if (!keyword.isBlank()) {
            return keyword;
        }
        return rawKeyword;
    }

    private List<ArticleRagContext> buildMysqlFallbackContexts(AiIntent intent) {
        String keyword = intent.getKeyWord();
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        PageVO<ArticleDetailVO> pageResult = articlesService.getArticles(1L, 3L, keyword, null, "lasted");
        if (pageResult == null) {
            return List.of();
        }
        List<ArticleDetailVO> list = pageResult.getList();

        if (list == null || list.isEmpty()) {
            log.info("ARTICLE_SEARCH MySQL 兜底无结果，keyword={}", keyword);
            return List.of();
        }

        log.info("ARTICLE_SEARCH 使用 MySQL 兜底生成引用，keyword={}, count={}", keyword, list.size());

        return list.stream()
                .filter(article -> article.getId() != null)
                .map(article -> new ArticleRagContext(
                        article.getId(),
                        article.getTitle(),
                        0,
                        buildSnippet(article)
                ))
                .toList();
    }

    private String buildSnippet(ArticleDetailVO article) {
        if (article.getSummary() != null && !article.getSummary().isBlank()) {
            return article.getSummary();
        }
        if (article.getContent() != null && !article.getContent().isBlank()) {
            String content = article.getContent();
            return content.length() <= 50 ? content : content.substring(0, 50) + "\n\n[文章内容过长，后半部分已省略]";
        }
        return article.getTitle() == null ? "" : article.getTitle();
    }

    private List<ArticleRagContext> retainCurrentlyPublished(List<ArticleRagContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        List<Long> articleIds = contexts.stream()
                .map(ArticleRagContext::articleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (articleIds.isEmpty()) {
            return List.of();
        }

        try {
            List<Articles> articles = articlesService.listByIds(articleIds);
            Set<Long> publishedIds = (articles == null ? List.<Articles>of() : articles).stream()
                    .filter(article -> Integer.valueOf(1).equals(article.getStatus()))
                    .map(Articles::getId)
                    .collect(Collectors.toSet());
            return contexts.stream()
                    .filter(context -> publishedIds.contains(context.articleId()))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("RAG Gate 后复核文章可见性失败，按 fail-closed 拒绝候选: {}", e.getMessage());
            return List.of();
        }
    }

    private List<ArticleRagContext> mergeContexts(
            List<ArticleRagContext> vectorContexts,
            List<ArticleRagContext> keywordContexts
    ){

        Map<String,ArticleRagContext> merged = new LinkedHashMap<>();

        for (ArticleRagContext context : vectorContexts) {
            merged.putIfAbsent(contextKey(context), context);
        }

        for(ArticleRagContext context : keywordContexts){
            merged.putIfAbsent(contextKey(context), context);
        }

        return new ArrayList<>(merged.values());
    }

    private String contextKey(ArticleRagContext context) {
        return context.articleId() + ":" + context.chunkIndex();
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private void recordTrace(ArticleRagRetrievalTrace trace) {
        try {
            traceRecorder.record(trace);
        } catch (RuntimeException e) {
            log.warn("RAG retrieval trace 记录失败，不影响检索结果: {}", e.getMessage());
        }
    }
}
