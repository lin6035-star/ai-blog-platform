package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * P2.1 验收：按运行模式裁剪 Article Decider Prompt（Token 设计稿 §8.4）。
 *
 * <p>三道门，缺一不可：
 * <ol>
 *   <li><b>FULL 逐字等价</b>——拆分片段最容易出的事故是「analysis-only 全绿、FULL 悄悄少了一段历史规则」，
 *       那种丢失只有线上真实对话才暴露。所以 FULL normal / repair 必须与改造前夹具逐字相同。</li>
 *   <li><b>结构硬门</b>——analysis-only 下三个禁用动作的声明与规则必须归零（不是"末尾声明禁止"，
 *       而是模型根本看不到）；repair 轮的合法动作列表精确收窄为四项。</li>
 *   <li><b>静态数字门</b>——analysis-only 正常 prompt 相对基线降低至少 30%（§8.4 修订版）。</li>
 * </ol>
 *
 * <p>基线来自 {@link DeciderPromptBaselineDumpTests} 在改造前落盘的夹具（运行时字符串长度，
 * 不是手工分段统计——后者正是设计稿修订前 3723 / 4519 / 5144 三个数字互相打架的原因）。
 */
class ArticleDeciderPromptTests {

    private static final Path DIR = DeciderPromptBaselineDumpTests.DIR;

    /** 分析模式下不允许出现的动作：模型不应该看到它们的存在 */
    private static final List<String> DISABLED_IN_ANALYSIS_ONLY =
            List.of("ASK_USER", "SUGGEST_WORKFLOW", "SUGGEST_WRITE");

    private static final List<String> FULL_ACTIONS = List.of(
            "QUERY_ARTICLE", "QUERY_MEMORY", "SEARCH_RAG",
            "ASK_USER", "FINAL_ANSWER", "SUGGEST_WORKFLOW", "SUGGEST_WRITE");

    private static final List<String> ANALYSIS_ACTIONS = List.of(
            "QUERY_ARTICLE", "QUERY_MEMORY", "SEARCH_RAG", "FINAL_ANSWER");

    /** 改造前实测基线（2026-09-30 落盘夹具时的运行时长度） */
    private static final int ANALYSIS_ONLY_NORMAL_BASELINE = 3986;

    /** §8.4：静态数字门 30% → 裁剪后不得超过基线的 70% */
    private static final double STATIC_GATE = 0.70;

    private ArticleAgentStepDecider full;
    private ArticleAnalysisStepDecider analysisOnly;

    @BeforeEach
    void setUp() {
        full = new ArticleAgentStepDecider(
                mock(ChatClient.Builder.class), new ObjectMapper(), mock(AiJudgeModelSupport.class));
        analysisOnly = new ArticleAnalysisStepDecider(
                mock(ChatClient.Builder.class), new ObjectMapper(), mock(AiJudgeModelSupport.class));
    }

    // ==================== 门 1：FULL 逐字等价（安全网） ====================

    @Test
    @DisplayName("FULL 正常轮除 answerPlan 协议外与改造前逐字一致")
    void fullNormalIsIdenticalToBaseline() throws Exception {
        String expected = fixture(DeciderPromptBaselineDumpTests.FULL_NORMAL)
                .replace("input.answer = 完整的回答文本（中文）",
                        "input.answerPlan = 1-5 条简短的拟回答要点（中文，禁止展开成完整正文）");
        assertIdentical(DeciderPromptBaselineDumpTests.FULL_NORMAL,
                expected, full.buildSystemPrompt(false));
    }

    @Test
    @DisplayName("FULL 修复轮除 answerPlan 约束外与改造前逐字一致")
    void fullRepairIsIdenticalToBaseline() throws Exception {
        String expected = fixture(DeciderPromptBaselineDumpTests.FULL_REPAIR)
                .replace("禁止输出 markdown、代码块或任何解释文字。",
                        "FINAL_ANSWER 必须带非空 input.answerPlan（1-5 条简短要点），禁止使用 input.answer。\n"
                                + "禁止输出 markdown、代码块或任何解释文字。");
        assertIdentical(DeciderPromptBaselineDumpTests.FULL_REPAIR,
                expected, full.buildSystemPrompt(true));
    }

    // ==================== 门 2：结构硬门 ====================

    @Test
    @DisplayName("analysis-only 正常轮与修复轮都不得出现禁用动作")
    void analysisOnlyHidesDisabledActions() {
        for (boolean repair : List.of(false, true)) {
            String prompt = analysisOnly.buildSystemPrompt(repair);
            for (String disabled : DISABLED_IN_ANALYSIS_ONLY) {
                assertThat(prompt)
                        .as("repair=%s：模型不该看到 %s（不是末尾声明禁止，而是根本看不到）", repair, disabled)
                        .doesNotContain(disabled);
            }
        }
    }

    @Test
    @DisplayName("analysis-only 修复轮的合法动作列表精确收窄为四项")
    void analysisOnlyRepairListsExactlyFourActions() {
        String repairPrompt = analysisOnly.buildSystemPrompt(true);
        for (String action : ANALYSIS_ACTIONS) {
            assertThat(repairPrompt).as("修复轮必须保留 %s", action).contains(action);
        }
        assertThat(repairPrompt).contains("actionType 只能是：QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG / FINAL_ANSWER。");
    }

    @Test
    @DisplayName("analysis-only 仍保留定位与只读规则（裁的是动作，不是能力）")
    void analysisOnlyKeepsLocateAndReadOnlyRules() {
        String prompt = analysisOnly.buildSystemPrompt(false);

        // 定位能力：anchorMode / 指代判定 / focus 都必须在——它们是分析段能跑起来的前提
        assertThat(prompt).contains("anchorMode");
        assertThat(prompt).contains("SESSION_LAST");
        assertThat(prompt).contains("指代判定");
        assertThat(prompt).contains("input.focus");
        // 只读四动作的说明
        for (String action : ANALYSIS_ACTIONS) {
            assertThat(prompt).contains(action);
        }
        // 防注入与证据收敛提示
        assertThat(prompt).contains("防注入");
    }

    @Test
    @DisplayName("FULL 仍声明七类动作与写 / Workflow 分界")
    void fullStillDeclaresAllActions() {
        String prompt = full.buildSystemPrompt(false);
        for (String action : FULL_ACTIONS) {
            assertThat(prompt).as("FULL 必须保留 %s", action).contains(action);
        }
        assertThat(prompt).contains("OPTIMIZE_ARTICLE");
        assertThat(prompt).contains("UPDATE_ARTICLE_TITLE");
    }

    @Test
    @DisplayName("Article FINAL_ANSWER 只声明 answerPlan，不要求完整 answer")
    void finalAnswerUsesAnswerPlanContract() {
        for (ArticleAgentStepDecider decider : List.of(full, analysisOnly)) {
            assertThat(decider.buildSystemPrompt(false))
                    .contains("input.answerPlan")
                    .doesNotContain("input.answer = 完整的回答文本");
            assertThat(decider.buildSystemPrompt(true))
                    .contains("FINAL_ANSWER 必须带非空 input.answerPlan")
                    .contains("禁止使用 input.answer");
        }
    }

    // ==================== 门 3：静态数字门 ====================

    @Test
    @DisplayName("analysis-only 静态 prompt 相对基线降低至少 30%")
    void analysisOnlyStaticPromptShrinksAtLeastThirtyPercent() {
        int actual = analysisOnly.buildSystemPrompt(false).length();
        int limit = (int) Math.floor(ANALYSIS_ONLY_NORMAL_BASELINE * STATIC_GATE);

        System.out.println("[p2.1] analysis-only 正常 prompt = " + actual
                + " 字符 / 基线 " + ANALYSIS_ONLY_NORMAL_BASELINE
                + "，降幅 " + String.format("%.1f%%", (1 - (double) actual / ANALYSIS_ONLY_NORMAL_BASELINE) * 100)
                + "（门槛 ≤" + limit + "）");

        assertThat(actual)
                .as("analysis-only 正常 prompt %d 字符，基线 %d，门槛 ≤%d（降 30%%）",
                        actual, ANALYSIS_ONLY_NORMAL_BASELINE, limit)
                .isLessThanOrEqualTo(limit);
    }

    // ==================== helpers ====================

    private String fixture(String name) throws Exception {
        return Files.readString(DIR.resolve(name), StandardCharsets.UTF_8);
    }

    private void assertIdentical(String name, String expected, String actual) {
        if (expected.equals(actual)) {
            return;
        }
        fail("【" + name + "】与基线不一致——" + firstDifference(expected, actual));
    }

    /** 定位第一处差异并给出上下文，收敛比对时不用手工数行 */
    private String firstDifference(String expected, String actual) {
        int max = Math.min(expected.length(), actual.length());
        for (int i = 0; i < max; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                return "首个差异在第 " + i + " 字符\n  期望: "
                        + window(expected, i) + "\n  实际: " + window(actual, i);
            }
        }
        if (expected.length() != actual.length()) {
            String tail = "长度不同（期望 " + expected.length() + "，实际 " + actual.length() + "）";
            if (expected.length() > actual.length()) {
                return tail + "，实际少了：" + window(expected, max);
            }
            return tail + "，实际多了：" + window(actual, max);
        }
        return "内容相同但 equals 为 false（不应发生）";
    }

    private String window(String text, int around) {
        int from = Math.max(0, around - 50);
        int to = Math.min(text.length(), around + 70);
        return "…「" + text.substring(from, to).replace("\n", "\\n") + "」…";
    }
}
