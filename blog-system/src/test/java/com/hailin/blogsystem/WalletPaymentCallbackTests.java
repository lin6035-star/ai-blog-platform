package com.hailin.blogsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.entity.WalletRechargeOrder;
import com.hailin.blogsystem.entity.vo.PaymentInitiateVO;
import com.hailin.blogsystem.entity.vo.RechargeOrderVO;
import com.hailin.blogsystem.entity.vo.WalletPackageVO;
import com.hailin.blogsystem.exception.BusinessException;
import com.hailin.blogsystem.mapper.WalletPaymentMapper;
import com.hailin.blogsystem.service.WalletRechargeNotifyService;
import com.hailin.blogsystem.service.WalletRechargeService;
import com.hailin.blogsystem.service.WalletService;
import com.hailin.blogsystem.utils.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 充值回调安全 + 订单闭环（超时关单 / 取消 / 关单后到账）。
 *
 * 设计稿：docs/redis/充值订单真实化设计.md §6.3 - §6.6
 *
 * <p>这些用例锁的是「钱不会被错误地加」的方向：伪造回调、金额篡改、重放、重复投递、
 * 关单后到账，任何一条写漏都是真实资损。
 */
@SpringBootTest
class WalletPaymentCallbackTests {

    @Autowired
    private WalletRechargeService walletRechargeService;

    @Autowired
    private WalletRechargeNotifyService walletRechargeNotifyService;

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletPaymentMapper walletPaymentMapper;

    @Autowired
    private BlogWalletProperties walletProperties;

    @Autowired
    private ObjectMapper objectMapper;

    private final long userId = 9_400_000_000_000L + System.nanoTime() % 1_000_000_000L;
    private final long otherUserId = userId + 1;

    @BeforeEach
    void setUp() {
        UserContext.set(userId);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---------- 回调安全 ----------

    @Test
    void callbackRejectsInvalidSignature() throws Exception {
        RechargeOrderVO order = createPackageOrder();
        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());
        String payload = buildCallbackPayload(
                payment.getPaymentNo(), order.getOrderNo(), "M-TEST-1", order.getPayAmount());

        assertThatThrownBy(() -> walletRechargeNotifyService.handleCallback("MOCK", payload, "deadbeef"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("验签失败");

        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    @Test
    void callbackRejectsExpiredTimestamp() throws Exception {
        RechargeOrderVO order = createPackageOrder();
        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());
        // 时间戳往前拨一天：签名本身是合法的，但重放过期
        String payload = buildCallbackPayload(
                payment.getPaymentNo(), order.getOrderNo(), "M-TEST-2",
                order.getPayAmount(), System.currentTimeMillis() / 1000 - 86_400);

        assertThatThrownBy(() -> walletRechargeNotifyService.handleCallback(
                "MOCK", payload, hmac(payload)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("回调已过期");

        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    /**
     * 金额篡改：签名合法（攻击者拿不到密钥就伪造不出来，这里模拟「渠道报文被改但签名也更新」），
     * 但金额与支付单快照不一致 → 必须拒绝入账。
     *
     * <p>这是「金额只信服务端快照」的锁。
     */
    @Test
    void callbackRejectsAmountMismatch() throws Exception {
        RechargeOrderVO order = createPackageOrder();
        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());
        String payload = buildCallbackPayload(
                payment.getPaymentNo(), order.getOrderNo(), "M-TEST-3", order.getPayAmount() + 1);

        assertThatThrownBy(() -> walletRechargeNotifyService.handleCallback(
                "MOCK", payload, hmac(payload)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金额不匹配");

        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    @Test
    void callbackIsIdempotentOnDuplicateDelivery() throws Exception {
        RechargeOrderVO order = createPackageOrder();
        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());
        String payload = buildCallbackPayload(
                payment.getPaymentNo(), order.getOrderNo(), "M-TEST-4", order.getPayAmount());
        String signature = hmac(payload);

        walletRechargeNotifyService.handleCallback("MOCK", payload, signature);
        // 渠道重复投递：必须回成功（不抛），但不能再加一次钱
        assertThatCode(() -> walletRechargeNotifyService.handleCallback("MOCK", payload, signature))
                .doesNotThrowAnyException();

        assertThat(walletService.getWallet(userId).getBalance()).isEqualTo(order.getCreditAmount());
        assertThat(walletPaymentMapper.selectByPaymentNo(payment.getPaymentNo()).getStatus())
                .isEqualTo("SUCCESS");
    }

    @Test
    void callbackRejectsUnknownPayment() throws Exception {
        String payload = buildCallbackPayload("P_NOT_EXIST", "W_NOT_EXIST", "M-TEST-5", 100);

        assertThatThrownBy(() -> walletRechargeNotifyService.handleCallback(
                "MOCK", payload, hmac(payload)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("支付单不存在");
    }

    // ---------- 关单后到账 ----------

    /**
     * 渠道已收款、订单却已被超时关掉——真实支付里最贵的一类不一致。
     *
     * <p>期望：回调不抛（否则渠道会无限重试）、支付单记为 SUCCESS（渠道事实）、
     * 但**不入账**（订单已关闭），留给对账任务告警。
     */
    @Test
    void callbackAfterOrderClosedDoesNotCredit() throws Exception {
        RechargeOrderVO order = createPackageOrder();
        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());
        // 模拟关单任务把订单关掉（支付单仍是 CREATED，即回调还在路上）
        walletRechargeService.lambdaUpdate()
                .eq(WalletRechargeOrder::getOrderNo, order.getOrderNo())
                .set(WalletRechargeOrder::getStatus, "CLOSED")
                .set(WalletRechargeOrder::getCloseReason, "TIMEOUT")
                .update();

        String payload = buildCallbackPayload(
                payment.getPaymentNo(), order.getOrderNo(), "M-TEST-6", order.getPayAmount());
        assertThatCode(() -> walletRechargeNotifyService.handleCallback(
                "MOCK", payload, hmac(payload)))
                .doesNotThrowAnyException();

        // 渠道事实落库、但钱没入账
        assertThat(walletPaymentMapper.selectByPaymentNo(payment.getPaymentNo()).getStatus())
                .isEqualTo("SUCCESS");
        assertThat(walletService.getWallet(userId).getBalance()).isZero();
        assertThat(walletRechargeService.getOrder(order.getOrderNo()).getStatus()).isEqualTo("CLOSED");
    }

    // ---------- 超时关单 / 取消 ----------

    @Test
    void expiredOrderIsClosedLazilyAndPaymentClosedToo() {
        RechargeOrderVO order = createPackageOrder();
        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());
        assertThat(payment.getPaymentNo()).isNotBlank();

        // 把截止时间拨到过去，模拟「下单后一直没付」
        walletRechargeService.lambdaUpdate()
                .eq(WalletRechargeOrder::getOrderNo, order.getOrderNo())
                .set(WalletRechargeOrder::getExpireAt, LocalDateTime.now().minusMinutes(1))
                .update();

        RechargeOrderVO closed = walletRechargeService.getOrder(order.getOrderNo());

        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getCloseReason()).isEqualTo("TIMEOUT");
        assertThat(closed.getClosedAt()).isNotNull();
        // 联动：未支付的支付单也要关，否则补单任务会一直扫到它
        assertThat(walletPaymentMapper.selectByPaymentNo(payment.getPaymentNo()).getStatus())
                .isEqualTo("CLOSED");
    }

    @Test
    void cancelClosesPendingOrder() {
        RechargeOrderVO order = createPackageOrder();
        walletRechargeService.initiatePayment(order.getOrderNo());

        RechargeOrderVO cancelled = walletRechargeService.cancelOrder(order.getOrderNo());

        assertThat(cancelled.getStatus()).isEqualTo("CLOSED");
        assertThat(cancelled.getCloseReason()).isEqualTo("USER_CANCELLED");
    }

    @Test
    void cancelRejectsPaidOrder() {
        RechargeOrderVO order = createPackageOrder();
        PaymentInitiateVO payment = walletRechargeService.initiatePayment(order.getOrderNo());
        walletRechargeService.mockPay(order.getOrderNo(), payment.getPayToken());

        assertThatThrownBy(() -> walletRechargeService.cancelOrder(order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已支付");
    }

    // ---------- 订单查询 / 越权 ----------

    @Test
    void orderListReturnsOwnOrdersNewestFirst() {
        RechargeOrderVO first = createPackageOrder();
        RechargeOrderVO second = createPackageOrder();

        var page = walletRechargeService.listOrders(1, 10);

        assertThat(page.getList()).extracting(RechargeOrderVO::getOrderNo)
                .contains(first.getOrderNo(), second.getOrderNo());
        assertThat(page.getTotal()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void getOrderRejectsOtherUsersOrder() {
        RechargeOrderVO order = createPackageOrder();

        UserContext.set(otherUserId);
        assertThatThrownBy(() -> walletRechargeService.getOrder(order.getOrderNo()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权");

        UserContext.set(userId);
    }

    @Test
    void mockPayRejectsWrongPayToken() {
        RechargeOrderVO order = createPackageOrder();
        walletRechargeService.initiatePayment(order.getOrderNo());

        assertThatThrownBy(() -> walletRechargeService.mockPay(order.getOrderNo(), "wrong-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("支付凭证无效");

        assertThat(walletService.getWallet(userId).getBalance()).isZero();
    }

    // ---------- helper ----------

    private RechargeOrderVO createPackageOrder() {
        WalletPackageVO pkg = walletRechargeService.listPackages().get(0);
        return walletRechargeService.createOrder(pkg.getCode());
    }

    private String buildCallbackPayload(String paymentNo, String orderNo,
                                        String channelTradeNo, long amount) throws Exception {
        return buildCallbackPayload(paymentNo, orderNo, channelTradeNo, amount,
                System.currentTimeMillis() / 1000);
    }

    private String buildCallbackPayload(String paymentNo, String orderNo,
                                        String channelTradeNo, long amount, long timestamp) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentNo", paymentNo);
        body.put("orderNo", orderNo);
        body.put("channelTradeNo", channelTradeNo);
        body.put("amount", amount);
        body.put("timestamp", timestamp);
        return objectMapper.writeValueAsString(body);
    }

    /** 用服务端配置的密钥签名——测试扮演「渠道方」，这与 MockPaymentChannel 的签名逻辑等价 */
    private String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                walletProperties.getPayment().getMockSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
