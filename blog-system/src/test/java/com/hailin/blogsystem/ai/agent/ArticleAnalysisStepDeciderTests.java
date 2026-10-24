package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 分析版决策器的 prompt 契约（第二刀 §4.7 三层约束的第一层）。
 *
 * <p>放在同包是因为要访问 {@code protected} 的 {@code buildSystemPrompt}——
 * 它本就是为子类覆写而保护的，测试与子类同权；改成 public 反而放宽了生产可见性。
 *
 * <p><b>P2.1（2026-09-30）推翻了旧的折中</b>：以前 prompt 是「完整版 + 末尾追加覆盖声明」，
 * 模型每一步仍能看到 {@code ASK_USER / SUGGEST_WORKFLOW / SUGGEST_WRITE} 的完整规则，
 * 第一层只保证"末尾声明了禁止"，真正兜底靠后两层（白名单拒绝 + Verifier 转问分流）。
 * 当时的理由是"那些说明散落在父类 prompt 十几处、抽不出独立片段"。
 *
 * <p>现在改为**按运行模式组装**（{@link ArticleDeciderPrompts}）：三个禁用动作的声明与规则
 * 根本不装载，连交织在「指代判定 / anchorMode」里的分支也按模式改写。实测 analysis-only
 * 正常 prompt 从 3986 降到 2522 字符（-36.7%，门槛 30%）。
 *
 * <p><b>后两层未变、仍是权威</b>：{@code AgentRunPolicy.allowedActions} 与 Verifier 转问照旧。
 * prompt 裁剪只减少模型看到的无关选择与误选概率，**不承担安全职责**。
 */
class ArticleAnalysisStepDeciderTests {

    private static final List<String> DISABLED_ACTIONS =
            List.of("ASK_USER", "SUGGEST_WORKFLOW", "SUGGEST_WRITE");

    private ArticleAnalysisStepDecider newAnalysisDecider() {
        return new ArticleAnalysisStepDecider(
                mock(ChatClient.Builder.class),
                new ObjectMapper(),
                mock(AiJudgeModelSupport.class));
    }

    private ArticleAgentStepDecider newFullDecider() {
        return new ArticleAgentStepDecider(
                mock(ChatClient.Builder.class),
                new ObjectMapper(),
                mock(AiJudgeModelSupport.class));
    }

    @Test
    void analysisPromptNoLongerCarriesDisabledActionRules() {
        String prompt = newAnalysisDecider().buildSystemPrompt(false);

        // P2.1 前这里断言的是「末尾有覆盖声明」，且明说 prompt 里仍含禁用动作的说明。
        // 现在契约反过来了：模型**看不到**这三个动作，也不再需要末尾声明。
        for (String disabled : DISABLED_ACTIONS) {
            assertThat(prompt)
                    .as("analysis-only 不该出现 %s——不是末尾声明禁止，而是根本看不到", disabled)
                    .doesNotContain(disabled);
        }
        assertThat(prompt).doesNotContain("【本次运行模式：只分析，无副作用】");
        // 正常轮以输出格式行收尾
        assertThat(prompt.trim())
                .endsWith("（anchorMode 仅在需要定位文章时带；可选顶层 \"plan\" 见下）");
    }

    @Test
    void repairPromptAlsoOmitsDisabledActions() {
        String repair = newAnalysisDecider().buildSystemPrompt(true);

        // 修复轮与正常轮**同源**：不能正常轮裁掉了、修复轮又把禁用动作带回来
        for (String disabled : DISABLED_ACTIONS) {
            assertThat(repair).as("修复轮同样不该出现 %s", disabled).doesNotContain(disabled);
        }
        assertThat(repair)
                .contains("actionType 只能是：QUERY_ARTICLE / QUERY_MEMORY / SEARCH_RAG / FINAL_ANSWER。");
    }

    @Test
    void fullDeciderPromptStillCarriesWriteAndWorkflowRules() {
        String prompt = newFullDecider().buildSystemPrompt(false);

        // 完整模式是零语义变更：写动作与 Workflow 的规则必须原样保留（逐字等价由
        // ArticleDeciderPromptTests 用夹具锁，这里只做语义层的对照）
        for (String action : DISABLED_ACTIONS) {
            assertThat(prompt).as("FULL 必须保留 %s", action).contains(action);
        }
        assertThat(prompt).doesNotContain("【本次运行模式：只分析，无副作用】");
    }

    @Test
    void analysisPromptKeepsArticleDomainContext() {
        String prompt = newAnalysisDecider().buildSystemPrompt(false);

        // 裁的是动作，不是能力：同一套文章定位 / 锚点逻辑必须留着
        assertThat(prompt).contains("QUERY_ARTICLE");
        assertThat(prompt).contains("anchorMode");
        assertThat(prompt).contains("指代判定");
    }
}
