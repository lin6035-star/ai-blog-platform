package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.config.BlogAiProperties;
import com.hailin.blogsystem.entity.AiPrompt;
import com.hailin.blogsystem.ai.tool.AiArticleTools;
import com.hailin.blogsystem.ai.tool.AiLearningDashboardTool;
import com.hailin.blogsystem.ai.tool.WalletBalanceTool;
import com.hailin.blogsystem.service.impl.AiModelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelServiceToolUsageTests {

    private ChatClient.Builder builder;
    private ChatClient client;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.StreamResponseSpec streamSpec;
    private ChatClient.CallResponseSpec callSpec;
    private AiModelServiceImpl service;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        client = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        streamSpec = mock(ChatClient.StreamResponseSpec.class);
        callSpec = mock(ChatClient.CallResponseSpec.class);

        when(builder.defaultSystem(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.tools(any(Object[].class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(org.springframework.ai.tool.ToolCallback[].class)))
                .thenReturn(requestSpec);
        when(requestSpec.toolContext(anyMap())).thenReturn(requestSpec);
        when(requestSpec.options(any())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(requestSpec.call()).thenReturn(callSpec);

        BlogAiProperties properties = new BlogAiProperties();
        properties.setSystemPrompt("");
        service = new AiModelServiceImpl(
                builder,
                properties,
                mock(AiArticleTools.class),
                mock(AiLearningDashboardTool.class),
                mock(WalletBalanceTool.class)
        );
    }

    @Test
    void streamingToolAggregationFallbackAccumulatesBothProviderCalls() {
        when(streamSpec.chatResponse()).thenAnswer(invocation -> Flux.concat(
                Flux.just(response("", 100, 10, 110)),
                Flux.error(new IllegalStateException("toolName cannot be null or empty"))
        ));
        when(callSpec.chatResponse()).thenAnswer(invocation -> response("重跑成功", 200, 30, 230));

        TokenUsageAccumulator usage = new TokenUsageAccumulator();
        List<String> chunks = service.streamChat(
                        AiPrompt.builder()
                                .finalPromptContext("查询我的学习计划")
                                .userMessage("查询我的学习计划")
                                .build(),
                        "tool-fallback-test",
                        usage)
                .collectList()
                .block();

        assertThat(chunks).containsExactly("重跑成功");
        assertThat(usage.getTotalTokens())
                .as("流式失败调用和非流式重跑是两次独立请求，必须累计而不是取峰值")
                .isEqualTo(340);
    }

    private static ChatResponse response(String text, int prompt, int completion, int total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);
        when(response.getResult()).thenReturn(new Generation(new AssistantMessage(text)));
        return response;
    }
}
