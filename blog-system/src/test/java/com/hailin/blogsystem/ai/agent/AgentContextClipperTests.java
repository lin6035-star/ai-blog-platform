package com.hailin.blogsystem.ai.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证据上下文裁剪的定向测试（Token 工程 P0-a，2026-09-30）。
 *
 * <p><b>锁的是什么</b>：修复前两处 {@code clipContext} 都从**最早**一条开始累加、满了就停，
 * 于是满载时保留旧材料、**丢掉最新证据**——而证据收敛门的核心机制正是
 * 「判 NEED_MORE → 补查 → 用新证据回答」，补到的新证据反而最可能被截掉。
 *
 * <p>四个方向按设计稿 §16.3 的要求逐条锁死：<b>最新保留 / 最旧淘汰 / 顺序不变 / 总预算</b>，
 * 另补单条超预算、空输入、null 条目三个边界。
 */
class AgentContextClipperTests {

    private static final int BUDGET = 6000;

    /** 造一条可辨认、长度可控的观察文本：以标记开头，便于断言它在不在结果里 */
    private static String observation(String marker, int length) {
        StringBuilder sb = new StringBuilder(marker);
        while (sb.length() < length) {
            sb.append('x');
        }
        return sb.toString();
    }

    /** 四条各 2000 字符（含换行 2001）：预算 6000 只装得下最新两条 */
    private static List<String> fourObservations() {
        return List.of(
                observation("OLDEST-1", 2000),
                observation("OLD-2", 2000),
                observation("NEW-3", 2000),
                observation("NEWEST-4", 2000));
    }

    // ==================== 四类定向测试 ====================

    @Test
    @DisplayName("最新保留：满载时最新证据必须在上下文里（修复前此例必失败）")
    void keepsLatestWhenBudgetFull() {
        String clipped = AgentContextClipper.latestWindow(fourObservations(), BUDGET);

        assertThat(clipped)
                .as("最新一条证据丢失 = 证据收敛门的补查结果被截掉")
                .contains("NEWEST-4");
        assertThat(clipped).contains("NEW-3");
    }

    @Test
    @DisplayName("最旧淘汰：超出预算的旧证据应被丢弃（修复前丢的是新的，方向正好相反）")
    void dropsOldestWhenBudgetFull() {
        String clipped = AgentContextClipper.latestWindow(fourObservations(), BUDGET);

        assertThat(clipped).doesNotContain("OLDEST-1");
        assertThat(clipped).doesNotContain("OLD-2");
    }

    @Test
    @DisplayName("顺序不变：窗口内必须保持原时间顺序，不能把列表倒序发给模型")
    void preservesOriginalTimeOrder() {
        String clipped = AgentContextClipper.latestWindow(fourObservations(), BUDGET);

        assertThat(clipped.indexOf("NEW-3"))
                .as("时间线颠倒会让模型误判证据的先后关系")
                .isLessThan(clipped.indexOf("NEWEST-4"));
    }

    @Test
    @DisplayName("总预算：输出长度不得超过上限")
    void neverExceedsBudget() {
        String clipped = AgentContextClipper.latestWindow(fourObservations(), BUDGET);

        assertThat(clipped.length()).isLessThanOrEqualTo(BUDGET);
    }

    // ==================== 边界 ====================

    @Test
    @DisplayName("边界：预算充足时全部保留，不误伤")
    void keepsEverythingWhenUnderBudget() {
        List<String> observations = List.of("短证据一", "短证据二", "短证据三");

        String clipped = AgentContextClipper.latestWindow(observations, BUDGET);

        assertThat(clipped).contains("短证据一").contains("短证据二").contains("短证据三");
    }

    @Test
    @DisplayName("边界：单条即超预算时截断保留最新一条，不能返回空上下文")
    void truncatedWhenSingleObservationExceedsBudget() {
        List<String> observations = List.of("旧材料", observation("HUGE-LATEST", 9000));

        String clipped = AgentContextClipper.latestWindow(observations, BUDGET);

        assertThat(clipped)
                .as("返回空上下文会静默丢光证据")
                .isNotEmpty()
                .startsWith("HUGE-LATEST");
        assertThat(clipped.length()).isEqualTo(BUDGET);
    }

    @Test
    @DisplayName("边界：空输入 / null 列表返回空串")
    void emptyInputReturnsEmpty() {
        assertThat(AgentContextClipper.latestWindow(null, BUDGET)).isEmpty();
        assertThat(AgentContextClipper.latestWindow(List.of(), BUDGET)).isEmpty();
    }

    @Test
    @DisplayName("边界：null 条目跳过，不影响其余证据与顺序")
    void nullEntriesAreSkipped() {
        List<String> observations = Arrays.asList("证据一", null, "证据二");

        String clipped = AgentContextClipper.latestWindow(observations, BUDGET);

        assertThat(clipped).contains("证据一").contains("证据二");
        assertThat(clipped).doesNotContain("null");
    }
}
