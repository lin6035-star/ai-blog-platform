package com.hailin.blogsystem.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hailin.blogsystem.config.BlogWalletProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.WalletPayment;
import com.hailin.blogsystem.utils.OrderNoGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 虚拟支付渠道（Mock）。
 *
 * <p><b>它扮演的是「渠道方」</b>：生成预支付凭证、产生渠道交易号、对回调报文签名、响应查单。
 * 服务端的回调处理内核（验签 / 幂等 / 金额校验 / 入账）用的仍是真实链路——
 * 这样本地就能演练重复回调、金额篡改、乱序、回调丢失这些真实问题。
 *
 * <p>⚠️ 签名密钥只存在于服务端配置，不会通过任何接口下发给浏览器。
 * 前端的「模拟支付」按钮只是触发服务端以渠道身份构造一条签名回调，
 * 而不是让浏览器自己签名——否则验签这道门等于没有。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MockPaymentChannel implements PaymentChannel {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final BlogWalletProperties walletProperties;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return BlogConstants.Wallet.CHANNEL_MOCK;
    }

    @Override
    public String initiate(WalletPayment payment) {
        // 真实渠道这里返回 prepay_id（+ 小程序/JSAPI 支付参数）。
        // payToken 的用途是让「模拟支付页」能证明自己拿到的是一笔真实存在的支付单。
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public ChannelPaymentStatus query(WalletPayment payment) {
        // 虚拟渠道的「渠道侧」就是本系统，所以状态直接映射本地支付单：
        // 只有回调真正落库后才会是 SUCCESS——查单在这里不可能比本地更新，
        // 但真实渠道恰恰相反（渠道可能已扣款、回调还没到），补单的价值就在那里。
        return switch (payment.getStatus()) {
            case BlogConstants.Wallet.PAYMENT_SUCCESS -> ChannelPaymentStatus.SUCCESS;
            case BlogConstants.Wallet.PAYMENT_FAILED -> ChannelPaymentStatus.FAILED;
            default -> ChannelPaymentStatus.PENDING;
        };
    }

    @Override
    public SignedCallback simulatePaidCallback(WalletPayment payment, String payToken) {
        if (payToken == null || payment.getPayToken() == null
                || !MessageDigest.isEqual(
                        payToken.getBytes(StandardCharsets.UTF_8),
                        payment.getPayToken().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("支付凭证无效");
        }
        if (!BlogConstants.Wallet.PAYMENT_CREATED.equals(payment.getStatus())) {
            // 支付单已终态：模拟渠道侧不允许对同一笔单再次付款。
            // 真实渠道这里会报「订单已支付/已关闭」。
            throw new IllegalArgumentException("支付单已处理，不能重复支付");
        }

        String channelTradeNo = OrderNoGenerator.next("M");
        long timestamp = System.currentTimeMillis() / 1000;
        String payload = buildPayload(payment, channelTradeNo, timestamp);
        log.info("[PAYMENT-MOCK] 渠道侧确认收款 paymentNo={} channelTradeNo={} amount={}",
                payment.getPaymentNo(), channelTradeNo, payment.getPayAmount());
        return new SignedCallback(payload, sign(payload));
    }

    @Override
    public boolean verifyCallback(String payload, String signature) {
        if (payload == null || signature == null) {
            return false;
        }
        String expected = sign(payload);
        // 常量时间比较：普通 equals 会在第一个不同字符处提前返回，理论上可被计时侧信道利用
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PaymentCallback parseCallback(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return new PaymentCallback(
                    requiredText(node, "paymentNo"),
                    requiredText(node, "orderNo"),
                    requiredText(node, "channelTradeNo"),
                    requiredLong(node, "amount"),
                    requiredLong(node, "timestamp"),
                    payload);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("回调报文格式不合法", e);
        }
    }

    private String buildPayload(WalletPayment payment, String channelTradeNo, long timestamp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paymentNo", payment.getPaymentNo());
        body.put("orderNo", payment.getOrderNo());
        body.put("channelTradeNo", channelTradeNo);
        body.put("amount", payment.getPayAmount());
        body.put("timestamp", timestamp);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("构造模拟回调报文失败", e);
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    walletProperties.getPayment().getMockSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("回调签名失败", e);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException("回调报文缺少字段：" + field);
        }
        return value.asText();
    }

    private long requiredLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToLong()) {
            throw new IllegalArgumentException("回调报文字段不合法：" + field);
        }
        return value.asLong();
    }
}
