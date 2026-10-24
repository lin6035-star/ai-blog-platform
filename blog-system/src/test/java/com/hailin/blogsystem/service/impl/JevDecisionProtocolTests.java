package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JevDecisionProtocolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requestUsesPrimaryChoiceAndIndependentAtomicQuestions() {
        PageContextDTO pageContext = new PageContextDTO();
        pageContext.setPageType("article-detail");
        pageContext.setArticleId("12");

        Map<String, Object> request = JevDecisionProtocol.request(
                "点赞这篇文章，再分析一下它写得怎么样", pageContext);

        assertThat(request.get("model")).isEqualTo("typesafe/jev-1.13");
        assertThat(request.get("state")).isEqualTo(Map.of(
                "message", "点赞这篇文章，再分析一下它写得怎么样",
                "pageContext", Map.of("pageType", "article-detail", "articleId", "12")));

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> questions =
                (Map<String, Map<String, Object>>) request.get("questions");
        assertThat(questions.keySet())
                .containsExactlyInAnyOrderElementsOf(JevDecisionProtocol.questionNames());
        assertThat(questions.get("primary").get("type")).isEqualTo("choice");
        assertThat(questions.get("needs_thinking").get("type")).isEqualTo("noul");
        assertThat(JevDecisionProtocol.goalQuestionNames()).hasSize(13);
        assertThat(JevDecisionProtocol.goalQuestionNames())
                .allSatisfy(name -> assertThat(questions.get(name).get("type")).isEqualTo("noul"));
        assertThat(questions).doesNotContainKeys("risk", "general_chat");
    }

    @Test
    void requestCarriesExplicitEmptyPageContextWhenCallerHasNoPage() {
        Map<String, Object> request = JevDecisionProtocol.request("什么是缓存穿透", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) request.get("state");
        assertThat(state).containsKey("pageContext");
        @SuppressWarnings("unchecked")
        Map<String, Object> pageContext = (Map<String, Object>) state.get("pageContext");
        assertThat(pageContext).containsOnlyKeys("pageType", "articleId");
        assertThat(pageContext.get("pageType")).isNull();
        assertThat(pageContext.get("articleId")).isNull();
    }

    @Test
    void responseKeepsCompositeGoalsAtConservativeThreshold() throws Exception {
        JsonNode response = objectMapper.readTree(responseJson(Map.of(
                "article_write", 0.97,
                "workflow_optimize", 0.70,
                "article_analysis", 0.69)));

        JevDecisionResult result = JevDecisionProtocol.mapResponse(response, 680);

        assertThat(result.primaryIntent()).isEqualTo("article_write");
        assertThat(result.primaryConfidence()).isEqualTo(0.99);
        assertThat(result.acceptedGoals())
                .containsExactlyInAnyOrder("article_write", "workflow_optimize");
        assertThat(result.goalProbabilities().get("article_analysis")).isEqualTo(0.69);
        assertThat(result.needsThinking()).isTrue();
        assertThat(result.needsThinkingProbability()).isEqualTo(0.70);
        assertThat(result.inputTokens()).isEqualTo(2158);
        assertThat(result.outputTokens()).isEqualTo(383);
        assertThat(result.costUsd()).isEqualTo(0.000090636);
        assertThat(result.elapsedMs()).isEqualTo(680);
    }

    @Test
    void responseFailsClosedWhenAnAtomicAnswerIsMissing() throws Exception {
        JsonNode response = objectMapper.readTree(responseJson(Map.of(
                "article_write", 0.97,
                "workflow_optimize", 0.96)));
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("answers"))
                .remove("wallet");

        assertThatThrownBy(() -> JevDecisionProtocol.mapResponse(response, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wallet");
    }

    private String responseJson(Map<String, Double> overrides) throws Exception {
        var root = objectMapper.createObjectNode();
        root.put("model", "typesafe/jev-1.13-20260917");
        root.put("provider", "TypeSafe");
        var answers = root.putObject("answers");
        var primary = answers.putObject("primary");
        primary.put("type", "choice");
        primary.put("choice", "article_write");
        primary.put("confidence", 0.99);
        primary.putObject("probabilities").put("article_write", 0.99);

        for (String goal : JevDecisionProtocol.goalQuestionNames()) {
            answers.putObject(goal)
                    .put("type", "noul")
                    .put("noul", overrides.getOrDefault(goal, 0.02));
        }
        answers.putObject("needs_thinking")
                .put("type", "noul")
                .put("noul", 0.70);
        var usage = root.putObject("usage");
        usage.put("input_tokens", 2158);
        usage.put("output_tokens", 383);
        usage.put("cost", 0.000090636);
        return objectMapper.writeValueAsString(root);
    }
}
