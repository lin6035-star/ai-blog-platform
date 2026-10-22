package com.hailin.blogsystem.ai.agent;

import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.AiAgentRun;
import com.hailin.blogsystem.entity.vo.PaymentInitiateVO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 钱包域写动作执行器：把确认卡执行成「创建充值订单 + 发起支付」。
 *
 * <p><b>只做两段，不替代用户付款</b>：{@code createCustomOrder} 落 PENDING 订单 →
 * {@code initiatePayment} 创建支付单并返回 {@code payToken}，交给前端支付窗口。
 * 用户点「确认支付」后才走渠道回调入账——AI 可以说「已创建订单」，不能说「已到账」。
 *
 * <p>⚠️ 真实支付下这也是唯一正确的形态：服务端替用户调起支付页可以，
 * 但替用户完成支付不可能。所以这里**永远不调 {@code mockPay}**。
 *
 * <p>幂等由壳提供：{@code confirmWrite} 的 {@code WAITING_WRITE_CONFIRM → CONFIRMING}
 * 状态 CAS 是一道 DB 行锁、一次性消费。<b>注意别误以为这里的防线是订单号唯一索引</b>
 * ——每次尝试都生成新 orderNo，撞 {@code uk_recharge_order_no} 那层根本不会触发
 * （见 docs/agent/agent-wallet-agent-design.md）。
 *
 * <p>异常直接上抛，由壳的 {@code @Transactional} 整体回滚：订单不存在、余额未动、
 * run 回到 {@code WAITING_WRITE_CONFIRM} 可重试。{@code createCustomOrder} 抛的
 * {@code IllegalArgumentException}（金额越界/非整数）会被
 * {@code GlobalExceptionHandler} 映射成 40001，语义正确，不需要在这里再包一层。
 */
@Component
@Slf4j
public class WalletAgentWriteActionExecutor implements AgentWriteActionExecutor {

    private final WalletRechargeService walletRechargeService;
    private final WalletService walletService;

    public WalletAgentWriteActionExecutor(WalletRechargeService walletRechargeService,
                                          WalletService walletService) {
        this.walletRechargeService = walletRechargeService;
        this.walletService = walletService;
    }

    @Override
    public boolean supports(String actionType) {
        return AgentWriteProposal.TYPE_RECHARGE_WALLET.equals(actionType);
    }

    @Override
    public WriteActionResult execute(AiAgentRun run, AgentWriteProposal proposal, Long userId) {
        BigDecimal payYuan = parseAmount(proposal.payYuan());
        long beforeBalance = balanceOf(userId);

        RechargeOrderVO order = walletRechargeService.createCustomOrder(payYuan);
        /*
         * 只做「下单 + 发起支付」，**不代替用户完成支付**：
         *   ① createCustomOrder 落 PENDING 订单
         *   ② initiatePayment 创建支付单，返回 payToken —— 把它交给前端弹「模拟支付」窗口，
         *      用户点确认才走渠道回调入账（真实支付下这里是收银台参数，用户跳渠道付款）
         *
         * ⚠️ 这里**绝不能**调 mockPay 把支付做掉：AI 可以说「已创建订单」，
         * 不能替用户确认付款——钱的确认权在用户手里（这也是接真实渠道后唯一正确的形态）。
         */
        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());
        if (!BlogConstants.Wallet.ORDER_PENDING.equals(payment.getOrderStatus())) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT,
                    "订单状态异常：" + payment.getOrderStatus());
        }

        long creditAmount = order.getCreditAmount() == null ? 0L : order.getCreditAmount();
        // 到账额度以**实际下单结果**为准，不是提案时刻算的估算值（兑换比例可能已经被改过）
        String message = "已创建充值订单，请在支付窗口中完成支付。";

        Map<String, Object> executed = new HashMap<>();
        executed.put("orderNo", order.getOrderNo());
        executed.put("paymentNo", payment.getPaymentNo());
        executed.put("payToken", payment.getPayToken());
        executed.put("payYuan", payYuan.toPlainString());
        executed.put("payAmount", order.getPayAmount());
        executed.put("creditAmount", creditAmount);
        executed.put("expireAt", order.getExpireAt() == null ? null : order.getExpireAt().toString());
        executed.put("orderStatus", payment.getOrderStatus());
        executed.put("beforeBalance", beforeBalance);

        log.info("写动作执行完成（已创建订单，待用户支付）: runId={}, orderNo={}, paymentNo={}, payYuan={}, credit={}",
                run.getId(), order.getOrderNo(), payment.getPaymentNo(), payYuan.toPlainString(), creditAmount);
        return new WriteActionResult(message, executed);
    }

    /**
     * 提案里的金额是**字符串**（record 里所有字段都是 String）。
     *
     * <p>这里是兜底重校验——真正的权威闸门是服务层的 {@code requireWholeYuan}
     * （整数元 + 区间 + 防溢出），提案端的拦截只是为了让用户更早看到提示。
     */
    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "提案缺少充值金额");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(BlogConstants.ErrorCode.BAD_REQUEST, "提案的充值金额无效");
        }
    }

    private long balanceOf(Long userId) {
        WalletVO wallet = walletService.getWallet(userId);
        return wallet == null || wallet.getBalance() == null ? 0L : wallet.getBalance();
    }
}
