package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 充值支付单 Mapper。
 *
 * <p>所有状态推进一律 <b>CAS</b>（{@code AND status = 'CREATED'}）：回调可能被重复投递、
 * 关单任务与回调可能并发，谁先真正推进状态由影响行数决定——这是钱的操作里唯一可信的仲裁。
 */
@Mapper
public interface WalletPaymentMapper extends BaseMapper<WalletPayment> {

    @Select("SELECT * FROM wallet_payment WHERE payment_no = #{paymentNo}")
    WalletPayment selectByPaymentNo(@Param("paymentNo") String paymentNo);

    /** 该订单下最近一条 CREATED 支付单（发起支付时复用它，避免重复下单） */
    @Select("""
            SELECT * FROM wallet_payment
            WHERE order_no = #{orderNo} AND status = 'CREATED'
            ORDER BY id DESC
            LIMIT 1
            """)
    WalletPayment selectCreatedByOrderNo(@Param("orderNo") String orderNo);

    @Select("SELECT * FROM wallet_payment WHERE order_no = #{orderNo} ORDER BY id")
    List<WalletPayment> selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 回调入账第一步：支付单 CREATED → SUCCESS。
     *
     * @return 影响行数；0 表示已被并发回调推进过，调用方必须重新读取后按幂等分支处理
     */
    @Update("""
            UPDATE wallet_payment
            SET status = 'SUCCESS',
                channel_trade_no = #{channelTradeNo},
                paid_at = CURRENT_TIMESTAMP,
                callback_payload = #{callbackPayload},
                updated_at = CURRENT_TIMESTAMP
            WHERE payment_no = #{paymentNo}
              AND status = 'CREATED'
            """)
    int markSuccess(@Param("paymentNo") String paymentNo,
                    @Param("channelTradeNo") String channelTradeNo,
                    @Param("callbackPayload") String callbackPayload);

    @Update("""
            UPDATE wallet_payment
            SET status = 'FAILED',
                fail_reason = #{reason},
                updated_at = CURRENT_TIMESTAMP
            WHERE payment_no = #{paymentNo}
              AND status = 'CREATED'
            """)
    int markFailed(@Param("paymentNo") String paymentNo, @Param("reason") String reason);

    /** 订单关闭时联动关闭其下所有 CREATED 支付单（同一事务） */
    @Update("""
            UPDATE wallet_payment
            SET status = 'CLOSED',
                updated_at = CURRENT_TIMESTAMP
            WHERE order_no = #{orderNo}
              AND status = 'CREATED'
            """)
    int markClosedByOrderNo(@Param("orderNo") String orderNo);

    /** 补单扫描：创建超过阈值仍是 CREATED 的支付单 */
    @Select("""
            SELECT * FROM wallet_payment
            WHERE status = 'CREATED'
              AND created_at < #{before}
            ORDER BY id
            LIMIT #{limit}
            """)
    List<WalletPayment> selectStaleCreated(@Param("before") LocalDateTime before,
                                           @Param("limit") int limit);

    /**
     * 对账：支付单已成功、但订单不是 PAID（掉单）。
     *
     * <p>真实场景这是渠道扣了钱、本地没入账——最贵的一类不一致，必须告警。
     */
    @Select("""
            SELECT p.* FROM wallet_payment p
            JOIN wallet_recharge_order o ON p.order_no = o.order_no
            WHERE p.status = 'SUCCESS'
              AND o.status <> 'PAID'
            LIMIT #{limit}
            """)
    List<WalletPayment> selectSuccessWithoutPaidOrder(@Param("limit") int limit);

    /**
     * 对账：订单已 PAID、但没有任何 SUCCESS 支付单（脏单/绕过回调的写入）。
     */
    @Select("""
            SELECT o.* FROM wallet_recharge_order o
            WHERE o.status = 'PAID'
              AND NOT EXISTS (
                    SELECT 1 FROM wallet_payment p
                    WHERE p.order_no = o.order_no AND p.status = 'SUCCESS'
                  )
            LIMIT #{limit}
            """)
    List<WalletRechargeOrder> selectPaidWithoutSuccessPayment(@Param("limit") int limit);

    /**
     * 对账：支付单成功金额与订单应付金额不一致（串单 / 数据被改）。
     */
    @Select("""
            SELECT p.* FROM wallet_payment p
            JOIN wallet_recharge_order o ON p.order_no = o.order_no
            WHERE p.status = 'SUCCESS'
              AND p.pay_amount <> o.pay_amount
            LIMIT #{limit}
            """)
    List<WalletPayment> selectAmountMismatch(@Param("limit") int limit);
}
