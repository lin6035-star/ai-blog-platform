package com.hailin.blogsystem.entity.dto;

import lombok.Data;

/**
 * 模拟支付页确认付款。
 *
 * <p>只有 {@code payToken} 一个字段：金额、订单、渠道全部由服务端按支付单决定——
 * 前端能传金额的支付接口就是一个自助发钱接口。
 */
@Data
public class MockPayDTO {
    private String payToken;
}
