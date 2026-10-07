package com.hailin.blogsystem.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.ai.classifier.jev.JevDecisionGateway;
import com.hailin.blogsystem.ai.classifier.jev.JevDecisionResult;
import com.hailin.blogsystem.ai.classifier.jev.JevFastPathMapper;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.dto.AiIntent;
import com.hailin.blogsystem.service.LearningPlansService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JevFastPathClassifierTests {

    private ChatClient.Builder builder;
    private ChatClient.CallResponseSpec callSpec;
    private LearningPlansService learningPlansService;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);
        learningPlansService = mock(LearningPlansService.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenAnswer(inv -> plusResponse());
    }

    @Test
    void plusOnlyDoesNotCallJev() {
        BlogAiProperties properties = properties(BlogAiProperties.ClassifierEngine.PLUS_ONLY);
        JevDecisionGateway gateway = mock(JevDecisionGateway.class);
        AiIntentClassifierImpl classifier = classifier(properties, gateway);

        AiIntent intent = classifier.classify("什么是缓存穿透", null, null, new TokenUsageAccumulator());

        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
        verify(gateway, never()).decide(anyString(), any());
        verify(builder).build();
    }

    @Test
    void acceptedJevResultSkipsPlusAndCountsJevUsage() {
        BlogAiProperties properties = properties(BlogAiProperties.ClassifierEngine.JEV_FAST_PATH);
        JevDecisionGateway gateway = mock(JevDecisionGateway.class);
        when(gateway.decide(anyString(), any())).thenReturn(generalChatResult());
        AiIntentClassifierImpl classifier = classifier(properties, gateway);
        TokenUsageAccumulator usage = new TokenUsageAccumulator();

        AiIntent intent = classifier.classify("什么是缓存穿透", null, null, usage);

        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
        assertThat(intent.getReason()).contains("Jev");
        assertThat(usage.getPromptTokens()).isEqualTo(100);
        assertThat(usage.getCompletionTokens()).isEqualTo(10);
        assertThat(usage.getTotalTokens()).isEqualTo(110);
        assertThat(usage.getCallCount()).isEqualTo(1);
        verify(builder, never()).build();
    }

    @Test
    void compositeJevResultFallsBackToPlusAndCountsBothCalls() {
        BlogAiProperties properties = properties(BlogAiProperties.ClassifierEngine.JEV_FAST_PATH);
        JevDecisionGateway gateway = mock(JevDecisionGateway.class);
        when(gateway.decide(anyString(), any())).thenReturn(compositeResult());
        AiIntentClassifierImpl classifier = classifier(properties, gateway);
        TokenUsageAccumulator usage = new TokenUsageAccumulator();

        AiIntent intent = classifier.classify(
                "点赞这篇文章，再分析一下它写得怎么样", null, null, usage);

        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
        assertThat(usage.getTotalTokens()).isEqualTo(630);
        assertThat(usage.getCallCount()).isEqualTo(2);
        verify(builder).build();
    }

    @Test
    void jevExceptionFallsBackToPlus() {
        BlogAiProperties properties = properties(BlogAiProperties.ClassifierEngine.JEV_FAST_PATH);
        JevDecisionGateway gateway = mock(JevDecisionGateway.class);
        when(gateway.decide(anyString(), any())).thenThrow(new IllegalStateException("timeout"));
        AiIntentClassifierImpl classifier = classifier(properties, gateway);

        AiIntent intent = classifier.classify("你好", null, null, new TokenUsageAccumulator());

        assertThat(intent.getIntent()).isEqualTo("GENERAL_CHAT");
        verify(builder).build();
    }

    private AiIntentClassifierImpl classifier(BlogAiProperties properties, JevDecisionGateway gateway) {
        return new AiIntentClassifierImpl(
                builder,
                new ObjectMapper(),
                learningPlansService,
                new AiJudgeModelSupport(""),
                properties,
                gateway,
                new JevFastPathMapper());
    }

    private static BlogAiProperties properties(BlogAiProperties.ClassifierEngine engine) {
        BlogAiProperties properties = new BlogAiProperties();
        properties.setClassifierEngine(engine);
        properties.getJev().setApiKey("test-key");
        return properties;
    }

    private static JevDecisionResult generalChatResult() {
        return result("general_chat", 0.95, Map.of(), Set.of());
    }

    private static JevDecisionResult compositeResult() {
        return result(
                "article_write",
                0.97,
                Map.of("article_write", 0.97, "article_analysis", 0.96),
                Set.of("article_write", "article_analysis"));
    }

    private static JevDecisionResult result(String primary, double primaryConfidence,
                                            Map<String, Double> goalProbabilities,
                                            Set<String> acceptedGoals) {
        return new JevDecisionResult(
                "typesafe/jev-1.13", "TypeSafe", primary, primaryConfidence,
                Map.of(primary, primaryConfidence), goalProbabilities, acceptedGoals,
                0.10, false, 100, 10, 0.001, 350);
    }

    private static ChatResponse plusResponse() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(500);
        when(usage.getCompletionTokens()).thenReturn(20);
        when(usage.getTotalTokens()).thenReturn(520);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(new Generation(new AssistantMessage(
                "{\"intent\":\"GENERAL_CHAT\",\"suggestedAction\":\"CHAT\",\"risk\":\"LOW\"}")));
        when(response.getMetadata()).thenReturn(metadata);
        return response;
    }
}
