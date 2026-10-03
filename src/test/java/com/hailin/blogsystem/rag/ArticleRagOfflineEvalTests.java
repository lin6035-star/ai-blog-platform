package com.hailin.blogsystem.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleRagOfflineEvalTests {

    @Test
    void reportsRouteRecallMrrAndNegativeFalseAcceptsWithAbsoluteCounts() {
        List<ArticleRagEvalCase> cases = List.of(
                new ArticleRagEvalCase("p1", "缓存一致性", true, List.of(1L), "PARAPHRASE", ""),
                new ArticleRagEvalCase("p2", "秒杀", true, List.of(2L), "CODE_API", ""),
                new ArticleRagEvalCase("n1", "写诗", false, List.of(), "OUT_OF_DOMAIN", ""),
                new ArticleRagEvalCase("n2", "无关", true, List.of(), "HARD_NEGATIVE", "")
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
}
