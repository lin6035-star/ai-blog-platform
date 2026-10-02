package com.hailin.blogsystem.service.impl;

import com.hailin.blogsystem.entity.LearningPlans;
import com.hailin.blogsystem.entity.dto.PageContextDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试侧链路与生产链路的**口径契约**（离线，零成本）。
 *
 * <p><b>它防什么</b>：{@link ClassifierCandidateRunner} 是 {@code AiIntentClassifierImpl} 的副本。
 * 副本最大的风险不是"写错了"（写错了配对测量立刻跑不出来），而是**生产改了、副本没跟着改**——
 * 那种漂移不会报错，只会让测量结果悄悄失真。注释里写"请同步"拦不住任何人，断言可以。
 *
 * <p><b>为什么用反射</b>：生产的 {@code buildUserPrompt} 是 private。为了一个测量入口
 * 就去改生产代码的可见性，是把 spike 的成本转嫁给生产；反射只在这一个测试里用，代价留在测试侧。
 *
 * <p>system prompt 侧不需要对照：FULL 候选直接调 {@code AiIntentClassifierImpl.buildSystemPrompt}，
 * 本来就是同一个方法。
 */
class ClassifierCandidateRunnerTests {

    @Test
    void userPromptMatchesProduction() throws Exception {
        Method production = AiIntentClassifierImpl.class.getDeclaredMethod(
                "buildUserPrompt", String.class, PageContextDTO.class, List.class);
        production.setAccessible(true);
        // buildUserPrompt 不碰任何实例字段，依赖传 null 是安全的（这不是"能跑就行"，是刻意的）
        AiIntentClassifierImpl impl = new AiIntentClassifierImpl(null, null, null, null, null);
        ClassifierCandidateRunner runner = new ClassifierCandidateRunner(null, null, null, null);

        PageContextDTO page = new PageContextDTO();
        page.setPageType("article-detail");
        page.setPath("/article/12");
        page.setArticleId("12");
        page.setAuthorId("7");
        page.setUserId("9");

        LearningPlans planA = new LearningPlans();
        planA.setTitle("C语言系统学习计划");
        LearningPlans planB = new LearningPlans();
        planB.setTitle("C++ 系统学习与工程化实践计划");

        assertSame(production, impl, runner, "无页面上下文 + 无计划列表", null, List.of());
        assertSame(production, impl, runner, "有页面上下文 + 无计划列表", page, List.of());
        assertSame(production, impl, runner, "无页面上下文 + 两个计划", null, List.of(planA, planB));
    }

    private void assertSame(Method production, AiIntentClassifierImpl impl, ClassifierCandidateRunner runner,
                            String scene, PageContextDTO page, List<LearningPlans> plans) throws Exception {
        String expected = (String) production.invoke(impl, "点赞这篇文章", page, plans);
        String actual = runner.buildUserPrompt("点赞这篇文章", page, plans);
        assertThat(actual)
                .as("user prompt 必须与生产逐字一致（场景：%s）——不一致就要么同步副本，要么改测量口径", scene)
                .isEqualTo(expected);
    }
}
