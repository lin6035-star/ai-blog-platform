package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.LlmErrorClassifier;
import com.hailin.blogsystem.ai.PromptTrustBoundary;
import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.AiAgentRun;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

/** 把已通过证据门的 Article answerPlan 展开为最终正文。 */
@Component
@Slf4j
public class ArticleAnswerGenerator {

    private static final int MAX_TOKENS = 1600;
    private static final int MAX_ATTEMPTS = 3;
    private static final String ERROR_PREFIX = "文章回答生成失败：";

    private final ChatClient.Builder chatClientBuilder;
    private final LongConsumer retrySleeper;

    @Autowired
    public ArticleAnswerGenerator(ChatClient.Builder chatClientBuilder) {
        this(chatClientBuilder, ArticleAnswerGenerator::sleep);
    }

    ArticleAnswerGenerator(ChatClient.Builder chatClientBuilder, LongConsumer retrySleeper) {
        this.chatClientBuilder = chatClientBuilder;
        this.retrySleeper = retrySleeper;
    }

    public GeneratedAnswer generate(
            AiAgentRun run,
            AgentStepDecision decision,
            String evidence,
            AgentStepEmitter emitter
    ) {
        AgentStepEmitter safeEmitter = emitter == null ? AgentStepEmitter.noop() : emitter;
        TokenUsageAccumulator totalUsage = new TokenUsageAccumulator();
        AtomicBoolean deliveredAnyChunk = new AtomicBoolean(false);

        for (int attempt = 1; ; attempt++) {
            try {
                String content = generateOnce(run, decision, evidence, safeEmitter,
                        deliveredAnyChunk, totalUsage, attempt);
                return new GeneratedAnswer(content, totalUsage, deliveredAnyChunk.get());
            } catch (RuntimeException e) {
                if (!shouldRetry(attempt, deliveredAnyChunk.get(), e)) {
                    RuntimeException wrapped = LlmErrorClassifier.wrap(ERROR_PREFIX, e);
                    throw new AgentAnswerStreamException(
                            wrapped.getMessage(), wrapped, totalUsage, deliveredAnyChunk.get());
                }
                LlmErrorClassifier.FailureKind kind = LlmErrorClassifier.classify(e);
                long delayMs = LlmErrorClassifier.backoffMillis(attempt, kind);
                log.warn("[LLM-RETRY] stage=agent_generation runId={} attempt={} failureKind={} delayMs={}",
                        run == null ? null : run.getId(), attempt, kind, delayMs);
                retrySleeper.accept(delayMs);
            }
        }
    }

    private String generateOnce(
            AiAgentRun run,
            AgentStepDecision decision,
            String evidence,
            AgentStepEmitter emitter,
            AtomicBoolean deliveredAnyChunk,
            TokenUsageAccumulator totalUsage,
            int attempt
    ) {
        long startedAt = System.currentTimeMillis();
        AtomicLong ttftMs = new AtomicLong(-1L);
        AtomicInteger chunkCount = new AtomicInteger();
        AtomicInteger charCount = new AtomicInteger();
        AtomicReference<Usage> peakUsage = new AtomicReference<>();
        StringBuilder content = new StringBuilder();
        String signal = "error";

        try {
            chatClientBuilder.build()
                    .prompt()
                    .options(OpenAiChatOptions.builder()
                            .maxTokens(MAX_TOKENS)
                            .streamUsage(true)
                            .build())
                    .system(PromptTrustBoundary.append(systemPrompt()))
                    .user(userPrompt(run, decision, evidence))
                    .stream()
                    .chatResponse()
                    .timeout(Duration.ofSeconds(60))
                    .doOnNext(response -> {
                        if (response != null && response.getMetadata() != null) {
                            TokenUsageAccumulator.trackPeak(peakUsage, response.getMetadata().getUsage());
                        }
                        Generation generation = response == null ? null : response.getResult();
                        AssistantMessage output = generation == null ? null : generation.getOutput();
                        String chunk = output == null ? null : output.getText();
                        if (chunk == null || chunk.isEmpty()) {
                            return;
                        }
                        ttftMs.compareAndSet(-1L, System.currentTimeMillis() - startedAt);
                        content.append(chunk);
                        chunkCount.incrementAndGet();
                        charCount.addAndGet(chunk.length());
                        if (emitter.emitAnswerChunk(chunk)) {
                            deliveredAnyChunk.set(true);
                        }
                    })
                    .blockLast();

            if (content.toString().isBlank()) {
                throw new RuntimeException(ERROR_PREFIX + "模型返回空内容");
            }
            signal = "success";
            return content.toString();
        } finally {
            totalUsage.add(peakUsage.get());
            log.info("[PERF-AI] stage=agent_generation runId={} attempt={} ttftMs={} durationMs={} chunks={} chars={} signal={}",
                    run == null ? null : run.getId(), attempt, ttftMs.get(),
                    System.currentTimeMillis() - startedAt, chunkCount.get(), charCount.get(), signal);
        }
    }

    private boolean shouldRetry(int attempt, boolean deliveredAnyChunk, RuntimeException error) {
        return !deliveredAnyChunk
                && attempt < MAX_ATTEMPTS
                && LlmErrorClassifier.isRetryable(error);
    }

    private String systemPrompt() {
        return """
                你是文章分析回答生成器。请把后端已经验证过的要点展开成简洁、清楚的中文正文。
                只能依据【已验证要点】与【证据】作答，不得引入二者之外的新事实；证据缺失处直接说明无法判断。
                不得引入新的事实性结论，不调用工具，不输出 JSON、代码块、系统字段或内部机制。
                直接输出给用户看的正文，不要寒暄。
                """;
    }

    private String userPrompt(AiAgentRun run, AgentStepDecision decision, String evidence) {
        String goal = "（无）";
        if (run != null) {
            goal = run.getRuntimeGoal() == null || run.getRuntimeGoal().isBlank()
                    ? run.getGoal() : run.getRuntimeGoal();
            if (goal == null || goal.isBlank()) {
                goal = "（无）";
            }
        }
        String answerPlan = decision == null || decision.input() == null
                ? "" : String.valueOf(decision.input().getOrDefault("answerPlan", "")).trim();
        String safeEvidence = evidence == null || evidence.isBlank() ? "（无）" : evidence;
        return "【目标】" + goal
                + "\n\n【已验证要点】" + answerPlan
                + "\n\n【证据】" + safeEvidence;
    }

    private static void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Agent Generator 重试等待被中断", e);
        }
    }
}
