package com.hailin.blogsystem.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagOfflineEvalTests {

    private static final String SEARCH = "ARTICLE_SEARCH";

    @Test
    void reportsRouteRecallMrrAndNegativeFalseAcceptsWithAbsoluteCounts() {
        List<ArticleRagEvalCase> cases = List.of(
                new ArticleRagEvalCase("p1", "缓存一致性", true, SEARCH, List.of(1L), "PARAPHRASE", ""),
                new ArticleRagEvalCase("p2", "秒杀", true, SEARCH, List.of(2L), "CODE_API", ""),
                new ArticleRagEvalCase("n1", "写诗", false, "NONE", List.of(), "OUT_OF_DOMAIN", ""),
                new ArticleRagEvalCase("n2", "无关", true, SEARCH, List.of(), "HARD_NEGATIVE", "")
        );

        ArticleRagOfflineEvalRunner.Report report = new ArticleRagOfflineEvalRunner().run(
                cases,
                testCase -> switch (testCase.id()) {
                    case "p1" -> new ArticleRagOfflineEvalRunner.Evaluation(true, List.of(9L, 1L));
                    case "p2" -> new ArticleRagOfflineEvalRunner.Evaluation(true, List.of(2L));
                    case "n1" -> new ArticleRagOfflineEvalRunner.Evaluation(false, List.of());
                    default -> new ArticleRagOfflineEvalRunner.Evaluation(true, List.of(99L));
                }
        );

        assertThat(report.total()).isEqualTo(4);
        assertThat(report.routeCorrect()).isEqualTo(4);
        assertThat(report.recallAt5Hits()).isEqualTo(2);
        assertThat(report.recallAt5Denominator()).isEqualTo(2);
        assertThat(report.recallAt5()).isEqualTo(1.0);
        assertThat(report.mrrAt5()).isEqualTo(0.75);
        assertThat(report.negativeCases()).isEqualTo(2);
        assertThat(report.negativeFalseAccepts()).isEqualTo(1);
        assertThat(report.negativeFalseAcceptRate()).isEqualTo(0.5);
    }

    @Test
    void groupsMetricsByCaseTypeSoEasyCasesCannotHideWeakOnes() {
        List<ArticleRagEvalCase> cases = List.of(
                new ArticleRagEvalCase("t1", "标题一", true, SEARCH, List.of(1L), "TITLE_EXACT", ""),
                new ArticleRagEvalCase("t2", "标题二", true, SEARCH, List.of(2L), "TITLE_EXACT", ""),
                new ArticleRagEvalCase("p1", "口语提问", true, SEARCH, List.of(3L), "PARAPHRASE", ""),
                new ArticleRagEvalCase("n1", "难负例", true, SEARCH, List.of(), "HARD_NEGATIVE", "")
        );

        Map<String, ArticleRagOfflineEvalRunner.Report> reports =
                new ArticleRagOfflineEvalRunner().runGrouped(
                        cases,
                        testCase -> switch (testCase.id()) {
                            case "t1" -> new ArticleRagOfflineEvalRunner.Evaluation(true, List.of(1L));
                            case "t2" -> new ArticleRagOfflineEvalRunner.Evaluation(true, List.of(2L));
                            case "p1" -> new ArticleRagOfflineEvalRunner.Evaluation(true, List.of());
                            // 难负例被误放：返回了不相干候选
                            default -> new ArticleRagOfflineEvalRunner.Evaluation(true, List.of(99L));
                        }
                );

        // 总体 Recall 是 2/3 = 0.667，看上去还行；分类别才暴露"口语一条都没召回"。
        assertThat(reports).containsOnlyKeys("TITLE_EXACT", "PARAPHRASE", "HARD_NEGATIVE");
        assertThat(reports.get("TITLE_EXACT").total()).isEqualTo(2);
        assertThat(reports.get("TITLE_EXACT").recallAt5()).isEqualTo(1.0);
        assertThat(reports.get("PARAPHRASE").recallAt5()).isZero();
        assertThat(reports.get("HARD_NEGATIVE").negativeFalseAcceptRate()).isEqualTo(1.0);
        assertThat(ArticleRagOfflineEvalRunner.summaryOf(reports)).contains("TITLE_EXACT").contains("PARAPHRASE");
    }
}
