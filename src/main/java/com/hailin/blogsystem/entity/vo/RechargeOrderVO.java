package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.WalletRechargeOrder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 充值订单视图。
 *
 * <p>只暴露前端要用的字段——{@code version} 属于内部并发控制，不出现在接口里。
 * {@code expireAt / closedAt / closeReason} 是超时关单闭环后前端要展示的状态：
 * 待支付倒计时、以及「为什么关了」。
 */
@Data
public class RechargeOrderVO {

    private String orderNo;
    private String packageCode;
    private Long payAmount;
    private Long creditAmount;
    private String status;
    private LocalDateTime expireAt;
    private LocalDateTime paidAt;
    private LocalDateTime closedAt;
    private String closeReason;
    private LocalDateTime createdAt;

    public static RechargeOrderVO from(WalletRechargeOrder order) {
        RechargeOrderVO vo = new RechargeOrderVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setPackageCode(order.getPackageCode());
        vo.setPayAmount(order.getPayAmount());
        vo.setCreditAmount(order.getCreditAmount());
        vo.setStatus(order.getStatus());
        vo.setExpireAt(order.getExpireAt());
        vo.setPaidAt(order.getPaidAt());
        vo.setClosedAt(order.getClosedAt());
        vo.setCloseReason(order.getCloseReason());
        vo.setCreatedAt(order.getCreatedAt());
        return vo;
    }
}
