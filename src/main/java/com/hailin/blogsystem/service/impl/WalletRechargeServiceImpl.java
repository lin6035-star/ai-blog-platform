package com.hailin.blogsystem.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletCustomConfigVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.WalletRechargeOrderMapper;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.OrderNoGenerator;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 充值订单服务实现（虚拟支付）。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §4
 *
 * <p><b>幂等靠什么</b>：订单号 + 状态 CAS（第二层）+ 流水唯一索引（第三层）。
 * <b>不引入 Idempotency-Key 存储</b>——项目现有的 {@code WorkflowActionIdempotency}
 * 是 Redis 的，而钱的操作不能依赖 Redis（Redis 挂了幂等就失效）。
 * 订单号在这个场景里本身就是天然的请求去重键，比再引入一层 key 存储更强。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WalletRechargeServiceImpl extends ServiceImpl<WalletRechargeOrderMapper, WalletRechargeOrder>
        implements WalletRechargeService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_PAID = "PAID";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final WalletRechargeOrderMapper rechargeOrderMapper;
    private final WalletService walletService;
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

        WalletRechargeOrder order = new WalletRechargeOrder();
        order.setOrderNo(OrderNoGenerator.next("W"));
        order.setUserId(userId);
        order.setPackageCode(packageCode);
        // 金额与到账额度从套餐快照写入——请求体里根本没有金额字段，前端说了不算
        order.setPayAmount(pkg.getPayAmount());
        order.setCreditAmount(pkg.getCreditAmount());
        order.setStatus(STATUS_PENDING);
        order.setVersion(0);
        save(order);

        log.info("[RECHARGE] 下单 user_id={} order_no={} package={} credit={}",
                userId, order.getOrderNo(), packageCode, pkg.getCreditAmount());
        return RechargeOrderVO.from(order);
    }

    @Override
    public RechargeOrderVO createCustomOrder(BigDecimal payYuan) {
        Long userId = requireLogin();

        long yuan = requireWholeYuan(payYuan);
        long creditPerYuan = walletProperties.getCustom().getCreditPerYuan();
        // multiplyExact 而不是 *：配置被改坏（max-yuan 多打几个 0）时溢出会**在这里**炸，
        // 而不是悄悄绕成负数、写进一张负金额订单，等到 pay 时才在
        // WalletServiceImpl.increase 的 amount <= 0 上报一个指不到根因的错
        long payAmount = Math.multiplyExact(yuan, 100L);
        long creditAmount = Math.multiplyExact(yuan, creditPerYuan);

        WalletRechargeOrder order = new WalletRechargeOrder();
        // 复用 "W" 前缀：pay / completePaidOrder 不按前缀分支，多一个前缀只是给后来的人
        // 多一个必须知道的东西。区分自定义订单靠 package_code = 'CUSTOM'，它可查、可索引
        order.setOrderNo(OrderNoGenerator.next("W"));
        order.setUserId(userId);
        order.setPackageCode(BlogConstants.Wallet.CUSTOM_PACKAGE_CODE);
        order.setPayAmount(payAmount);
        order.setCreditAmount(creditAmount);
        order.setStatus(STATUS_PENDING);
        order.setVersion(0);
        save(order);

        log.info("[RECHARGE] 自定义下单 user_id={} order_no={} pay_yuan={} credit={}",
                userId, order.getOrderNo(), yuan, creditAmount);
        return RechargeOrderVO.from(order);
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

    @Override
    @Transactional
    public RechargeOrderVO pay(String orderNo) {
        Long userId = requireLogin();

        WalletRechargeOrder order = lambdaQuery()
                .eq(WalletRechargeOrder::getOrderNo, orderNo)
                .one();
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }
        // 归属校验不能省：orderNo 是可枚举的短字符串，不校验等于把别人的订单
        // 交给任何人「代付」——付完余额加到别人账上。
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(BlogConstants.ErrorCode.FORBIDDEN, "无权操作该订单");
        }
        if (STATUS_CANCELLED.equals(order.getStatus())) {
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单已取消，无法支付");
        }
        if (STATUS_PAID.equals(order.getStatus())) {
            // 已经付过了：幂等成功，不能再加一次余额
            return RechargeOrderVO.from(order);
        }

        // 状态 CAS：并发双击时只有一个事务能推进状态
        if (rechargeOrderMapper.markPaid(orderNo) == 0) {
            // 影响 0 行必须重新确认状态，绝不能继续加余额。
            // 这里用锁定读而不是普通 SELECT：MySQL 默认 REPEATABLE READ 下，
            // 普通读走的是事务开始时的快照，可能还停在 PENDING，据此判断会得出错误结论。
            WalletRechargeOrder latest = rechargeOrderMapper.selectByOrderNoForUpdate(orderNo);
            if (latest != null && STATUS_PAID.equals(latest.getStatus())) {
                return RechargeOrderVO.from(latest);
            }
            throw new BusinessException(BlogConstants.ErrorCode.CONFLICT, "订单状态已变更，请刷新后重试");
        }

        return completePaidOrder(orderNo);
    }

    /**
     * 加余额 + 写流水。
     *
     * <p>与 {@link #pay} 的自调用共享同一个事务（Spring 自调用不走代理），
     * 所以「CAS 置 PAID」和「加余额」要么一起成功、要么一起回滚。
     */
    @Override
    @Transactional
    public RechargeOrderVO completePaidOrder(String orderNo) {
        WalletRechargeOrder order = lambdaQuery()
                .eq(WalletRechargeOrder::getOrderNo, orderNo)
                .one();
        if (order == null) {
            throw new BusinessException(BlogConstants.ErrorCode.NOT_FOUND, "订单不存在");
        }

        // 记账幂等键由订单号派生：即使订单被推进两次，第二笔流水也会撞 uk_user_idem 而回滚，
        // 不会出现「加了两次钱」。这是三层防线里的最后一层。
        walletService.increase(
                order.getUserId(),
                order.getCreditAmount(),
                "RECHARGE",
                "RECHARGE_ORDER",
                orderNo,
                "RECHARGE:" + orderNo,
                rechargeRemark(order));

        log.info("[RECHARGE] 支付完成 user_id={} order_no={} credit=+{}",
                order.getUserId(), orderNo, order.getCreditAmount());
        return RechargeOrderVO.from(order);
    }

    /**
     * 流水备注。
     *
     * <p>自定义订单没有套餐码，直接拼会得到「充值套餐 CUSTOM」——一句对账时没人看得懂的话。
     * 所以按金额说人话。套餐分支的文案**一字不改**，历史流水的写法保持原样。
     *
     * <p>{@code payAmount / 100} 在自定义分支上是精确的：金额由「整数元 × 100」得到，
     * 必然是 100 的整数倍。这个整除**不能**推广到套餐分支——套餐金额可以是非整元。
     */
    private String rechargeRemark(WalletRechargeOrder order) {
        if (BlogConstants.Wallet.CUSTOM_PACKAGE_CODE.equals(order.getPackageCode())) {
            return "自定义充值 " + order.getPayAmount() / 100 + " 元";
        }
        return "充值套餐 " + order.getPackageCode();
    }

    private Long requireLogin() {
        Long userId = UserContext.get();
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }
}
