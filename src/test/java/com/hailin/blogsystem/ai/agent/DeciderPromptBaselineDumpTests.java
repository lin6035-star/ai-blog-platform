package com.hailin.blogsystem.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.ai.AiJudgeModelSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.mock;

/**
 * P2.1 基线夹具生成器（Token 设计稿 §8.4「实施第一步必须用统一测量函数取得唯一基线」）。
 *
 * <p><b>它不是普通测试，是工具</b>：运行一次把改造前的四份 prompt 原文落成夹具，
 * 之后由 {@link ArticleDeciderPromptTests} 用它做两件事——
 * ① 断言 FULL normal / repair 与改造前**逐字一致**；
 * ② 用同一个测量函数算静态体积基线（30% 数字门的分母）。
 *
 * <p><b>为什么用运行时字符串而不是手工分段统计</b>：设计稿修订前踩过一次口径矛盾——
 * 手工按行号统计既受 Java 文本块缩进影响，也数不准 analysis-only 的「父类 + 追加」组合，
 * 于是 3723 / 4519 / 5144 三个数字互相打架。{@code buildSystemPrompt(repair)} 的**返回值长度**
 * 就是实际发给模型的字符串长度，它才是唯一权威口径。
 *
 * <p>重新生成夹具的唯一合法场景：**有意**修改 FULL prompt。改完必须同步更新
 * {@link ArticleDeciderPromptTests} 里的体积基线注释，并在提交信息里说明为什么改。
 */
class DeciderPromptBaselineDumpTests {

    static final Path DIR = Path.of("src", "test", "resources", "agent", "decider-prompt-baseline");

    static final String FULL_NORMAL = "article-full-normal.txt";
    static final String FULL_REPAIR = "article-full-repair.txt";
    static final String ANALYSIS_NORMAL = "article-analysis-only-normal.txt";
    static final String ANALYSIS_REPAIR = "article-analysis-only-repair.txt";

    @Test
    void dumpBaselinePrompts() throws Exception {
        // 夹具代表**改造前**的 prompt。已存在就跳过——绝不覆盖，
        // 否则 FULL 等价性测试会变成"改造后和自己比"，基线彻底失效。
        Assumptions.assumeFalse(Files.exists(DIR.resolve(FULL_NORMAL)),
                "基线夹具已存在，跳过生成：" + DIR);
        Files.createDirectories(DIR);

        ArticleAgentStepDecider full = new ArticleAgentStepDecider(
                mock(ChatClient.Builder.class), new ObjectMapper(), mock(AiJudgeModelSupport.class));
        ArticleAnalysisStepDecider analysisOnly = new ArticleAnalysisStepDecider(
                mock(ChatClient.Builder.class), new ObjectMapper(), mock(AiJudgeModelSupport.class));

        dump(FULL_NORMAL, full.buildSystemPrompt(false));
        dump(FULL_REPAIR, full.buildSystemPrompt(true));
        dump(ANALYSIS_NORMAL, analysisOnly.buildSystemPrompt(false));
        dump(ANALYSIS_REPAIR, analysisOnly.buildSystemPrompt(true));
    }

    private void dump(String name, String content) throws Exception {
        Path target = DIR.resolve(name);
        if (Files.exists(target)) {
            // 夹具代表**改造前**的 prompt。改造后若再跑本测试，会把新 prompt 写进去，
            // FULL 等价性测试就变成"自己和自己比"——基线彻底失效。所以拒绝覆盖。
            throw new IllegalStateException(
                    "基线夹具已存在，拒绝覆盖：" + name
                            + "。它是改造前的唯一基线；确需重新生成必须手工删除该文件，并在提交信息里说明原因。");
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        System.out.println("[prompt-baseline] " + name + " = " + content.length() + " chars");
    }
}
