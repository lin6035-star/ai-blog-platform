package com.hailin.blogsystem;

import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.entity.vo.PaymentInitiateVO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 充值订单：下单 / 发起支付 / 回调入账 / 幂等 / 越权。
 *
 * 设计稿：docs/redis/充值订单真实化设计.md
 *
 * <p>改造后 {@code pay} 不再直接加余额：入账只能由「发起支付 → 渠道回调」驱动。
 * 测试统一走 {@link #completePayment(String)}，避免任何测试绕过回调路径。
 */
@SpringBootTest
class WalletRechargeTests {

    @Autowired
    private WalletRechargeService walletRechargeService;

    @Autowired
    private WalletService walletService;

    private final long userId = 9_200_000_000_000L + System.nanoTime() % 1_000_000_000L;
    private final long otherUserId = userId + 1;

    @BeforeEach
    void setUp() {
        UserContext.set(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 完整支付链路：发起支付拿凭证 → 模拟支付页确认 → 走回调内核入账 */
    private RechargeOrderVO completePayment(String orderNo) {
        PaymentInitiateVO initiate = walletRechargeService.initiatePayment(orderNo);
        return walletRechargeService.mockPay(orderNo, initiate.getPayToken());
    }

    // ---------- 下单 ----------

    @Test
    void listPackagesComesFromConfig() {
        List<WalletPackageVO> packages = walletRechargeService.listPackages();

        assertThat(packages).isNotEmpty();
        assertThat(packages).allSatisfy(pkg -> {
            assertThat(pkg.getCode()).isNotBlank();
            assertThat(pkg.getPayAmount()).isPositive();
            assertThat(pkg.getCreditAmount()).isPositive();
        });
    }

    @Test
    void createOrderSnapshotsAmountsFromPackageNotFromRequest() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);

        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        assertThat(order.getOrderNo()).startsWith("W");
        assertThat(order.getStatus()).isEqualTo("PENDING");
        // 金额来自套餐快照——请求体里根本没有金额字段
        assertThat(order.getPayAmount()).isEqualTo(pkg.getPayAmount());
        assertThat(order.getCreditAmount()).isEqualTo(pkg.getCreditAmount());
        // 订单闭环：下单必须带支付截止时间，否则超时关单任务没有判据
        assertThat(order.getExpireAt()).isNotNull();
    }

    @Test
    void createOrderRejectsUnknownPackage() {
        assertThatThrownBy(() -> walletRechargeService.createOrder("PACK_NOT_EXIST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("套餐不存在");
    }

    // ---------- 发起支付 ----------

    @Test
    void initiatePaymentCreatesPaymentButKeepsOrderPending() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());

        assertThat(payment.getPaymentNo()).startsWith("P");
        assertThat(payment.getPayToken()).isNotBlank();
        assertThat(payment.getOrderStatus()).isEqualTo("PENDING");
        // 关键不变量：发起支付**不能**改订单状态，入账只能由回调驱动
        assertThat(walletRechargeService.getOrder(order.getOrderNo()).getStatus()).isEqualTo("PENDING");
        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    @Test
    void initiatePaymentReusesCreatedPayment() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        PaymentInitiateVO first = walletRechargeService.initiatePayment(order.getOrderNo());
        PaymentInitiateVO second = walletRechargeService.initiatePayment(order.getOrderNo());

        // 重复点「支付」不应刷出一堆支付单——同一订单复用同一条 CREATED 支付单
        assertThat(second.getPaymentNo()).isEqualTo(first.getPaymentNo());
    }

    // ---------- 回调入账 ----------

    @Test
    void callbackCreditsWalletAndMarksOrderPaid() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        RechargeOrderVO paid = completePayment(order.getOrderNo());

        assertThat(paid.getStatus()).isEqualTo("PAID");
        assertThat(paid.getPaidAt()).isNotNull();
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(pkg.getCreditAmount());
    }

    /**
     * 幂等：重复确认支付只加一次钱。
     *
     * <p>第二次 {@code mockPay} 时订单已 PAID → 直接返回当前状态，
     * 不会再走回调内核；出错方向是「用户白拿额度」，必须锁死。
     */
    @Test
    void callbackIsIdempotentOnSecondCall() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());
        PaymentInitiateVO initiate = walletRechargeService.initiatePayment(order.getOrderNo());

        walletRechargeService.mockPay(order.getOrderNo(), initiate.getPayToken());
        RechargeOrderVO second = walletRechargeService.mockPay(order.getOrderNo(), initiate.getPayToken());

        assertThat(second.getStatus()).isEqualTo("PAID");
        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(pkg.getCreditAmount());
    }

    @Test
    void initiatePaymentRejectsOtherUsersOrder() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());

        UserContext.set(otherUserId);
        assertThatThrownBy(() -> walletRechargeService.initiatePayment(order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");

        // 越权请求不能造成任何余额变动
        UserContext.set(userId);
        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    @Test
    void mockPayRejectsOtherUsersOrder() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());
        PaymentInitiateVO initiate = walletRechargeService.initiatePayment(order.getOrderNo());

        UserContext.set(otherUserId);
        assertThatThrownBy(() -> walletRechargeService.mockPay(order.getOrderNo(), initiate.getPayToken()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");

        UserContext.set(userId);
        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    @Test
    void initiatePaymentRejectsMissingOrder() {
        assertThatThrownBy(() -> walletRechargeService.initiatePayment("W_NOT_EXIST"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("订单不存在");
    }

    @Test
    void initiatePaymentRejectsClosedOrder() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        RechargeOrderVO order = walletRechargeService.createOrder(pkg.getCode());
        walletRechargeService.lambdaUpdate()
                .eq(WalletRechargeOrder::getOrderNo, order.getOrderNo())
                .set(WalletRechargeOrder::getStatus, "CLOSED")
                .set(WalletRechargeOrder::getCloseReason, "USER_CANCELLED")
                .update();

        assertThatThrownBy(() -> walletRechargeService.initiatePayment(order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已关闭");

        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }
}
