package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.ai.TokenUsageAccumulator;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.vo.WalletCustomConfigVO;
import com.hailin.blogsystem.service.WalletRechargeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 钱包域的决策器：<b>确定性，不调 LLM</b>。
 *
 * <p>充值不需要多步推理——「充多少」就在用户原话里。再让 Agent 循环跑几次 LLM 决策，
 * 既烧钱又凭空多出一次幻觉面（见 {@link WalletAmountParser} 的类注释）。
 * 所以这里直接把 goal 解析成决策，整个 run 正常情况下只有一步。
 *
 * <p>⚠️ 与三个 LLM 域最根本的差别：<b>这个决策器每一轮的输出都完全一样</b>。
 * 任何「拒绝后让下一轮收尾」的写法在这里都是死循环，所以
 * {@link #decide} 用 {@code stepNo > 1} 自终止收尾。
 */
@Component
@RequiredArgsConstructor
public class WalletAgentStepDecider implements AgentStepDecider {

    /** 循环走到第二步还没结束时的收尾话术（正常情况下不该出现） */
    private static final String FALLBACK = "这次没有完成充值。你可以直接说「充 10 块」这样，我再帮你确认一次。";

    private final WalletRechargeService walletRechargeService;

    @Override
    public AgentStepDecision decide(String goal, String clippedContext, int stepNo, int maxSteps,
                                    TokenUsageAccumulator usage) {
        // ⚠️ 自终止。这个域只有第一步有意义；stepNo > 1 说明流程没按预期收尾
        // （提案被拒后又回到循环顶）。确定性决策器的每一轮输入都相同，再给一次完全一样的
        // 提案只会重复一次——**这是本域唯一能真正让循环有界的东西**，不能省。
        if (stepNo > 1) {
            return finalAnswer(FALLBACK);
        }

        WalletAmountParser.Result parsed = WalletAmountParser.parse(goal);
        WalletCustomConfigVO config = walletRechargeService.getCustomConfig();
        long min = config == null || config.getMinYuan() == null ? 1L : config.getMinYuan();
        long max = config == null || config.getMaxYuan() == null ? 1000L : config.getMaxYuan();

        String problem = validate(parsed, min, max);
        if (problem != null) {
            return finalAnswer(problem);
        }

        return AgentStepDecision.of(AgentStepActionType.SUGGEST_WRITE)
                .withInput(Map.of(
                        "actionType", AgentWriteProposal.TYPE_RECHARGE_WALLET,
                        "payYuan", String.valueOf(parsed.yuan())));
    }

    /**
     * 追问 / 报错都走 {@code FINAL_ANSWER}，<b>不能用 {@code ASK_USER}</b>。
     *
     * <p>钱包域不能有 {@code WAITING_USER}：{@code AgentFollowUpResolver} 只认
     * 「上一轮建议的目标是本会话可唯一定位的学习计划」，用户回答「10块」会走正常分类
     * （判成 GENERAL_CHAT）→ <b>永远回不到钱包域</b>，钱充不进去、用户只看到一句闲聊。
     */
    private static AgentStepDecision finalAnswer(String answer) {
        return AgentStepDecision.of(AgentStepActionType.FINAL_ANSWER)
                .withInput(Map.of("answer", answer));
    }

    /** @return 给用户看的问题描述；null 表示金额可用 */
    private static String validate(WalletAmountParser.Result parsed, long min, long max) {
        return switch (parsed.kind()) {
            case NONE -> String.format("你想充多少额度？目前支持 %d ~ %d 元的整数金额。", min, max);
            case AMBIGUOUS -> "你提到了不止一个金额，想充哪一个？说一个数就行。";
            case SINGLE -> parsed.yuan() < min || parsed.yuan() > max
                    ? String.format("充值金额需在 %d ~ %d 元之间。", min, max)
                    : null;
        };
    }

    /**
     * maxSteps 到顶时的收尾文案。
     *
     * <p>默认实现返回 null，Runtime 会退化成「把 observations 拼成一段」——
     * 而本域的 observations 里可能只有系统提示，拼出来是给用户看的怪话。
     */
    @Override
    public String summarize(String goal, String clippedContext, TokenUsageAccumulator usage) {
        return FALLBACK;
    }
}
