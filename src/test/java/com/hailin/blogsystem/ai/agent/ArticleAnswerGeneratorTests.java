package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArticleAnswerGeneratorTests {

    private ChatClient.Builder builder;
    private ChatClient.ChatClientRequestSpec request;
    private ChatClient.StreamResponseSpec stream;
    private ArticleAnswerGenerator generator;

    @BeforeEach
    void setUp() {
        builder = mock(ChatClient.Builder.class);
        ChatClient client = mock(ChatClient.class);
        request = mock(ChatClient.ChatClientRequestSpec.class);
        stream = mock(ChatClient.StreamResponseSpec.class);

        when(builder.build()).thenReturn(client);
        when(client.prompt()).thenReturn(request);
        when(request.options(any(OpenAiChatOptions.class))).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.stream()).thenReturn(stream);

        generator = new ArticleAnswerGenerator(builder, ignored -> { });
    }

    @Test
    void streamsChunksBuildsAuthoritativeContentAndTracksPeakUsage() {
        ChatResponse first = response("结构清晰，", 100, 10, 110);
        ChatResponse second = response("建议补充边界。", 100, 20, 120);
        when(stream.chatResponse()).thenReturn(Flux.just(
                first,
                second
        ));
        List<String> chunks = new ArrayList<>();
        AgentStepEmitter emitter = externalEmitter(chunks);

        GeneratedAnswer answer = generator.generate(
                run(), decision("结构清晰；建议补充边界"), "标题和三节结构", emitter);

        assertThat(answer.content()).isEqualTo("结构清晰，建议补充边界。");
        assertThat(answer.deliveredAnyChunk()).isTrue();
        assertThat(chunks).containsExactly("结构清晰，", "建议补充边界。");
        assertThat(answer.usage().getPromptTokens()).isEqualTo(100);
        assertThat(answer.usage().getCompletionTokens()).isEqualTo(20);
        assertThat(answer.usage().getTotalTokens()).isEqualTo(120);

        ArgumentCaptor<OpenAiChatOptions> options = ArgumentCaptor.forClass(OpenAiChatOptions.class);
        verify(request).options(options.capture());
        assertThat(options.getValue().getMaxTokens()).isEqualTo(1600);

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(request).system(systemPrompt.capture());
        assertThat(systemPrompt.getValue())
                .contains("外部材料信任边界")
                .contains("不得引入")
                .doesNotContain("judge-model");

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(request).user(userPrompt.capture());
        assertThat(userPrompt.getValue())
                .contains("【目标】分析文章")
                .contains("【已验证要点】结构清晰；建议补充边界")
                .contains("【证据】标题和三节结构");
    }

    @Test
    void bufferedEmitterCollectsContentWithoutExternalDelivery() {
        ChatResponse response = response("完整正文", 10, 2, 12);
        when(stream.chatResponse()).thenReturn(Flux.just(response));

        GeneratedAnswer answer = generator.generate(
                run(), decision("要点"), "证据", AgentStepEmitter.noop());

        assertThat(answer.content()).isEqualTo("完整正文");
        assertThat(answer.deliveredAnyChunk()).isFalse();
    }

    @Test
    void runtimeGoalWinsOverTruncatedPersistedGoal() {
        AiAgentRun run = run();
        run.setGoal("数据库中截断的目标");
        run.setRuntimeGoal("完整目标\n\n来源 [1]\nRedis 正文证据");
        when(stream.chatResponse()).thenAnswer(inv ->
                Flux.just(response("完整正文", 10, 2, 12)));

        generator.generate(run, decision("横向比较三篇文章"), "", AgentStepEmitter.noop());

        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(request).user(userPrompt.capture());
        assertThat(userPrompt.getValue())
                .contains("【目标】完整目标")
                .contains("来源 [1]")
                .contains("Redis 正文证据")
                .doesNotContain("数据库中截断的目标");
    }

    @Test
    void retriesRetryableFailureBeforeExternalDelivery() {
        ChatResponse response = response("重试成功", 10, 2, 12);
        when(stream.chatResponse())
                .thenReturn(Flux.error(new RuntimeException("429 too many requests")))
                .thenReturn(Flux.just(response));

        GeneratedAnswer answer = generator.generate(
                run(), decision("要点"), "证据", externalEmitter(new ArrayList<>()));

        assertThat(answer.content()).isEqualTo("重试成功");
        verify(stream, times(2)).chatResponse();
    }

    @Test
    void retriesBufferedPartialAttemptAndKeepsOnlySuccessfulAttemptContent() {
        ChatResponse partial = response("旧半段", 5, 1, 6);
        ChatResponse completed = response("重新生成的完整正文", 10, 2, 12);
        when(stream.chatResponse())
                .thenReturn(Flux.concat(
                        Flux.just(partial),
                        Flux.error(new RuntimeException("503 unavailable"))))
                .thenReturn(Flux.just(completed));

        GeneratedAnswer answer = generator.generate(
                run(), decision("要点"), "证据", AgentStepEmitter.noop());

        assertThat(answer.content()).isEqualTo("重新生成的完整正文");
        assertThat(answer.usage().getTotalTokens()).isEqualTo(18);
        verify(stream, times(2)).chatResponse();
    }

    @Test
    void doesNotRetryAfterChunkWasExternallyDelivered() {
        ChatResponse partial = response("已展示半段", 5, 1, 6);
        when(stream.chatResponse()).thenReturn(Flux.concat(
                Flux.just(partial),
                Flux.error(new RuntimeException("503 unavailable"))
        ));

        assertThatThrownBy(() -> generator.generate(
                run(), decision("要点"), "证据", externalEmitter(new ArrayList<>())))
                .isInstanceOf(AgentAnswerStreamException.class)
                .satisfies(error -> assertThat(((AgentAnswerStreamException) error).deliveredAnyChunk()).isTrue());
        verify(stream, times(1)).chatResponse();
    }

    @Test
    void blankResponseFailsWithoutRetry() {
        ChatResponse response = response("  ", 10, 1, 11);
        when(stream.chatResponse()).thenReturn(Flux.just(response));

        assertThatThrownBy(() -> generator.generate(
                run(), decision("要点"), "证据", AgentStepEmitter.noop()))
                .isInstanceOf(AgentAnswerStreamException.class)
                .hasMessageContaining("空内容");
        verify(stream, times(1)).chatResponse();
    }

    private AgentStepEmitter externalEmitter(List<String> chunks) {
        return new AgentStepEmitter() {
            @Override
            public void emit(int stepNo, String actionType, String status, String message, String thoughtSummary) {
            }

            @Override
            public boolean emitAnswerChunk(String chunk) {
                chunks.add(chunk);
                return true;
            }
        };
    }

    private AiAgentRun run() {
        AiAgentRun run = new AiAgentRun();
        run.setId(7L);
        run.setGoal("分析文章");
        return run;
    }

    private AgentStepDecision decision(String answerPlan) {
        return AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                .withInput(Map.of("answerPlan", answerPlan));
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
}
