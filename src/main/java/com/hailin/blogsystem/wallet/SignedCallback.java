package com.hailin.blogsystem.wallet;

/**
 * 渠道签名后的回调报文。
 *
 * <p>{@code payload} 是渠道侧生成的原文，{@code signature} 是对原文的 HMAC。
 * 服务端验签必须对**原文**做，不能用反序列化后再拼出来的字符串——
 * 字段顺序、空白、编码任何一处不同都会让合法回调被误拒。
 *
 * @param payload   回调报文原文（JSON）
 * @param signature HMAC-SHA256 十六进制签名
 */
public record SignedCallback(String payload, String signature) {
}
