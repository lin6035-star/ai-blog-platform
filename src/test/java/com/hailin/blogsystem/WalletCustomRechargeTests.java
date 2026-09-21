package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.entity.WalletTransaction;
import com.hailin.blogsystem.entity.dto.WalletCustomRechargeDTO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletCustomConfigVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.WalletTransactionMapper;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自定义金额充值：金额闸门 + 支付链路 + 元/分口径。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §4.6
 *
 * <p>金额闸门是这里唯一的输入校验点（DTO 上刻意没有 {@code @Min/@Max}，因为上下限来自配置
 * 而注解参数必须是编译期常量）。闸门写漏的出错方向是钱：0 元、负数、小数都可能变成白拿额度。
 *
 * <p>支付链路那几条用来证明自定义订单走的是**同一套** {@code pay}——
 * 如果哪天有人给它单开一条路径，CAS 那道防线就会静默消失，而它只在并发双击时才现形。
 */
@SpringBootTest
class WalletCustomRechargeTests {

    @Autowired
    private WalletRechargeService walletRechargeService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletTransactionMapper walletTransactionMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private final long userId = 9_300_000_000_000L + System.nanoTime() % 1_000_000_000L;
    private final long otherUserId = userId + 1;

    @BeforeEach
    void setUp() {
        UserContext.set(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void assertRejected(BigDecimal payYuan, String messagePart) {
        assertThatThrownBy(() -> walletRechargeService.createCustomOrder(payYuan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }

    private WalletTransaction loadRechargeTransaction(String orderNo) {
        return walletTransactionMapper.selectOne(new LambdaQueryWrapper<WalletTransaction>()
                .eq(WalletTransaction::getUserId, userId)
                .eq(WalletTransaction::getBizId, orderNo));
    }

    // ---------- 配置 ----------

    @Test
    void customConfigComesFromProperties() {
        WalletCustomConfigVO config = walletRechargeService.getCustomConfig();

        assertThat(config.getMinYuan()).isPositive();
        assertThat(config.getMaxYuan()).isGreaterThanOrEqualTo(config.getMinYuan());
        assertThat(config.getCreditPerYuan()).isPositive();
    }

    /**
     * 产品不变量：自定义充值不能比任何套餐**更**划算。
     *
     * <p>自定义卖的是「想充多少充多少」的灵活度，套餐卖的是折扣。
     * ⚠️ 注意是「不超过」而不是「严格低于」：PACK_10 是 1 元 = 1000 credit，
     * <b>它本来就是基准价、没有优惠</b>，和自定义同价是刻意的设计——自定义等于原价买灵活度，
     * 想要折扣就选 PACK_30 / PACK_98。写成严格低于会当场误报。
     */
    @Test
    void customRateIsNeverBetterThanAnyPackage() {
        long creditPerYuan = walletRechargeService.getCustomConfig().getCreditPerYuan();

        for (WalletPackageVO pkg : walletRechargeService.listPackages()) {
            long packageCreditPerYuan = pkg.getCreditAmount() * 100 / pkg.getPayAmount();
            assertThat(creditPerYuan)
                    .as("套餐 %s 是 1 元 = %d credit，自定义不该比它更划算",
                            pkg.getCode(), packageCreditPerYuan)
                    .isLessThanOrEqualTo(packageCreditPerYuan);
        }
    }

    // ---------- 金额闸门 ----------

    @Test
    void rejectsMissingAmount() {
        assertRejected(null, "请填写");
    }

    @Test
    void rejectsZero() {
        assertRejected(BigDecimal.ZERO, "元之间");
    }

    @Test
    void rejectsNegative() {
        assertRejected(new BigDecimal("-1"), "元之间");
    }

    @Test
    void rejectsAboveMax() {
        assertRejected(new BigDecimal("1001"), "元之间");
    }

    @Test
    void rejectsFractionalYuan() {
        assertRejected(new BigDecimal("5.5"), "整数元");
    }

    /**
     * 超出 long 范围的值必须是「你传错了」而不是「我坏了」。
     *
     * <p>1e30 的 scale 是 -30，能通过整数判定，然后在 {@code longValueExact} 抛
     * {@code ArithmeticException}——那会一路落到 GlobalExceptionHandler 的 Exception 兜底，
     * 返回 50000「服务器内部错误」。所以 service 里必须显式 catch 并转成
     * IllegalArgumentException（→ 40001）。这条锁的正是那个转换。
     */
    @Test
    void rejectsHugeValueAsBadRequestNotServerError() {
        assertRejected(new BigDecimal("1e30"), "不合法");
    }

    /**
     * 5.0 是整数元，必须放行。
     *
     * <p>⚠️ 锁的是 {@code stripTrailingZeros}：{@code new BigDecimal("5.0").scale()} 是 1，
     * 若直接判 scale 会把 JSON 里写成 {@code 5.0} 的正常请求误拒。
     */
    @Test
    void acceptsWholeNumberWithTrailingZeros() {
        RechargeOrderVO order = walletRechargeService.createCustomOrder(new BigDecimal("5.0"));

        assertThat(order.getPayAmount()).isEqualTo(500L);
        assertThat(order.getCreditAmount()).isEqualTo(5000L);
    }

    // ---------- 下单 ----------

    @Test
    void acceptsMinimumYuan() {
        RechargeOrderVO order = walletRechargeService.createCustomOrder(new BigDecimal("1"));

        assertThat(order.getPayAmount()).isEqualTo(100L);
        assertThat(order.getCreditAmount()).isEqualTo(1000L);
    }

    @Test
    void acceptsMaximumYuan() {
        WalletCustomConfigVO config = walletRechargeService.getCustomConfig();
        long max = config.getMaxYuan();

        RechargeOrderVO order = walletRechargeService.createCustomOrder(BigDecimal.valueOf(max));

        assertThat(order.getPayAmount()).isEqualTo(max * 100);
        assertThat(order.getCreditAmount()).isEqualTo(max * config.getCreditPerYuan());
    }

    @Test
    void customOrderSnapshotsAmountsFromServerNotFromRequest() {
        RechargeOrderVO order = walletRechargeService.createCustomOrder(new BigDecimal("5"));

        assertThat(order.getOrderNo()).startsWith("W");
        assertThat(order.getStatus()).isEqualTo("PENDING");
        assertThat(order.getPackageCode()).isEqualTo("CUSTOM");
        assertThat(order.getPayAmount()).isEqualTo(500L);
        assertThat(order.getCreditAmount()).isEqualTo(5000L);
    }

    // ---------- 支付链路（证明复用的是同一套 pay） ----------

    @Test
    void payCustomOrderCreditsWallet() {
        RechargeOrderVO order = walletRechargeService.createCustomOrder(new BigDecimal("5"));

        RechargeOrderVO paid = walletRechargeService.pay(order.getOrderNo());

        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(5000L);
    }

    /** 幂等：重复支付只加一次钱。和套餐那条同源，出错方向是「用户白拿额度」 */
    @Test
    void payCustomOrderIsIdempotent() {
        RechargeOrderVO order = walletRechargeService.createCustomOrder(new BigDecimal("5"));

        walletRechargeService.pay(order.getOrderNo());
        RechargeOrderVO second = walletRechargeService.pay(order.getOrderNo());

        assertThat(second.getStatus()).isEqualTo("PAID");
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(5000L);
    }

    @Test
    void payCustomOrderRejectsOtherUser() {
        RechargeOrderVO order = walletRechargeService.createCustomOrder(new BigDecimal("5"));

        UserContext.set(otherUserId);
        assertThatThrownBy(() -> walletRechargeService.pay(order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");

        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    @Test
    void customAndPackageOrdersCoexist() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);

        RechargeOrderVO packageOrder = walletRechargeService.createOrder(pkg.getCode());
        walletRechargeService.pay(packageOrder.getOrderNo());
        RechargeOrderVO customOrder = walletRechargeService.createCustomOrder(new BigDecimal("5"));
        walletRechargeService.pay(customOrder.getOrderNo());

        assertThat(walletService.getWallet(userId).getBalance())
                .isEqualTo(pkg.getCreditAmount() + 5000L);
    }

    // ---------- 流水备注 ----------

    /** 「充值套餐 CUSTOM」是一句对账时没人看得懂的话，所以自定义分支按金额说人话 */
    @Test
    void customOrderRemarkReadsYuanNotPackageCode() {
        RechargeOrderVO order = walletRechargeService.createCustomOrder(new BigDecimal("5"));
        walletRechargeService.pay(order.getOrderNo());

        assertThat(loadRechargeTransaction(order.getOrderNo()).getRemark())
                .isEqualTo("自定义充值 5 元");
    }

    /** 套餐分支的文案一字不改——历史流水的写法必须保持原样 */
    @Test
    void packageOrderRemarkStaysUnchanged() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());
        walletRechargeService.pay(order.getOrderNo());

        assertThat(loadRechargeTransaction(order.getOrderNo()).getRemark())
                .isEqualTo("充值套餐 " + pkg.getCode());
    }

    // ---------- DTO 类型锁定 ----------

    /**
     * 1.5 必须原样到达 service，不能被静默截断成 1。
     *
     * <p>这条锁的是 <b>DTO 的字段类型</b>：Jackson 默认开启
     * {@code ACCEPT_FLOAT_AS_INT}，若 {@code payYuan} 被「简化」成 {@code Long}，
     * {@code {"payYuan":1.5}} 会变成 1——<b>不报错、无日志</b>，而 service 层的测试
     * 会照样全绿（它拿到的是已经截断过的 1）。只有这条能抓到。
     */
    @Test
    void dtoKeepsFractionalInputInsteadOfTruncating() throws Exception {
        WalletCustomRechargeDTO dto =
                objectMapper.readValue("{\"payYuan\":1.5}", WalletCustomRechargeDTO.class);

        assertThat(dto.getPayYuan()).isEqualByComparingTo("1.5");
    }
}
