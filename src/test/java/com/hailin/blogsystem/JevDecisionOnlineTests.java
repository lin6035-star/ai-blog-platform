package com.hailin.blogsystem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import com.hailin.blogsystem.service.impl.JevDecisionClient;
import com.hailin.blogsystem.service.impl.JevDecisionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenRouter Jev 离线候选的真实 API 评测，不接生产路由。
 *
 * <p>显式运行：
 * <pre>
 * .\mvnw.cmd test "-Dtest=JevDecisionOnlineTests" "-DrunRealJev=true" "-DjevRepeat=3"
 * </pre>
 */
@EnabledIfSystemProperty(named = "runRealJev", matches = "true")
class JevDecisionOnlineTests {

    private static final String CASES = "/agent/jev-decision-evaluation-cases.json";
    private static final Path REPORT = Path.of("target", "jev-decision-report.txt");
    private static final int REPEAT = Integer.getInteger("jevRepeat", 3);
    private static final long THROTTLE_MS = Long.getLong("jevThrottleMs", 150L);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evaluateRepresentativeChineseCases() throws Exception {
        String apiKey = System.getenv("JEV_API_KEY");
        assertThat(apiKey).as("真实 Jev 评测需要 JEV_API_KEY").isNotBlank();
        assertThat(REPEAT).as("jevRepeat 必须大于 0").isPositive();

        List<JevEvalCase> cases = narrow(loadCases(), System.getProperty("jevCase"));
        assertThat(cases).as("筛选后至少保留一条 Jev 样例").isNotEmpty();

        JevDecisionClient client = JevDecisionClient.openRouter(apiKey, objectMapper);
        List<RunRow> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        boolean first = true;
        for (JevEvalCase evalCase : cases) {
            for (int attempt = 1; attempt <= REPEAT; attempt++) {
                if (!first && THROTTLE_MS > 0) {
                    Thread.sleep(THROTTLE_MS);
                }
                first = false;

                JevDecisionResult result = client.decide(evalCase.message(), pageContext(evalCase));
                boolean primaryOk = evalCase.expectedPrimary().equals(result.primaryIntent());
                boolean goalsOk = new LinkedHashSet<>(evalCase.expectedGoals())
                        .equals(new LinkedHashSet<>(result.acceptedGoals()));
                boolean thinkingOk = evalCase.expectedNeedsThinking() == null
                        || evalCase.expectedNeedsThinking() == result.needsThinking();
                boolean passed = primaryOk && goalsOk && thinkingOk;
                String signature = result.primaryIntent() + "|"
                        + result.acceptedGoals().stream().sorted().toList() + "|"
                        + result.needsThinking();

                rows.add(new RunRow(evalCase.name(), attempt, passed, signature, result));
                if (!passed) {
                    failures.add(evalCase.name() + "#" + attempt
                            + " expectedPrimary=" + evalCase.expectedPrimary()
                            + " actualPrimary=" + result.primaryIntent()
                            + " expectedGoals=" + evalCase.expectedGoals()
                            + " actualGoals=" + result.acceptedGoals()
                            + " expectedThinking=" + evalCase.expectedNeedsThinking()
                            + " actualThinking=" + result.needsThinking());
                }
                System.out.printf(Locale.ROOT,
                        "[JEV] %-24s #%d %4dms primary=%s(%.2f) goals=%s thinking=%.2f pass=%s%n",
                        evalCase.name(), attempt, result.elapsedMs(), result.primaryIntent(),
                        result.primaryConfidence(), result.acceptedGoals(),
                        result.needsThinkingProbability(), passed);
            }
        }

        for (JevEvalCase evalCase : cases) {
            long signatures = rows.stream()
                    .filter(row -> row.caseName().equals(evalCase.name()))
                    .map(RunRow::signature)
                    .distinct()
                    .count();
            if (signatures != 1) {
                failures.add(evalCase.name() + " repeated decisions are inconsistent");
            }
        }

        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report(cases, rows, failures), StandardCharsets.UTF_8);
        System.out.println("[JEV] report=" + REPORT.toAbsolutePath());

        assertThat(failures)
                .as("Jev 代表性样例失败，详情见 " + REPORT.toAbsolutePath())
                .isEmpty();
    }

    private List<JevEvalCase> loadCases() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(CASES)) {
            assertThat(input).as("Jev 样例文件必须存在").isNotNull();
            return objectMapper.readValue(Objects.requireNonNull(input), new TypeReference<>() {
            });
        }
    }

    private static List<JevEvalCase> narrow(List<JevEvalCase> cases, String filter) {
        if (filter == null || filter.isBlank()) {
            return cases;
        }
        List<String> parts = Arrays.stream(filter.split(","))
                .map(String::trim)
                .filter(part -> !part.isBlank())
                .toList();
        return cases.stream()
                .filter(evalCase -> parts.stream().anyMatch(evalCase.name()::contains))
                .toList();
    }

    private static PageContextDTO pageContext(JevEvalCase evalCase) {
        if (evalCase.pageType() == null) {
            return null;
        }
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType(evalCase.pageType());
        pageContext.setArticleId(evalCase.articleId());
        return pageContext;
    }

    private static String report(List<JevEvalCase> cases, List<RunRow> rows, List<String> failures) {
        List<Long> elapsed = rows.stream().map(row -> row.result().elapsedMs()).sorted().toList();
        long passed = rows.stream().filter(RunRow::passed).count();
        long inputTokens = rows.stream().mapToLong(row -> row.result().inputTokens()).sum();
        long outputTokens = rows.stream().mapToLong(row -> row.result().outputTokens()).sum();
        double cost = rows.stream().mapToDouble(row -> row.result().costUsd()).sum();

        StringBuilder report = new StringBuilder("=== Jev 1.13 representative evaluation ===\n");
        report.append("cases: ").append(cases.size()).append('\n');
        report.append("repeat: ").append(REPEAT).append('\n');
        report.append("runs: ").append(rows.size()).append('\n');
        report.append("pass: ").append(passed).append('/').append(rows.size()).append('\n');
        report.append("P50/P95/max ms: ")
                .append(percentile(elapsed, 0.50)).append('/')
                .append(percentile(elapsed, 0.95)).append('/')
                .append(elapsed.isEmpty() ? 0 : elapsed.get(elapsed.size() - 1)).append('\n');
        report.append("input/output tokens: ").append(inputTokens).append('/')
                .append(outputTokens).append('\n');
        report.append(String.format(Locale.ROOT, "cost USD: %.8f%n", cost));

        report.append("\nper case:\n");
        for (JevEvalCase evalCase : cases) {
            List<RunRow> caseRows = rows.stream()
                    .filter(row -> row.caseName().equals(evalCase.name()))
                    .toList();
            report.append("- ").append(evalCase.name())
                    .append(" pass=").append(caseRows.stream().filter(RunRow::passed).count())
                    .append('/').append(caseRows.size())
                    .append(" signatures=")
                    .append(caseRows.stream().map(RunRow::signature).distinct().toList())
                    .append('\n');
        }

        report.append("\nfailures:\n");
        if (failures.isEmpty()) {
            report.append("- none\n");
        } else {
            failures.forEach(failure -> report.append("- ").append(failure).append('\n'));
        }
        return report.toString();
    }

    private static long percentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(sorted.size() * percentile) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    record JevEvalCase(
            String name,
            String message,
            String pageType,
            String articleId,
            String expectedPrimary,
            List<String> expectedGoals,
            Boolean expectedNeedsThinking
    ) {
    }

    record RunRow(
            String caseName,
            int attempt,
            boolean passed,
            String signature,
            JevDecisionResult result
    ) {
    }
}
