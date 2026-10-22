package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.entity.vo.PaymentInitiateVO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletCustomConfigVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.WalletPaymentMapper;
import com.hailin.blogsystem.mapper.WalletRechargeOrderMapper;
import com.hailin.blogsystem.service.WalletRechargeNotifyService;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.utils.OrderNoGenerator;
import com.hailin.blogsystem.utils.UserContext;
import com.hailin.blogsystem.wallet.ChannelPaymentStatus;
import com.hailin.blogsystem.wallet.PaymentCallback;
import com.hailin.blogsystem.wallet.PaymentChannel;
import com.hailin.blogsystem.wallet.SignedCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 充值订单服务实现（虚拟支付，真实支付模型）。
 *
 * 设计稿：docs/redis/充值订单真实化设计.md
 *
 * <p><b>与改造前的关键差别</b>：{@link #initiatePayment(String)} 只发起支付、不改订单状态；
 * 真正入账发生在 {@link WalletRechargeNotifyService#handleCallback}（验签通过之后）。
 * 前端点「支付成功」不等于入账成功——与真实支付一致。
 *
 * <p><b>幂等</b>：订单号 + 支付单状态 CAS + 订单状态 CAS + 流水唯一索引，四层防线。
 * 不引入 Idempotency-Key 存储——钱的操作不能依赖 Redis（Redis 挂了幂等就失效）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalletRechargeServiceImpl extends ServiceImpl<WalletRechargeOrderMapper, WalletRechargeOrder>
        implements WalletRechargeService {

    private static final String STATUS_PENDING = BlogConstants.Wallet.ORDER_PENDING;
    private static final String STATUS_PAID = BlogConstants.Wallet.ORDER_PAID;
    private static final String STATUS_CLOSED = BlogConstants.Wallet.ORDER_CLOSED;
    private static final String PAYMENT_CREATED = BlogConstants.Wallet.PAYMENT_CREATED;

    private final WalletRechargeOrderMapper rechargeOrderMapper;
    private final WalletPaymentMapper walletPaymentMapper;
    private final WalletRechargeNotifyService walletRechargeNotifyService;
    private final WalletRechargeCloseService walletRechargeCloseService;
    private final PaymentChannel paymentChannel;
    private final BlogWalletProperties walletProperties;

    @Override
    public List<WalletPackageVO> listPackages() {
        return walletProperties.getPackages().entrySet().stream()
                .map(entry -> {
                    WalletPackageVO vo = new WalletPackageVO();
                    vo.setCode(entry.getKey());
                    vo.setPayAmount(entry.getValue().getPayAmount());
                    vo.setCreditAmount(entry.getValue().getCreditAmount());
                    return vo;
                })
                .toList();
    }

    @Override
    public WalletCustomConfigVO getCustomConfig() {
        BlogWalletProperties.Custom custom = walletProperties.getCustom();

        WalletCustomConfigVO vo = new WalletCustomConfigVO();
        vo.setMinYuan(custom.getMinYuan());
        vo.setMaxYuan(custom.getMaxYuan());
        vo.setCreditPerYuan(custom.getCreditPerYuan());
        return vo;
    }

    @Override
    public RechargeOrderVO createOrder(String packageCode) {
        Long userId = requireLogin();

        BlogWalletProperties.Package pkg = walletProperties.getPackages().get(packageCode);
        if (pkg == null) {
            throw new IllegalArgumentException("套餐不存在：" + packageCode);
        }

        WalletRechargeOrder order = newOrder(userId, packageCode, pkg.getPayAmount(), pkg.getCreditAmount());
        save(order);

        log.info("[RECHARGE] 下单 user_id={} order_no={} package={} credit={} expireAt={}",
                userId, order.getOrderNo(), packageCode, pkg.getCreditAmount(), order.getExpireAt());
        return RechargeOrderVO.from(order);
    }

    @Override
    public RechargeOrderVO createCustomOrder(BigDecimal payYuan) {
        Long userId = requireLogin();

        long yuan = requireWholeYuan(payYuan);
        long creditPerYuan = walletProperties.getCustom().getCreditPerYuan();
        // multiplyExact 而不是 *：配置被改坏（max-yuan 多打几个 0）时溢出会**在这里**炸，
        // 而不是悄悄绕成负数、写进一张负金额订单，等到入账时才在
        // WalletServiceImpl.increase 的 amount <= 0 上报一个指不到根因的错
        long payAmount = Math.multiplyExact(yuan, 100L);
        long creditAmount = Math.multiplyExact(yuan, creditPerYuan);

        WalletRechargeOrder order = newOrder(
                userId, BlogConstants.Wallet.CUSTOM_PACKAGE_CODE, payAmount, creditAmount);
        save(order);

        log.info("[RECHARGE] 自定义下单 user_id={} order_no={} pay_yuan={} credit={} expireAt={}",
                userId, order.getOrderNo(), yuan, creditAmount, order.getExpireAt());
        return RechargeOrderVO.from(order);
    }

    private WalletRechargeOrder newOrder(Long userId, String packageCode, long payAmount, long creditAmount) {
        WalletRechargeOrder order = new WalletRechargeOrder();
        order.setOrderNo(OrderNoGenerator.next("W"));
        order.setUserId(userId);
        order.setPackageCode(packageCode);
        // 金额与到账额度从套餐快照写入——请求体里根本没有金额字段，前端说了不算
        order.setPayAmount(payAmount);
        order.setCreditAmount(creditAmount);
        order.setStatus(STATUS_PENDING);
        order.setVersion(0);
        // 支付截止时间：超时关单任务的判据，也是惰性关单的判据。
        // 下限 1 分钟：配置写成 0/负数时订单会立刻过期，用户点了支付直接提示"已超时"
        long timeoutMinutes = Math.max(1L, walletProperties.getOrderTimeoutMinutes());
        order.setExpireAt(LocalDateTime.now().plusMinutes(timeoutMinutes));
        return order;
    }

    @Override
    @Transactional
    public PaymentInitiateVO initiatePayment(String orderNo) {
        Long userId = requireLogin();

        // 锁定读：同一订单的并发「发起支付」在这里串行化，避免一次点击建出两个支付单
        WalletRechargeOrder order = rechargeOrderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }
        // 归属校验不能省：orderNo 是可枚举的短字符串，不校验等于把别人的订单
        // 交给任何人「代付」——付完余额加到别人账上。
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (STATUS_PAID.equals(order.getStatus())) {
            // 已支付：幂等返回（给前端「不用再付了」的信号，而不是把重复点击当失败）
            PaymentInitiateVO vo = new PaymentInitiateVO();
            vo.setOrderNo(order.getOrderNo());
            vo.setOrderStatus(order.getStatus());
            vo.setPayAmount(order.getPayAmount());
            vo.setCreditAmount(order.getCreditAmount());
            return vo;
        }
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单已关闭，请重新下单");
        }
        if (isExpired(order)) {
            // 惰性关单：定时任务还没扫到也没关系，用户一碰就知道真相
            walletRechargeCloseService.closeIfExpired(order);
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单已超时关闭，请重新下单");
        }

        WalletPayment payment = walletPaymentMapper.selectCreatedByOrderNo(orderNo);
        if (payment == null) {
            payment = new WalletPayment();
            payment.setPaymentNo(OrderNoGenerator.next("P"));
            payment.setOrderNo(orderNo);
            payment.setUserId(userId);
            payment.setChannel(paymentChannel.name());
            payment.setPayAmount(order.getPayAmount());
            payment.setStatus(PAYMENT_CREATED);
            payment.setPayToken(paymentChannel.initiate(payment));
            walletPaymentMapper.insert(payment);
            log.info("[RECHARGE] 发起支付 user_id={} order_no={} payment_no={} channel={}",
                    userId, orderNo, payment.getPaymentNo(), payment.getChannel());
        }

        PaymentInitiateVO vo = new PaymentInitiateVO();
        vo.setOrderNo(order.getOrderNo());
        vo.setPaymentNo(payment.getPaymentNo());
        vo.setChannel(payment.getChannel());
        vo.setPayToken(payment.getPayToken());
        vo.setPayAmount(order.getPayAmount());
        vo.setCreditAmount(order.getCreditAmount());
        vo.setOrderStatus(order.getStatus());
        vo.setExpireAt(order.getExpireAt());
        return vo;
    }

    @Override
    public RechargeOrderVO mockPay(String orderNo, String payToken) {
        // 刻意不开事务：回调处理（handleCallback）自己就是一个事务，
        // 这里再包一层只会把两个边界混在一起，反而看不出哪一步失败。
        Long userId = requireLogin();

        WalletRechargeOrder order = getByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (STATUS_PAID.equals(order.getStatus())) {
            return RechargeOrderVO.from(order);
        }
        if (!STATUS_PENDING.equals(order.getStatus())) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单已关闭，无法支付");
        }
        if (isExpired(order)) {
            walletRechargeCloseService.closeIfExpired(order);
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单已超时关闭，请重新下单");
        }

        WalletPayment payment = walletPaymentMapper.selectCreatedByOrderNo(orderNo);
        if (payment == null) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "没有待支付的支付单，请先发起支付");
        }

        // 由 Mock 渠道扮演「用户在渠道内完成付款」，构造签名回调后走与真实回调同一个入口
        SignedCallback callback = paymentChannel.simulatePaidCallback(payment, payToken);
        walletRechargeNotifyService.handleCallback(
                paymentChannel.name(), callback.payload(), callback.signature());

        return RechargeOrderVO.from(getByOrderNo(orderNo));
    }

    @Override
    @Transactional
    public RechargeOrderVO cancelOrder(String orderNo) {
        Long userId = requireLogin();

        WalletRechargeOrder order = rechargeOrderMapper.selectByOrderNoForUpdate(orderNo);
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单已支付，不能取消");
        }
        if (STATUS_CLOSED.equals(order.getStatus())) {
            // 幂等：重复取消直接返回当前状态
            return RechargeOrderVO.from(order);
        }

        int changed = rechargeOrderMapper.markClosed(orderNo, BlogConstants.Wallet.CLOSE_USER_CANCELLED);
        if (changed == 0) {
            WalletRechargeOrder latest = rechargeOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (latest != null && STATUS_CLOSED.equals(latest.getStatus())) {
                return RechargeOrderVO.from(latest);
            }
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单状态已变更，请刷新后重试");
        }
        // 联动关闭未支付的支付单：否则它们会挂着 CREATED，补单任务白扫一轮
        walletPaymentMapper.markClosedByOrderNo(orderNo);

        log.info("[RECHARGE] 用户取消订单 user_id={} order_no={}", userId, orderNo);
        return RechargeOrderVO.from(selectByOrderNo(orderNo));
    }

    @Override
    public RechargeOrderVO getOrder(String orderNo) {
        Long userId = requireLogin();

        WalletRechargeOrder order = getByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        // 惰性关单：打开订单页时顺手把已过期的关掉，不依赖定时任务是否跑过
        if (STATUS_PENDING.equals(order.getStatus()) && isExpired(order)
                && walletRechargeCloseService.closeIfExpired(order)) {
            order = getByOrderNo(orderNo);
        }
        return RechargeOrderVO.from(order);
    }

    @Override
    public PageVO<RechargeOrderVO> listOrders(long page, long pageSize) {
        Long userId = requireLogin();

        // 惰性关单：先把这个用户已过期的 PENDING 订单关掉（用户自己的单，量很小）
        List<WalletRechargeOrder> expired = lambdaQuery()
                .eq(WalletRechargeOrder::getUserId, userId)
                .eq(WalletRechargeOrder::getStatus, STATUS_PENDING)
                .isNotNull(WalletRechargeOrder::getExpireAt)
                .lt(WalletRechargeOrder::getExpireAt, LocalDateTime.now())
                .list();
        for (WalletRechargeOrder order : expired) {
            walletRechargeCloseService.closeIfExpired(order);
        }

        Page<WalletRechargeOrder> result = page(
                new Page<>(page, pageSize),
                new LambdaQueryWrapper<WalletRechargeOrder>()
                        .eq(WalletRechargeOrder::getUserId, userId)
                        .orderByDesc(WalletRechargeOrder::getCreatedAt)
                        .orderByDesc(WalletRechargeOrder::getId));

        List<RechargeOrderVO> list = result.getRecords().stream()
                .map(RechargeOrderVO::from)
                .toList();
        return new PageVO<>(list, result.getTotal(), page, pageSize);
    }

    @Override
    public boolean syncPayment(String orderNo) {
        Long userId = requireLogin();

        WalletRechargeOrder order = getByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        for (WalletPayment payment : walletPaymentMapper.selectByOrderNo(order.getOrderNo())) {
            if (!PAYMENT_CREATED.equals(payment.getStatus())) {
                continue;
            }
            if (paymentChannel.query(payment) == ChannelPaymentStatus.SUCCESS) {
                // 主动查单是服务端行为，没有渠道签名报文；
                // rawPayload 用一条可识别的 JSON 偏移，审计时能区分「回调入账」和「查单补单」
                PaymentCallback callback = new PaymentCallback(
                        payment.getPaymentNo(),
                        payment.getOrderNo(),
                        payment.getChannelTradeNo(),
                        payment.getPayAmount(),
                        System.currentTimeMillis() / 1000,
                        "{\"source\":\"CHANNEL_QUERY\"}");
                return walletRechargeNotifyService.applyChannelSuccess(payment, callback);
            }
        }
        return false;
    }

    private WalletRechargeOrder getByOrderNo(String orderNo) {
        return lambdaQuery().eq(WalletRechargeOrder::getOrderNo, orderNo).one();
    }

    private WalletRechargeOrder selectByOrderNo(String orderNo) {
        return lambdaQuery().eq(WalletRechargeOrder::getOrderNo, orderNo).one();
    }

    private boolean isExpired(WalletRechargeOrder order) {
        return order.getExpireAt() != null && !order.getExpireAt().isAfter(LocalDateTime.now());
    }

    /**
     * 「整数元 + 落在 [min, max]」——自定义充值的**唯一**一道金额闸门。
     *
     * <p>刻意不放在 DTO 的 {@code @Min}/{@code @Max} 上：上下限来自配置
     * （{@code blog.wallet.custom.min-yuan/max-yuan}），而注解参数必须是编译期常量，
     * 两者天生冲突——改了 yml 注解不会跟着变，那是必然漂移。放在这里还多一个好处：
     * 守卫、元→分换算、credit 公式挨在一起，改一个不会漏看另外两个。
     */
    private long requireWholeYuan(BigDecimal payYuan) {
        if (payYuan == null) {
            throw new IllegalArgumentException("请填写充值金额");
        }
        // stripTrailingZeros 之后 scale <= 0 才是整数：5 / 5.0 通过，5.5 拒绝。
        // ⚠️ 必须先剥尾零再判——new BigDecimal("5.0").scale() 是 1，直接判会把 5.0 误拒
        if (payYuan.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("充值金额必须是整数元");
        }

        long yuan;
        try {
            yuan = payYuan.longValueExact();
        } catch (ArithmeticException e) {
            // 超出 long 范围的值（如 1e30，它的 scale 是 -30，上面那一关拦不住）。
            // ⚠️ 必须转成 IllegalArgumentException：ArithmeticException 会一路落到
            // GlobalExceptionHandler 的 Exception 兜底，返回 50000「服务器内部错误」——
            // 把「你传错了」说成「我坏了」，方向和事实都反了
            throw new IllegalArgumentException("充值金额不合法");
        }

        BlogWalletProperties.Custom custom = walletProperties.getCustom();
        if (yuan < custom.getMinYuan() || yuan > custom.getMaxYuan()) {
            throw new IllegalArgumentException(
                    "充值金额需在 " + custom.getMinYuan() + " ~ " + custom.getMaxYuan() + " 元之间");
        }
        return yuan;
    }

    private Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }
}
