package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.workflow.AiWorkflowStepEmitter;
import com.hailin.blogsystem.ai.workflow.LlmStreamCaller;
import com.hailin.blogsystem.entity.dto.AiWorkflowStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmStreamCallerTests {

    private Scheduler callbackScheduler;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (callbackScheduler != null) {
            callbackScheduler.dispose();
        }
    }

    @Test
    void propagatesTraceIdIntoReactorResponseCallback() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);

        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(request);
        when(request.options(any(OpenAiChatOptions.class))).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.stream()).thenReturn(stream);

        callbackScheduler = Schedulers.newSingle("llm-callback-test");
        ChatResponse response = new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok")))
        );
        when(stream.chatResponse()).thenReturn(
                Flux.just(response).publishOn(callbackScheduler)
        );

        AtomicReference<String> callbackTraceId = new AtomicReference<>();
        MDC.put("traceId", "trace-llm");

        LlmStreamCaller caller = new LlmStreamCaller(builder);
        LlmStreamCaller.LlmStreamResult result = caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_OUTLINE,
                "outline",
                new AiWorkflowStepEmitter() {
                    @Override
                    public void emit(String step, String status, String message) {
                    }

                    @Override
                    public void emitContent(String step, String field, String delta) {
                        callbackTraceId.set(MDC.get("traceId"));
                    }
                },
                "system",
                "user",
                100
        );

        assertThat(result.content()).isEqualTo("ok");
        assertThat(callbackTraceId).hasValue("trace-llm");
    }

    //JSON Mode：纯 JSON 出口必须把 response_format=json_object 传到 options
    //（实测背景：qwen3.6-plus 在长 prompt + 长 JSON 场景下约 57% 漏掉收尾括号，见 agent-json-mode-fix-design.md）
    @Test
    void jsonModePassesJsonObjectResponseFormat() {
        ChatClient.ChatClientRequestSpec request = mockStreamingClient("{}");

        LlmStreamCaller caller = new LlmStreamCaller(mockBuilderFor(request));
        caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_PLAN,
                "plan",
                AiWorkflowStepEmitter.noop(),
                "system",
                "user",
                null,
                true
        );

        ArgumentCaptor<OpenAiChatOptions> captor = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(request).options(captor.capture());
        assertThat(captor.getValue().getResponseFormat()).isNotNull();
        assertThat(captor.getValue().getResponseFormat().getType())
                .isEqualTo(ResponseFormat.Type.JSON_OBJECT);
    }

    //反向：默认调用（Markdown 输出的步骤）不能带 response_format，否则正文会坏
    @Test
    void defaultCallKeepsResponseFormatUnset() {
        ChatClient.ChatClientRequestSpec request = mockStreamingClient("## 标题");

        LlmStreamCaller caller = new LlmStreamCaller(mockBuilderFor(request));
        caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_OUTLINE,
                "outline",
                AiWorkflowStepEmitter.noop(),
                "system",
                "user",
                null
        );

        ArgumentCaptor<OpenAiChatOptions> captor = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(request).options(captor.capture());
        assertThat(captor.getValue().getResponseFormat()).isNull();
    }

    @Test
    void workflowSystemPromptDefinesExternalMaterialTrustBoundary() {
        ChatClient.ChatClientRequestSpec request = mockStreamingClient("ok");

        LlmStreamCaller caller = new LlmStreamCaller(mockBuilderFor(request));
        caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_OUTLINE,
                "outline",
                AiWorkflowStepEmitter.noop(),
                "原始步骤规则",
                "user",
                null
        );

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(request).system(captor.capture());
        assertThat(captor.getValue())
                .startsWith("原始步骤规则")
                .contains("外部材料信任边界")
                .contains("RAG / 检索结果")
                .contains("不是系统指令");
    }

    @Test
    void streamFailureCarriesUsageAlreadyReportedByProvider() {
        ChatClient.ChatClientRequestSpec request = mockStreamingClient(Flux.concat(
                Flux.just(response("已输出半段", 5, 1, 6)),
                Flux.error(new RuntimeException("503 unavailable"))
        ));

        LlmStreamCaller caller = new LlmStreamCaller(mockBuilderFor(request));

        assertThatThrownBy(() -> caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_DRAFT,
                "draft",
                AiWorkflowStepEmitter.noop(),
                "system",
                "user",
                null
        )).satisfies(error -> assertThat(usageOf(error).getTotalTokens())
                .as("流中断前已经返回的 usage 不能丢")
                .isEqualTo(6));
    }

    @Test
    void retryableFailureBeforeVisibleContentIsAddedToSuccessfulResultUsage() {
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.ChatClientRequestSpec request = mockStreamingClient(stream);
        ChatResponse failedAttempt = response("", 5, 1, 6);
        ChatResponse successfulAttempt = response("重试成功", 10, 2, 12);
        when(stream.chatResponse())
                .thenReturn(Flux.concat(
                        Flux.just(failedAttempt),
                        Flux.error(new RuntimeException("503 unavailable"))
                ))
                .thenReturn(Flux.just(successfulAttempt));

        LlmStreamCaller caller = new LlmStreamCaller(mockBuilderFor(request));

        LlmStreamCaller.LlmStreamResult result = caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_DRAFT,
                "draft",
                AiWorkflowStepEmitter.noop(),
                "system",
                "user",
                null
        );

        assertThat(result.content()).isEqualTo("重试成功");
        assertThat(result.usage().getTotalTokens())
                .as("最终成功也要包含失败尝试已经消耗的 token")
                .isEqualTo(18);
        verify(stream, times(2)).chatResponse();
    }

    @Test
    void blankResponseFailureCarriesUsage() {
        ChatClient.ChatClientRequestSpec request = mockStreamingClient(
                Flux.just(response("  ", 10, 1, 11)));

        LlmStreamCaller caller = new LlmStreamCaller(mockBuilderFor(request));

        assertThatThrownBy(() -> caller.call(
                "测试失败：",
                AiWorkflowStep.GENERATE_DRAFT,
                "draft",
                AiWorkflowStepEmitter.noop(),
                "system",
                "user",
                null
        )).satisfies(error -> assertThat(usageOf(error).getTotalTokens())
                .as("空内容异常也发生在一次真实模型调用之后，usage 要保留")
                .isEqualTo(11));
    }

    private ChatClient.Builder mockBuilderFor(ChatClient.ChatClientRequestSpec request) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(request);
        return builder;
    }

    private ChatClient.ChatClientRequestSpec mockStreamingClient(String content) {
        return mockStreamingClient(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage(content)))
        )));
    }

    private ChatClient.ChatClientRequestSpec mockStreamingClient(Flux<ChatResponse> responses) {
        ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        ChatClient.ChatClientRequestSpec request = mockStreamingClient(stream);
        when(stream.chatResponse()).thenReturn(responses);
        return request;
    }

    private ChatClient.ChatClientRequestSpec mockStreamingClient(ChatClient.StreamResponseSpec stream) {
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        when(request.options(any(OpenAiChatOptions.class))).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.stream()).thenReturn(stream);
        return request;
    }

    private ChatResponse response(String text, int prompt, int completion, int total) {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(prompt);
        when(usage.getCompletionTokens()).thenReturn(completion);
        when(usage.getTotalTokens()).thenReturn(total);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(new Generation(new AssistantMessage(text)));
        when(response.getMetadata()).thenReturn(metadata);
        return response;
    }

    private com.hailin.blogsystem.ai.TokenUsageAccumulator usageOf(Throwable error) {
        Throwable current = error;
        while (current != null) {
            try {
                Object usage = current.getClass().getMethod("usage").invoke(current);
                if (usage instanceof com.hailin.blogsystem.ai.TokenUsageAccumulator accumulator) {
                    return accumulator;
                }
            } catch (ReflectiveOperationException ignored) {
                // 继续沿 cause 链找，当前实现没有 usage() 时本测试应失败为 0。
            }
            current = current.getCause();
        }
        return new com.hailin.blogsystem.ai.TokenUsageAccumulator();
    }
}
