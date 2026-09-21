package com.hailin.blogsystem.entity.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 自定义金额充值的请求体。
 *
 * <p>这是**唯一**接受金额的充值入口。{@link WalletRechargeDTO}（套餐）永远不会有金额字段
 * ——两条路各自一个 DTO，那条「请求体里根本没有金额字段」的契约就不会被放松
 * （见 docs/redis/钱包与秒杀计划.md §4.6）。
 *
 * <p><b>单位是元，字段名带 {@code Yuan} 是刻意的</b>：项目里 {@code payAmount} 一律是分，
 * 一个叫 {@code amount} 的字段迟早有人传分进来。到账额度仍由后端按
 * {@code blog.wallet.custom.credit-per-yuan} 算——用户能决定充多少钱，决定不了到账多少 credit。
 */
@Data
public class WalletCustomRechargeDTO {

    /**
     * 充值金额，单位：<b>元</b>（整数）。
     *
     * <p>⚠️ <b>类型必须是 {@code BigDecimal}，不能图省事用 {@code Long}</b>：
     * Jackson 默认开启 {@code DeserializationFeature.ACCEPT_FLOAT_AS_INT}，
     * {@code {"payYuan":1.5}} 会被<b>静默截断成 1</b>——不报错、无日志，
     * 用户要充 1.5 元结果拿到 1 元的额度。{@code BigDecimal} 把原始字面量完整带到
     * service，由那里拒绝小数（{@code WalletRechargeServiceImpl#requireWholeYuan}）。
     *
     * <p>也刻意不在这里加 {@code @Min}/{@code @Max}：上下限来自配置
     * （{@code blog.wallet.custom.min-yuan}），而注解参数必须是编译期常量，两者天生冲突。
     */
    private BigDecimal payYuan;
}
