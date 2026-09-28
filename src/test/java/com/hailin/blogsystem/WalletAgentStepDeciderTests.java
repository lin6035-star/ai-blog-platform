package com.hailin.blogsystem;

import com.hailin.blogsystem.ai.agent.AgentStepDecision;
import com.hailin.blogsystem.ai.agent.AgentWriteProposal;
import com.hailin.blogsystem.ai.agent.WalletAgentStepDecider;
import com.hailin.blogsystem.entity.dto.AgentStepActionType;
import com.hailin.blogsystem.entity.vo.WalletCustomConfigVO;
import com.hailin.blogsystem.service.WalletRechargeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 钱包域决策器：确定性、不调 LLM。
 *
 * <p>两条最要紧的断言：**第二轮必须自终止**（确定性决策器每轮输出相同，
 * 「拒绝后让下一轮收尾」就是死循环），以及**追问绝不能用 ASK_USER**
 * （钱包域不能有 WAITING_USER——AgentFollowUpResolver 只认学习计划，
 * 用户的回答会走成普通分类，永远回不到钱包域）。
 */
class WalletAgentStepDeciderTests {

    private WalletAgentStepDecider decider;

    @BeforeEach
    void setUp() {
        WalletRechargeService walletRechargeService = mock(WalletRechargeService.class);
        WalletCustomConfigVO config = new WalletCustomConfigVO();
        config.setMinYuan(1L);
        config.setMaxYuan(1000L);
        config.setCreditPerYuan(1000L);
        when(walletRechargeService.getCustomConfig()).thenReturn(config);
        decider = new WalletAgentStepDecider(walletRechargeService);
    }

    private AgentStepDecision decide(String goal) {
        return decider.decide(goal, null, 1, 3, null);
    }

    private static String answerOf(AgentStepDecision decision) {
        return String.valueOf(decision.input().get("answer"));
    }

    @Test
    void producesSuggestWriteWhenAmountIsClear() {
        AgentStepDecision decision = decide("帮我充10块");

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.SUGGEST_WRITE);
        assertThat(decision.input())
                .containsEntry("actionType", AgentWriteProposal.TYPE_RECHARGE_WALLET)
                .containsEntry("payYuan", "10");
    }

    @Test
    void producesSuggestWriteForChineseNumber() {
        AgentStepDecision decision = decide("帮我充十块");

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.SUGGEST_WRITE);
        assertThat(decision.input()).containsEntry("payYuan", "10");
    }

    /** 没给金额 → 追问，**不出卡**。卡片是「我可以执行」的承诺，猜一个数字递出去是主动误导 */
    @Test
    void asksWhenAmountIsMissing() {
        AgentStepDecision decision = decide("帮我充点钱");

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.FINAL_ANSWER);
        assertThat(answerOf(decision)).contains("1 ~ 1000");
    }

    /** 区间从配置读，不硬编码——改了 yml 文案不能漂 */
    @Test
    void asksWhenAmountIsOutOfRange() {
        assertThat(answerOf(decide("充0块"))).contains("1 ~ 1000");
        assertThat(answerOf(decide("充5000块"))).contains("1 ~ 1000");
    }

    @Test
    void asksWhenAmountIsAmbiguous() {
        AgentStepDecision decision = decide("先充10块，等下再充20块");

        assertThat(decision.actionType()).isEqualTo(AgentStepActionType.FINAL_ANSWER);
        assertThat(answerOf(decision)).contains("不止一个金额");
    }

    /**
     * ⚠️ 自终止：第二轮一律收尾。
     *
     * <p>这是本域防死循环的关键。确定性决策器的每一轮输入完全相同，再给一次
     * SUGGEST_WRITE 就是同一个提案 → 基类循环永远出不来。
     */
    @Test
    void selfTerminatesAfterFirstStep() {
        AgentStepDecision second = decider.decide("帮我充10块", null, 2, 3, null);
        AgentStepDecision third = decider.decide("帮我充10块", null, 3, 3, null);

        assertThat(second.actionType())
                .as("第二轮绝不能再给 SUGGEST_WRITE")
                .isEqualTo(AgentStepActionType.FINAL_ANSWER);
        assertThat(third.actionType()).isEqualTo(AgentStepActionType.FINAL_ANSWER);
    }

    /**
     * 钱包域**永远不能**输出 ASK_USER。
     *
     * <p>{@code AgentFollowUpResolver} 只认「上一轮建议的目标是本会话可唯一定位的
     * 学习计划」，钱包 run 若以 WAITING_USER 收尾，用户回答「10块」会走正常分类
     * （判成 GENERAL_CHAT）→ 永远回不到钱包域，钱充不进去、用户只看到一句闲聊。
     */
    @Test
    void neverEmitsAskUser() {
        for (String goal : new String[]{"帮我充点钱", "帮我充10块", "充5000块", "先充10再充20", null, ""}) {
            for (int stepNo = 1; stepNo <= 3; stepNo++) {
                assertThat(decider.decide(goal, null, stepNo, 3, null).actionType())
                        .as("「%s」第 %d 步", goal, stepNo)
                        .isNotEqualTo(AgentStepActionType.ASK_USER);
            }
        }
    }

    /** Runtime 在 maxSteps 到顶时会调它；默认实现返回 null 会拼出一段给用户看的怪话 */
    @Test
    void summaryIsAUsableFallbackRatherThanNull() {
        assertThat(decider.summarize("帮我充10块", null, null)).isNotBlank();
    }
}
