package com.hailin.blogsystem.ai.task;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务结果渲染器（第三刀）——**确定性文案，不调 LLM**。
 *
 * <p>沿用第一刀的判断：不让模型猜动作成没成功。文案只依据 {@link StepResult} 的
 * {@code state} / {@code code} 生成，**不解析 summary 的自然语言**。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>{@link StepState#DISPATCHED} 说"已发起"，不说"已完成"</b>——
 *       客户端步骤由前端执行，后端不知道结果，这是事实不能掩盖。</li>
 *   <li><b>{@link StepState#SKIPPED} 必须说明原因</b>——"跳转主页未执行，因为没有拿到作者信息"
 *       比只说"未执行"有用得多。</li>
 *   <li><b>存在 {@link TaskPlan#uncoveredGoalEvidence()} 时不得说"全部完成"</b>——
 *       用户说了三件事、系统只承接了两件，"两件都做完了"是真话但不是全部事实。
 *       这是弱模型下的安全底线：不要求证明目标被 100% 理解，但要求不谎报完成。</li>
 * </ol>
 */
@Component
public class TaskResultRenderer {

    public String render(OrchestrationOutcome outcome) {
        TaskPlan plan = outcome.plan();
        List<StepResult> results = outcome.results();

        List<String> done = new ArrayList<>();
        List<String> undone = new ArrayList<>();
        int pendingCount = 0;

        for (StepResult result : results) {
            if (result.state() == StepState.PENDING || result.state() == StepState.RUNNING) {
                pendingCount++;   // 计划暂停后没轮到的，由下面的暂停说明统一交代
                continue;
            }
            String line = lineOf(result);
            if (line == null) {
                continue;
            }
            if (result.isSuccessLike()) {
                done.add(line);
            } else {
                undone.add(line);
            }
        }

        boolean allDone = undone.isEmpty() && !plan.hasUncoveredGoals() && !outcome.paused();

        List<String> blocks = new ArrayList<>();
        if (allDone) {
            // 只有「全部达成 + 无未承接目标 + 未暂停」才允许说全部完成
            return "已全部完成：" + String.join("；", done);
        }
        if (!done.isEmpty()) {
            blocks.add("已完成：" + String.join("；", done));
        }
        if (!undone.isEmpty()) {
            blocks.add("未完成：" + String.join("；", undone));
        }
        if (pendingCount > 0) {
            blocks.add("已暂停：还有 " + pendingCount + " 个步骤没有执行。");
        }
        if (plan.hasUncoveredGoals()) {
            // 不伪装"全部完成"：明确说出还有没承接的目标
            blocks.add("另外，这些要求当前还不支持，没有执行："
                    + String.join("；", plan.uncoveredGoalEvidence()));
        }

        if (blocks.isEmpty()) {
            return "这次没有需要执行的动作。";
        }
        return String.join("\n", blocks);
    }

    /** 单步文案；PENDING / RUNNING 由暂停说明统一交代，不在这里出 */
    private String lineOf(StepResult result) {
        return switch (result.state()) {
            case SUCCEEDED -> result.summary();
            case DISPATCHED -> "已发起" + result.summary();
            case FAILED -> result.summary();
            case SKIPPED -> result.summary();
            case WAITING_CONFIRM, WAITING_USER -> "待你确认：" + result.summary();
            case PENDING, RUNNING -> null;
        };
    }
}
