package com.hailin.blogsystem.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * P0 离线评测计算器。它不绑定 Spring/ES，真实运行时由外层显式注入 query evaluator，
 * 这样普通单测不会偷偷访问在线 embedding 或消耗额度。
 */
public final class ArticleRagOfflineEvalRunner {

    public record Evaluation(boolean routedToRag, List<Long> returnedArticleIds) {
        public Evaluation {
            returnedArticleIds = returnedArticleIds == null ? List.of() : List.copyOf(returnedArticleIds);
        }
    }

    public record Report(
            int total,
            int routeCorrect,
            int routedExpected,
            int recallAt5Hits,
            int recallAt5Denominator,
            double recallAt5,
            double mrrAt5,
            int negativeCases,
            int negativeFalseAccepts,
            double negativeFalseAcceptRate
    ) {
        public String summary() {
            return "RAG eval: total=" + total
                    + ", route=" + routeCorrect + "/" + total
                    + ", recall@5=" + recallAt5Hits + "/" + recallAt5Denominator + " (" + recallAt5 + ")"
                    + ", mrr@5=" + mrrAt5
                    + ", negative=" + negativeFalseAccepts + "/" + negativeCases
                    + " (" + negativeFalseAcceptRate + ")";
        }
    }

    public Report run(List<ArticleRagEvalCase> cases,
                      Function<ArticleRagEvalCase, Evaluation> evaluator) {
        if (cases == null || evaluator == null) {
            throw new IllegalArgumentException("cases 和 evaluator 不能为空");
        }

        int routeCorrect = 0;
        int routedExpected = 0;
        int recallHits = 0;
        int recallDenominator = 0;
        double reciprocalRankSum = 0D;
        int negativeCases = 0;
        int negativeFalseAccepts = 0;

        for (ArticleRagEvalCase testCase : cases) {
            Evaluation evaluation = evaluator.apply(testCase);
            if (evaluation.routedToRag() == testCase.shouldRouteToRag()) {
                routeCorrect++;
            }
            if (testCase.shouldRouteToRag()) {
                routedExpected++;
            }

            Set<Long> expected = new LinkedHashSet<>(testCase.expectedArticleIds());
            if (!expected.isEmpty()) {
                recallDenominator++;
                List<Long> top5 = evaluation.returnedArticleIds().stream().limit(5).toList();
                boolean hit = false;
                for (int i = 0; i < top5.size(); i++) {
                    if (expected.contains(top5.get(i))) {
                        hit = true;
                        recallHits++;
                        reciprocalRankSum += 1D / (i + 1);
                        break;
                    }
                }
            }

            if ("HARD_NEGATIVE".equals(testCase.caseType()) || "OUT_OF_DOMAIN".equals(testCase.caseType())) {
                negativeCases++;
                if (evaluation.routedToRag() || !evaluation.returnedArticleIds().isEmpty()) {
                    negativeFalseAccepts++;
                }
            }
        }

        return new Report(
                cases.size(),
                routeCorrect,
                routedExpected,
                recallHits,
                recallDenominator,
                ratio(recallHits, recallDenominator),
                cases.isEmpty() ? 0D : reciprocalRankSum / recallDenominator,
                negativeCases,
                negativeFalseAccepts,
                ratio(negativeFalseAccepts, negativeCases)
        );
    }

    private double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }

    /**
     * 按 {@code caseType} 分组各跑一遍。
     *
     * <p>**不要只看总体指标**：{@code TITLE_EXACT} 的查询就是文章标题原文，向量相似度天然很高，
     * 它在集合里占比一高，总体 Recall 就会被抬到很好看，而 {@code PARAPHRASE}（用户实际说法）
     * 与 {@code HARD_NEGATIVE}（阈值 / Gate 能否拒绝）的问题会被平均掉。
     */
    public Map<String, Report> runGrouped(List<ArticleRagEvalCase> cases,
                                          Function<ArticleRagEvalCase, Evaluation> evaluator) {
        if (cases == null || evaluator == null) {
            throw new IllegalArgumentException("cases 和 evaluator 不能为空");
        }

        Map<String, List<ArticleRagEvalCase>> grouped = new LinkedHashMap<>();
        for (ArticleRagEvalCase testCase : cases) {
            grouped.computeIfAbsent(testCase.caseType(), key -> new ArrayList<>()).add(testCase);
        }

        Map<String, Report> reports = new LinkedHashMap<>();
        grouped.forEach((caseType, group) -> reports.put(caseType, run(group, evaluator)));
        return reports;
    }

    /** 分类别报告的多行输出，便于直接贴进评测报告。 */
    public static String summaryOf(Map<String, Report> reports) {
        StringBuilder builder = new StringBuilder();
        reports.forEach((caseType, report) ->
                builder.append(String.format("%-15s %s%n", caseType, report.summary())));
        return builder.toString();
    }
}
