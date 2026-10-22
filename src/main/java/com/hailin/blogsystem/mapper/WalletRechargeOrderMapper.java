package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 充值订单 Mapper。
 *
 * <p>「置 PAID」用<b>状态 CAS</b> 而不是无条件 UPDATE：并发双击时只有一个事务能推进状态，
 * 另一个影响 0 行，据此走幂等分支——否则同一张订单会被加两次余额。
 *
 * <p>关单同理：超时关单任务、用户取消、回调入账三者可能并发，
 * 每一次推进都必须带 {@code AND status = 'PENDING'}。
 */
@Mapper
public interface WalletRechargeOrderMapper extends BaseMapper<WalletRechargeOrder> {

    /**
     * 状态 CAS：只有仍是 PENDING 才置 PAID。
     *
     * @return 影响行数。0 表示已被别的请求改过状态（并发双击，或订单已取消）
     */
    @Update("""
            UPDATE wallet_recharge_order
            SET status = 'PAID',
                version = version + 1,
                paid_at = CURRENT_TIMESTAMP
            WHERE order_no = #{orderNo}
              AND status = 'PENDING'
            """)
    int markPaid(@Param("orderNo") String orderNo);

    /**
     * 关单 CAS：PENDING → CLOSED（超时 / 用户取消共用）。
     *
     * <p>不在这里判断是否真的超时——那是调用方的业务判断（惰性关单与定时关单
     * 的判据不同），CAS 只负责「只有一个执行者能真正关掉」。
     *
     * @return 影响行数。0 表示订单已不是 PENDING（已支付 / 已被别人关闭）
     */
    @Update("""
            UPDATE wallet_recharge_order
            SET status = 'CLOSED',
                version = version + 1,
                closed_at = CURRENT_TIMESTAMP,
                close_reason = #{reason}
            WHERE order_no = #{orderNo}
              AND status = 'PENDING'
            """)
    int markClosed(@Param("orderNo") String orderNo, @Param("reason") String reason);

    /**
     * 已过期但仍是 PENDING 的订单（超时关单任务用）。
     *
     * <p>必须按 (status, expire_at) 走索引；{@code expire_at IS NOT NULL} 是为了兼容
     * 加这一列之前的历史订单——它们没有过期时间，不该被关单任务当成「早该关了」。
     */
    @Select("""
            SELECT * FROM wallet_recharge_order
            WHERE status = 'PENDING'
              AND expire_at IS NOT NULL
              AND expire_at < CURRENT_TIMESTAMP
            ORDER BY expire_at
            LIMIT #{limit}
            """)
    List<WalletRechargeOrder> selectExpiredPending(@Param("limit") int limit);

    /**
     * 锁定读订单。
     *
     * <p>CAS 影响 0 行之后要靠它拿到<b>已提交的最新状态</b>：MySQL 默认 REPEATABLE READ 下，
     * 普通 SELECT 读到的是事务开始时的快照，可能还停在 PENDING，据此判断会得出错误结论。
     */
    @Select("SELECT * FROM wallet_recharge_order WHERE order_no = #{orderNo} FOR UPDATE")
    WalletRechargeOrder selectByOrderNoForUpdate(@Param("orderNo") String orderNo);
}
