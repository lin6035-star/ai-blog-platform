package com.hailin.blogsystem.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复合目标信号的**保守回退**边界（P3 激活前补的保护，2026-10-01）。
 *
 * <p>按需装载有个不对称的风险：线索表命中的永远是"它认识的那个目标"，而第二个目标可能根本不认识。
 * 最贵的失败就是这样来的——
 * <b>"点赞这篇文章，再给我弄个复习路线"</b>：{@code 点赞} 命中文章块，
 * 而"复习路线"不在学习词表里，于是学习规则被裁掉，**第二个目标静默丢失**。
 *
 * <p>多目标编排是第三刀/第四刀刚做完的能力，不能为省 Token 拿它冒险。
 * 所以这里断言的是"宁可退回 FULL"这个方向本身——不是"少省一点"，而是"不许赌"。
 */
class CandidateACompoundTests {

    @Test
    void compoundSignalFallsBackToFull() {
        CandidateA.Assembled assembled = CandidateA.assemble("点赞这篇文章，再给我弄个复习路线", "article-detail");
        assertThat(assembled.fullFallback())
                .as("含复合信号就必须 FULL——线索表只认识它认识的那个目标")
                .isTrue();
        assertThat(assembled.includedBlocks())
                .as("回退必须是真 19 块，不能只是打个标记")
                .hasSize(19);
    }

    @Test
    void everyCompoundSignalIsProtected() {
        for (String signal : List.of("再", "然后", "同时", "顺便", "接着", "以及", "并且", "之后")) {
            assertThat(CandidateA.assemble("点赞这篇文章" + signal + "给我弄个复习路线", "article-detail")
                    .fullFallback())
                    .as("复合信号必须触发 FULL：" + signal)
                    .isTrue();
        }
    }

    @Test
    void singleGoalRequestsAreStillTrimmed() {
        // 这次保护不能把简单请求的收益一起吃掉
        assertThat(CandidateA.assemble("点赞这篇文章", "article-detail").fullFallback())
                .as("单目标动作请求仍按需装载").isFalse();
        assertThat(CandidateA.assemble("帮我充10块钱", null).fullFallback())
                .as("单目标钱包请求仍按需装载").isFalse();
        assertThat(CandidateA.assemble("保存草稿", "editor-new").fullFallback())
                .as("编辑器页请求仍按需装载").isFalse();
        assertThat(CandidateA.assemble("帮我写一篇 Redis 缓存设计的文章", null).fullFallback())
                .as("单目标创建文章请求仍按需装载").isFalse();
    }

    @Test
    void zeroClueStillFallsBackToFull() {
        // 原有兜底不能被这次改动挤掉
        assertThat(CandidateA.assemble("什么是缓存穿透", null).fullFallback())
                .as("无线索的闲聊仍走 FULL").isTrue();
    }
}
