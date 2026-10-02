package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.config.BlogAiProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分类器 prompt 模式的**一键回退契约**（P3 激活前，2026-10-01）。
 *
 * <p>开关的价值全在"出事时一定切得回去"，而"切得回去"是可测的：给 FULL 就必须拿到
 * 与切换前**逐字相同**的 prompt。只测"ON_DEMAND 更短"是不够的——那证明不了回退是安全的。
 */
class ClassifierModeSwitchTests {

    @Test
    void fullModeIsIdenticalToLegacyPrompt() {
        assertThat(AiIntentClassifierImpl.systemPromptFor(BlogAiProperties.ClassifierMode.FULL, false,
                "点赞这篇文章，再给我弄个复习路线", "article-detail"))
                .as("FULL 模式必须与切换前逐字相同——否则'回退'只是换了一条新 prompt")
                .isEqualTo(AiIntentClassifierImpl.buildSystemPrompt(false));
    }

    @Test
    void onDemandModeUsesCandidateA() {
        String trimmed = CandidateA.assemble("点赞这篇文章", "article-detail").prompt();

        assertThat(AiIntentClassifierImpl.systemPromptFor(BlogAiProperties.ClassifierMode.ON_DEMAND, false,
                "点赞这篇文章", "article-detail"))
                .as("ON_DEMAND 必须走候选 A 的按需装载").isEqualTo(trimmed);
        assertThat(trimmed.length())
                .as("ON_DEMAND 确实更短").isLessThan(AiIntentClassifierImpl.buildSystemPrompt(false).length());
    }

    @Test
    void onDemandStillFallsBackOnCompoundRequest() {
        assertThat(AiIntentClassifierImpl.systemPromptFor(BlogAiProperties.ClassifierMode.ON_DEMAND, false,
                "点赞这篇文章，再给我弄个复习路线", "article-detail"))
                .as("复合请求在 ON_DEMAND 下也必须回退 FULL（保护多目标编排）")
                .isEqualTo(AiIntentClassifierImpl.buildSystemPrompt(false));
    }

    @Test
    void repairPromptKeepsTheRulesSelectedForThisRequest() {
        String message = "点赞这篇文章";
        String onDemandNormal = AiIntentClassifierImpl.systemPromptFor(
                BlogAiProperties.ClassifierMode.ON_DEMAND, false, message, "article-detail");
        String onDemandRepair = AiIntentClassifierImpl.systemPromptFor(
                BlogAiProperties.ClassifierMode.ON_DEMAND, true, message, "article-detail");

        assertThat(onDemandRepair)
                .as("repair 必须继续携带本轮 ON_DEMAND 实际装载的字段与语义规则")
                .startsWith(onDemandNormal)
                .contains("你上一次的输出不是合法 JSON")
                .contains("拿不准的字段输出 null");

        String fullNormal = AiIntentClassifierImpl.systemPromptFor(
                BlogAiProperties.ClassifierMode.FULL, false, message, "article-detail");
        String fullRepair = AiIntentClassifierImpl.systemPromptFor(
                BlogAiProperties.ClassifierMode.FULL, true, message, "article-detail");

        assertThat(fullRepair)
                .as("FULL repair 必须保留完整分类规则")
                .startsWith(fullNormal)
                .contains("你上一次的输出不是合法 JSON");
        assertThat(onDemandRepair).isNotEqualTo(fullRepair);
    }

    @Test
    void compoundRequestRepairStillUsesFullRules() {
        String message = "点赞这篇文章，再给我弄个复习路线";

        assertThat(AiIntentClassifierImpl.systemPromptFor(
                BlogAiProperties.ClassifierMode.ON_DEMAND, true, message, "article-detail"))
                .isEqualTo(AiIntentClassifierImpl.systemPromptFor(
                        BlogAiProperties.ClassifierMode.FULL, true, message, "article-detail"));
    }

    @Test
    void defaultModeIsOnDemandAfterActivation() {
        assertThat(new BlogAiProperties().getClassifierMode())
                .as("P3 激活后默认应获得按需装载收益；FULL 仍作为显式回退模式保留")
                .isEqualTo(BlogAiProperties.ClassifierMode.ON_DEMAND);
    }
}
