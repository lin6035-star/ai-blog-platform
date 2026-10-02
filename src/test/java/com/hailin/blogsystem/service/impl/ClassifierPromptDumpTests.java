package com.hailin.blogsystem.service.impl;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 把分类器 prompt 落一份**逐字基线**（P3.0 切块第 1 步）。
 *
 * <p><b>为什么必须有这一步</b>：候选 A 要把 500+ 行 prompt 切成规则片段。
 * 切块最容易犯的错不是"少装了某块"，而是**切的时候悄悄改了文本**——比如 text block 的缩进
 * 基准变了、某行的前导空格被吃掉。那种改动不会让任何断言失败，只会让模型行为变差。
 *
 * <p>切块前的原文已固化到 {@code src/test/resources/agent/classifier-prompt-full-baseline.txt}，
 * 由 {@link ClassifierPromptBlocksTests} 做逐字比对；这里保留 dump 只是为了人工查看与重新生成块。
 *
 * <p>输出：{@code target/classifier-prompt-baseline.txt}（不参与断言）。
 */
class ClassifierPromptDumpTests {

    private static final Path NORMAL = Path.of("target", "classifier-prompt-baseline.txt");
    private static final Path REPAIR = Path.of("target", "classifier-prompt-repair-baseline.txt");

    @Test
    void dumpClassifierSystemPrompts() throws Exception {
        String normal = AiIntentClassifierImpl.buildSystemPrompt(false);
        String repair = AiIntentClassifierImpl.buildSystemPrompt(true);

        assertThat(normal).isNotBlank();
        assertThat(repair).isNotBlank();

        // repair 轮是另一段短 prompt（它没有字段清单——这正是 P3 要补的自包含问题）
        assertThat(repair.length()).isLessThan(normal.length());

        write(NORMAL, normal);
        write(REPAIR, repair);

        System.out.printf("[P3-PROMPT] normal: %d 字符 / %d 行%n",
                normal.length(), normal.split("\n", -1).length);
        System.out.printf("[P3-PROMPT] repair: %d 字符 / %d 行%n",
                repair.length(), repair.split("\n", -1).length);
    }

    private void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }
}
