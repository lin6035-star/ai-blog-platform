package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.service.impl.AiMemoryCandidateExtractorServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 候选记忆第一层规则预筛（{@code shouldExtract}）。
 *
 * <p>2026-10-13 放宽词表时补的边界锁：常见的第一人称陈述与回答风格偏好必须能进第二层 LLM
 * 判断，纯提问和闲聊不能——旧词表（10 个词）在 27 条真实用户消息里只命中 2 条，
 * 候选记忆自 9-17 起停止生长。
 */
class AiMemoryCandidateExtractorTests {

    private static boolean shouldExtract(String message) throws Exception {
        AiMemoryCandidateExtractorServiceImpl extractor = new AiMemoryCandidateExtractorServiceImpl(
                null, null, new ObjectMapper(), null, null);
        Method method = AiMemoryCandidateExtractorServiceImpl.class
                .getDeclaredMethod("shouldExtract", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(extractor, message);
    }

    @Test
    void firstPersonStatementsReachTheSecondLayer() throws Exception {
        assertThat(shouldExtract("我在学 Elasticsearch")).isTrue();
        assertThat(shouldExtract("我是 Java 后端方向")).isTrue();
        assertThat(shouldExtract("我希望回答直接一点")).isTrue();
        assertThat(shouldExtract("我打算下阶段做 Agent")).isTrue();
        assertThat(shouldExtract("我习惯先看结论")).isTrue();
    }

    @Test
    void styleImperativesStillTrigger() throws Exception {
        assertThat(shouldExtract("回答直接一点，不要那么多铺垫")).isTrue();
        assertThat(shouldExtract("代码别那么啰嗦")).isTrue();
    }

    @Test
    void explicitMemoryCommandsStillTrigger() throws Exception {
        assertThat(shouldExtract("记住我偏好先讲业务再讲代码")).isTrue();
        assertThat(shouldExtract("这个项目后面再做秒杀")).isTrue();
    }

    @Test
    void ordinaryQuestionsAndChatterDoNotTrigger() throws Exception {
        assertThat(shouldExtract("有没有关于 Redis 的文章")).isFalse();
        assertThat(shouldExtract("帮我优化一下这篇文章")).isFalse();
        assertThat(shouldExtract("哈哈，我又来啦")).isFalse();
        assertThat(shouldExtract("")).isFalse();
        assertThat(shouldExtract(null)).isFalse();
    }
}
