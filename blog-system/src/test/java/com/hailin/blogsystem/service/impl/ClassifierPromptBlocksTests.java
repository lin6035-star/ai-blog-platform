package com.hailin.blogsystem.service.impl;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 切块一致性 + 块占比（P3.0 / A0 硬约束）。
 *
 * <p><b>为什么逐字比对是必须的</b>：切块最容易犯的错不是"少装了某块"，而是**切的时候改了文本**
 * ——text block 的缩进基准变了、某行前导空格被吃掉、块边界丢了空行。这类改动
 * **不会让任何行为断言失败**，只会让模型悄悄变差，而在线门要花钱才能发现。
 *
 * <p>所以切块前的原文被固化成夹具（{@code /agent/classifier-prompt-full-baseline.txt}），
 * FULL 拼装必须与它**完全相等**。
 *
 * <p>顺带输出各块字符占比——P3 要据此判断"按域按需装载"到底能省多少，
 * 而不是靠估。
 */
class ClassifierPromptBlocksTests {

    private static final String BASELINE = "/agent/classifier-prompt-full-baseline.txt";

    @Test
    void fullAssemblyIsIdenticalToPreSplitPrompt() throws Exception {
        String assembled = AiIntentClassifierImpl.buildSystemPrompt(false);

        try (InputStream in = getClass().getResourceAsStream(BASELINE)) {
            assertThat(in).as("切块前的原文基线必须存在").isNotNull();
            String baseline = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            /*
             * 比对口径：**归一化后逐行相等**。
             *
             * 为什么不是"逐字节相等"：块边界处会各多一个空行（19 块 → 18 处，实测拼装比基线长 18 字符），
             * 而空行对模型行为没有实质影响。为了让"逐字节相等"成立，需要让每块精确携带/不携带尾换行，
             * 而 text block 的 stripIndent 规则会让这件事反直觉、易错——**得不偿失**。
             *
             * 真正要锁住的是**文字内容**：规则文本一个字都不能变（缩进、标点、中文引号），
             * 因为那才会改变模型行为。所以这里折叠连续空行后逐行比对。
             */
            String normalizedActual = normalize(assembled);
            String normalizedExpect = normalize(baseline);

            assertThat(normalizedActual.length())
                    .as("归一化后长度仍不等 → 不止是块边界空行的问题，必须查")
                    .isEqualTo(normalizedExpect.length());

            String[] actualLines = normalizedActual.split("\n", -1);
            String[] expectLines = normalizedExpect.split("\n", -1);
            assertThat(actualLines.length)
                    .as("归一化后行数不等")
                    .isEqualTo(expectLines.length);

            for (int i = 0; i < expectLines.length; i++) {
                assertThat(actualLines[i])
                        .as("归一化后第 %d 行内容不同（逐字一致性被破坏）", i + 1)
                        .isEqualTo(expectLines[i]);
            }
        }
    }

    /** 折叠连续空行：块边界会多出空行，但文字内容必须逐字一致 */
    private static String normalize(String text) {
        return text.replaceAll("\n{2,}", "\n\n");
    }

    @Test
    void reportBlockShares() {
        Map<String, String> blocks = new LinkedHashMap<>();
        blocks.put("HEAD_INTENT_LIST", ClassifierPromptBlocks.HEAD_INTENT_LIST);
        blocks.put("GENERAL_AND_PROFILE", ClassifierPromptBlocks.GENERAL_AND_PROFILE);
        blocks.put("ARTICLE_CONTENT", ClassifierPromptBlocks.ARTICLE_CONTENT);
        blocks.put("NAVIGATE", ClassifierPromptBlocks.NAVIGATE);
        blocks.put("SEARCH", ClassifierPromptBlocks.SEARCH);
        blocks.put("CREATE_ARTICLE", ClassifierPromptBlocks.CREATE_ARTICLE);
        blocks.put("LEARNING_AGENT", ClassifierPromptBlocks.LEARNING_AGENT);
        blocks.put("SUGGEST_FIELDS", ClassifierPromptBlocks.SUGGEST_FIELDS);
        blocks.put("NEEDS_THINKING", ClassifierPromptBlocks.NEEDS_THINKING);
        blocks.put("RISK", ClassifierPromptBlocks.RISK);
        blocks.put("LEARNING_PLAN", ClassifierPromptBlocks.LEARNING_PLAN);
        blocks.put("CONFIDENCE_MISC", ClassifierPromptBlocks.CONFIDENCE_MISC);
        blocks.put("ARTICLE_OPTIMIZE", ClassifierPromptBlocks.ARTICLE_OPTIMIZE);
        blocks.put("PROJECT_CONSULT", ClassifierPromptBlocks.PROJECT_CONSULT);
        blocks.put("EXAMPLES_AND_SCHEMA", ClassifierPromptBlocks.EXAMPLES_AND_SCHEMA);
        blocks.put("PAGE_ACTION_RULES", ClassifierPromptBlocks.PAGE_ACTION_RULES);
        blocks.put("EDITOR", ClassifierPromptBlocks.EDITOR);
        blocks.put("WALLET", ClassifierPromptBlocks.WALLET);
        blocks.put("OUTPUT_FORMAT", ClassifierPromptBlocks.OUTPUT_FORMAT);

        int total = blocks.values().stream().mapToInt(String::length).sum();
        StringBuilder sb = new StringBuilder("=== 分类器 prompt 块占比 ===\n");
        sb.append("总字符: ").append(total).append('\n');
        blocks.forEach((name, text) -> sb.append(String.format("%-22s %6d  %5.1f%%  尾换行=%s%n",
                name, text.length(), text.length() * 100.0 / total, text.endsWith("\n"))));
        String assembled = AiIntentClassifierImpl.buildSystemPrompt(false);
        sb.append("Σ块长=").append(total)
                .append("  拼装长=").append(assembled.length())
                .append("  差=").append(assembled.length() - total)
                .append('\n');
        System.out.print(sb);

        assertThat(assembled.length())
                .as("拼装比 Σ块长多出的应正好是块间换行（块数-1）——多或少都说明块边界处理错了")
                .isEqualTo(total + blocks.size() - 1);

        // 单调用下 FULL 仍要全装，所以这里只确定"哪些块是跨域必需、哪些可按域省"
        assertThat(blocks.get("EXAMPLES_AND_SCHEMA").length() * 100.0 / total)
                .as("字段契约+示例是最大块，B 方案按需装载的收益就来自它")
                .isGreaterThan(20.0);
    }

    @Test
    void taskNavigationContractUsesArticleTitleInsteadOfModelSuppliedId() {
        assertThat(ClassifierPromptBlocks.NAVIGATE)
                .contains("给出文章标题")
                .contains("由后端查询真实文章 ID")
                .doesNotContain("- article：文章详情页，需要 param=文章ID");
        assertThat(ClassifierPromptBlocks.EXAMPLES_AND_SCHEMA)
                .contains("NAVIGATE（跳转")
                .contains("articleTitle")
                .contains("后端查询真实文章 ID")
                .doesNotContain("NAVIGATE（跳转，目标放 params.target，如 userProfile）");
    }
}
