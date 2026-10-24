package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.PaymentInitiateVO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletCustomConfigVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 充值订单服务（虚拟支付，零 Redis）。
 *
 * 设计稿：docs/redis/充值订单真实化设计.md（模型）/ docs/redis/钱包与秒杀计划.md §4（早期版本）
 *
 * <p><b>订单状态只能由回调驱动</b>：{@link #initiatePayment(String)} 只发起支付、不改订单状态；
 * 真正入账在 {@link WalletRechargeNotifyService#handleCallback} 里，与真实支付一致。
 * 这是「前端点支付成功 ≠ 入账成功」的落点。
 */
public interface WalletRechargeService extends IService<WalletRechargeOrder> {

    /** 充值套餐列表（来自 blog.wallet.packages 配置） */
    List<WalletPackageVO> listPackages();

    /** 自定义充值的配置（上下限 + 兑换比例），给前端做提示用（见 §4.6） */
    WalletCustomConfigVO getCustomConfig();

    /** 下单：按套餐快照落订单行，状态 PENDING，并写入支付截止时间 */
    RechargeOrderVO createOrder(String packageCode);

    /**
     * 自定义金额下单：金额由用户填（整数元），**到账额度仍由后端算**。
     *
     * <p>落下来的订单和套餐订单同构，支付走的是同一个 {@link #initiatePayment(String)}
     * ——不新开支付路径，那会把状态 CAS 那道防线绕掉。
     */
    RechargeOrderVO createCustomOrder(BigDecimal payYuan);

    /**
     * 发起支付（预支付）：创建（或复用）一条 CREATED 支付单，返回支付凭证。
     *
     * <p>订单仍是 PENDING——真实支付里「发起支付」和「支付成功」之间隔着用户操作与渠道回调。
     *
     * @throws com.hailin.blogsystem.exception.BusinessException 订单不存在 / 越权 / 已关闭 / 已超时
     */
    PaymentInitiateVO initiatePayment(String orderNo);

    /**
     * 模拟支付页确认付款（仅虚拟渠道）。
     *
     * <p>由 Service 以「渠道方」身份构造签名回调，再走
     * {@link WalletRechargeNotifyService#handleCallback}——与真实回调同一条路径，
     * 验签 / 幂等 / 金额校验一个都不少。
     */
    RechargeOrderVO mockPay(String orderNo, String payToken);

    /** 用户取消待支付订单；已支付订单不允许取消 */
    RechargeOrderVO cancelOrder(String orderNo);

    /** 订单详情（会先做惰性关单：已过期但仍是 PENDING 的当作已关闭） */
    RechargeOrderVO getOrder(String orderNo);

    /** 当前用户的订单列表（按创建时间倒序，会先做惰性关单） */
    PageVO<RechargeOrderVO> listOrders(long page, long pageSize);

    /**
     * 主动查单：回调可能丢失 / 延迟，补单任务据此把渠道事实拉回来。
     *
     * @return 是否发生了补单（true = 之前未入账，本次补上了）
     */
    boolean syncPayment(String orderNo);
}
