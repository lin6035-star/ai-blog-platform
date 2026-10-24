package com.hailin.blogsystem.ai.classifier.jev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JevDecisionProtocolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void requestCarriesExplicitEmptyPageContext() {
        Map<String, Object> request = JevDecisionProtocol.request("什么是缓存穿透", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) request.get("state");
        @SuppressWarnings("unchecked")
        Map<String, Object> page = (Map<String, Object>) state.get("pageContext");

        assertThat(request.get("model")).isEqualTo("typesafe/jev-1.13");
        assertThat(page).containsOnlyKeys("pageType", "articleId");
        assertThat(page.get("pageType")).isNull();
        assertThat(page.get("articleId")).isNull();
    }

    @Test
    void responseMapsGoalsProbabilitiesAndUsage() throws Exception {
        JsonNode response = objectMapper.readTree(responseJson(Map.of(
                "article_write", 0.97,
                "workflow_optimize", 0.70,
                "article_analysis", 0.69)));

        JevDecisionResult result = JevDecisionProtocol.mapResponse(response, 680);

        assertThat(result.acceptedGoals())
                .containsExactlyInAnyOrder("article_write", "workflow_optimize");
        assertThat(result.goalProbabilities().get("article_analysis")).isEqualTo(0.69);
        assertThat(result.inputTokens()).isEqualTo(2158);
        assertThat(result.outputTokens()).isEqualTo(383);
        assertThat(result.elapsedMs()).isEqualTo(680);
    }

    @Test
    void responseRejectsMissingAtomicAnswer() throws Exception {
        JsonNode response = objectMapper.readTree(responseJson(Map.of()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("answers")).remove("wallet");

        assertThatThrownBy(() -> JevDecisionProtocol.mapResponse(response, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wallet");
    }

    @Test
    void responseRejectsProbabilityOutsideZeroToOne() throws Exception {
        JsonNode response = objectMapper.readTree(responseJson(Map.of()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("answers").path("wallet"))
                .put("noul", 1.01);

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
        primary.put("choice", "article_write");
        primary.put("confidence", 0.99);
        primary.putObject("probabilities").put("article_write", 0.99);
        for (String goal : JevDecisionProtocol.goalQuestionNames()) {
            answers.putObject(goal).put("noul", overrides.getOrDefault(goal, 0.02));
        }
        answers.putObject("needs_thinking").put("noul", 0.70);
        root.putObject("usage")
                .put("input_tokens", 2158)
                .put("output_tokens", 383)
                .put("cost", 0.000090636);
        return objectMapper.writeValueAsString(root);
    }
}
