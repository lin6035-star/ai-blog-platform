package com.hailin.blogsystem.task;

import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.mapper.WalletRechargeOrderMapper;
import com.hailin.blogsystem.service.impl.WalletRechargeCloseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 充值订单超时关单（设计稿 §6.4）。
 *
 * <p><b>为什么还需要它</b>：惰性关单只覆盖「用户主动来看/来付」的场景，
 * 用户下单后直接关页面，那张单会永久 PENDING——除非有人主动扫。
 *
 * <p><b>逐单独立事务</b>：一笔关单失败不能拖垮整批；{@code closeIfExpired}
 * 在 {@link WalletRechargeCloseService}（另一个 bean）上，所以事务是逐单生效的。
 *
 * <p><b>关单前查渠道</b>的逻辑在 closeIfExpired 里：渠道已收款就不关，等补单任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletRechargeOrderCloseTask {

    /** 单轮上限：关单是维护性动作，不能让它长时间占着连接 */
    private static final int BATCH = 100;

    private final WalletRechargeOrderMapper walletRechargeOrderMapper;
    private final WalletRechargeCloseService walletRechargeCloseService;

    @Scheduled(fixedDelay = 60_000L)
    public void closeExpiredOrders() {
        List<WalletRechargeOrder> expired = walletRechargeOrderMapper.selectExpiredPending(BATCH);
        if (expired.isEmpty()) {
            return;
        }
        int closed = 0;
        int failed = 0;
        for (WalletRechargeOrder order : expired) {
            try {
                if (walletRechargeCloseService.closeIfExpired(order)) {
                    closed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("[RECHARGE-CLOSE] 单笔关单失败已跳过，可重跑：orderNo={}", order.getOrderNo(), e);
            }
        }
        log.info("[RECHARGE-CLOSE] 超时关单扫描：候选={} 关闭={} 失败={}", expired.size(), closed, failed);
    }
}
