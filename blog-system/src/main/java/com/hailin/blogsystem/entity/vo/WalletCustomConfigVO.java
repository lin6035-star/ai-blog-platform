package com.hailin.blogsystem.entity.vo;

import lombok.Data;

/**
 * 自定义充值的配置视图（来自 blog.wallet.custom）。
 *
 * <p>为什么要专门给前端一个接口：金额上下限和兑换比例必须与后端**同源**。
 * 前端硬编码 1~1000 的话，改了 yml 前端不同步——提示文案说「1 ~ 1000 元」，
 * 实际后端已经改成 1 ~ 500，用户按提示填了 800 被拒，两边都显得没道理。
 *
 * <p>注意这里**没有金额**，也没有任何可写的字段：它是只读的配置快照，
 * 真正的校验在下单时由后端做（前端拿它只是为了提前提示）。
 */
@Data
public class WalletCustomConfigVO {

    /** 单笔下限（元，整数） */
    private Long minYuan;

    /** 单笔上限（元，整数） */
    private Long maxYuan;

    /** 1 元 = 多少 credit */
    private Long creditPerYuan;
}
