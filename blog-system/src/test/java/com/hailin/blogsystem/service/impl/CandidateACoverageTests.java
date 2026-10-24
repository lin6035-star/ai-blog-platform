package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 候选 A 的**离线覆盖检查**（零成本，64 例评测集全量）。
 *
 * <p><b>它补的是哪一块</b>：26 条 spike 的离线降幅只回答"能省多少"，不回答"会不会判错"。
 * 而按需装载最典型的失败就是**漏装**——该装的规则块没装，模型照着残缺的 prompt 判。
 * 漏装不需要花额度就能发现：期望值（intent / taskGoals / workflowType）指向哪个域，
 * 那个域的块就必须在装载清单里。
 *
 * <p><b>判据的边界</b>：这张"期望值 → 必需块"的映射是**人工判定的粗判据**，
 * 它只能抓"整块没装"这类结构性缺口，抓不到"块装了但规则本身不够"这类语义问题。
 * 所以它是**在线硬门的预筛**，不是替代——在线硬门仍然要跑。
 *
 * <p>跑法（无 Spring 上下文、无额度）：
 * <pre>
 * .\mvnw.cmd test "-Dtest=CandidateACoverageTests"
 * </pre>
 */
class CandidateACoverageTests {

    private static final String EVAL_CASES = "/agent/classifier-evaluation-cases.json";
    private static final Path OUTPUT = Path.of("target", "candidate-a-coverage.txt");

    @Test
    void checkBlockCoverage() throws Exception {
        List<CaseView> cases = read();
        StringBuilder report = new StringBuilder("=== 候选 A 离线覆盖检查（" + cases.size() + " 条数据驱动样例）===\n");
        report.append("判据：期望值 → 必需块的人工映射，只抓「整块没装」的结构性缺口。\n\n");
        report.append(String.format("%-44s %-8s %s%n", "样例", "兜底", "缺失块"));

        int gaps = 0;
        List<String> gapLines = new ArrayList<>();
        for (CaseView c : cases) {
            CandidateA.Assembled assembled = CandidateA.assemble(c.message(), c.pageType());
            Set<String> required = requiredBlocks(c);
            List<String> missing = required.stream()
                    .filter(b -> !assembled.includedBlocks().contains(b))
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                gaps++;
                gapLines.add("  - " + c.name() + "：缺 " + String.join(",", missing)
                        + "（" + assembled.includedBlocks().size() + " 块已装"
                        + (assembled.fullFallback() ? "，FULL 兜底" : "") + "）");
            }
            report.append(String.format("%-44s %-8s %s%n",
                    truncate(c.name(), 44), assembled.fullFallback() ? "FULL" : "-",
                    missing.isEmpty() ? "-" : String.join(",", missing)));
        }

        report.append(String.format("%n缺口：%d / %d 条样例存在缺失块%n", gaps, cases.size()));
        gapLines.forEach(l -> report.append(l).append('\n'));
        if (gaps == 0) {
            report.append("结论：A 在评测集上没有结构性漏装；剩下的只能靠在线硬门验证语义正确性。\n");
        } else {
            report.append("结论：存在结构性漏装——**先补线索再跑在线**，否则在线硬门会用额度告诉我们同一件事。\n");
        }

        System.out.print(report);
        Files.createDirectories(OUTPUT.getParent());
        Files.writeString(OUTPUT, report.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 期望值 → 必需块。
     *
     * <p>只收**确定必需**的映射：宁可漏报，也不要把缺口表变成一堆假阳性——
     * 一张没人信的缺口表，比没有缺口表更糟。
     */
    private static Set<String> requiredBlocks(CaseView c) {
        Set<String> blocks = new LinkedHashSet<>();
        if (c.expectedIntent() != null) {
            addForIntent(blocks, c.expectedIntent());
        }
        // 旧字段：真正启动 Workflow 靠它（新协议是补充，不是替代）
        if (c.expectedSuggestedWorkflowType() != null) {
            addForWorkflowType(blocks, c.expectedSuggestedWorkflowType());
        }
        if (c.expectedTaskGoalWorkflowTypesContains() != null) {
            c.expectedTaskGoalWorkflowTypesContains().forEach(w -> addForWorkflowType(blocks, w));
        }
        if (c.expectedTaskGoalTypesContains() != null) {
            for (String type : c.expectedTaskGoalTypesContains()) {
                if ("ARTICLE_SEARCH".equals(type)) {
                    blocks.add("SEARCH");
                } else if ("NAVIGATE".equals(type)) {
                    blocks.add("NAVIGATE");
                } else if ("ARTICLE_WRITE".equals(type) || "ARTICLE_ANALYSIS".equals(type)) {
                    // 动作规则与"动作 + 分析"的复合判据都在 ARTICLE_CONTENT
                    blocks.add("ARTICLE_CONTENT");
                }
            }
        }
        if (c.expectedRequestedActionsContains() != null && !c.expectedRequestedActionsContains().isEmpty()) {
            blocks.add("ARTICLE_CONTENT");
        }
        return blocks;
    }

    private static void addForIntent(Set<String> blocks, String intent) {
        switch (intent) {
            // ARTICLE_DETAIL_QA 的判定规则（含"跨页指代""单节评价"这些反复调稳的分界）就写在 ARTICLE_CONTENT 里
            case "ARTICLE_ACTION", "ARTICLE_AGENT", "ARTICLE_DETAIL_QA" -> blocks.add("ARTICLE_CONTENT");
            case "ARTICLE_SEARCH" -> blocks.add("SEARCH");
            case "NAVIGATE" -> blocks.add("NAVIGATE");
            case "CREATE_ARTICLE_WORKFLOW" -> blocks.add("CREATE_ARTICLE");
            case "OPTIMIZE_ARTICLE_WORKFLOW" -> blocks.add("ARTICLE_OPTIMIZE");
            case "WALLET_AGENT" -> blocks.add("WALLET");
            case "EDITOR_ACTION" -> {
                blocks.add("EDITOR");
                blocks.add("PAGE_ACTION_RULES");
            }
            case "LEARNING_PLAN_QUERY", "LEARNING_PROGRESS", "LEARNING_ASSIST", "LEARNING_AGENT" -> {
                blocks.add("LEARNING_PLAN");
                blocks.add("LEARNING_AGENT");
            }
            default -> {
                // GENERAL_CHAT / USER_PROFILE_INSIGHT：规则在公共块里，没有额外必需块
            }
        }
    }

    private static void addForWorkflowType(Set<String> blocks, String workflowType) {
        switch (workflowType) {
            case "CREATE_ARTICLE" -> blocks.add("CREATE_ARTICLE");
            case "OPTIMIZE_ARTICLE" -> blocks.add("ARTICLE_OPTIMIZE");
            case "LEARNING_PLAN", "LEARNING_PROGRESS", "LEARNING_ASSIST" -> {
                blocks.add("LEARNING_PLAN");
                blocks.add("LEARNING_AGENT");
            }
            default -> {
            }
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private List<CaseView> read() {
        try (InputStream in = getClass().getResourceAsStream(EVAL_CASES)) {
            ObjectMapper mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return mapper.readValue(Objects.requireNonNull(in), new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new UncheckedIOException(new java.io.IOException(e));
        }
    }

    /** 评测集样例的只读视图——只声明本检查关心的字段，其余忽略 */
    record CaseView(
            String name,
            String message,
            String pageType,
            String expectedIntent,
            List<String> expectedRequestedActionsContains,
            List<String> expectedTaskGoalTypesContains,
            List<String> expectedTaskGoalWorkflowTypesContains,
            String expectedSuggestedWorkflowType) {
    }
}
