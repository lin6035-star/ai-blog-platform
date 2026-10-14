package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钱包配置。
 *
 * 设计稿：docs/redis/钱包与秒杀计划.md §4.5
 *
 * <p><b>套餐为什么用配置而不是建表</b>：当前不需要运营后台，改价直接改配置。
 * 升级触发条件——套餐需要频繁变更 / 需要分用户分渠道定价时，再升级成表 + 管理接口。
 */
@Data
@ConfigurationProperties(prefix = "blog.wallet")
public class BlogWalletProperties {

    /**
     * 注册赠送的初始额度（credit）。0 = 不送。
     *
     * <p>为什么需要它：计费开关默认开启，不送额度的话新账号注册完**立刻**就会
     * 「额度不足」——「注册就能用」和「计费上线」本来是冲突的，这一笔是解法。
     *
     * <p>存量账号不走这里（他们在计费上线前就注册了），自己去钱包页充值补上即可，
     * 所以不需要为回填写 SQL。
     */
    private long initialCredit = 0;

    /**
     * 充值套餐：key = packageCode，value = 套餐内容。
     *
     * <p>⚠️ 前端只传 packageCode，应付金额与到账额度都由后端按这里决定。
     * 让前端传金额，等于把「自助发钱」的接口开出去。
     */
    private Map<String, Package> packages = new LinkedHashMap<>();

    @Data
    public static class Package {
        /** 应付金额（分，真实货币口径；虚拟支付下仅用于展示） */
        private long payAmount;
        /** 到账额度（credit） */
        private long creditAmount;
    }

    /**
     * 自定义金额充值：金额由用户填（整数元）。
     *
     * <p><b>与 {@link Package} 的关键差别是金额来自请求体</b>——所以这个入口必须自己长出一道
     * 「金额合法性」的闸门（{@code WalletRechargeServiceImpl#requireWholeYuan}），
     * 套餐那条路不需要，因为套餐金额本来就由后端从配置里取。
     *
     * <p><b>但到账额度仍由后端算</b>：{@code 元 × creditPerYuan}。用户能决定「充多少钱」，
     * 决定不了「到账多少 credit」——那条线才是「自助发钱」的实质，
     * 详见 docs/redis/钱包与秒杀计划.md §4.6。
     */
    private Custom custom = new Custom();

    @Data
    public static class Custom {
        /** 单笔下限（元，整数） */
        private long minYuan = 1;
        /** 单笔上限（元，整数） */
        private long maxYuan = 1000;
        /**
         * 兑换比例：1 元 = 多少 credit。
         *
         * <p>必须**低于所有套餐**（PACK_30 是 1100、PACK_98 是 1204）——产品口径是
         * 「自定义卖灵活度、套餐卖折扣」。追平了就等于套餐卖不动，但那是产品决策不是错误，
         * 所以只在启动时 WARN 不 fail-fast。
         */
        private long creditPerYuan = 1000;
    }

    /**
     * 账单的**展示**保留期（天）：用户只看得到最近这么多天的账单，默认 7 天。
     *
     * <p><b>它只影响"给用户看的账单"，账本一行都不删。</b>
     * {@code wallet_transaction} 是按 {@code balance_seq} **连续编号**的账本，
     * 逐笔回放对账（{@code WalletTransactionMapper#selectLedgerAnomalies} 的
     * {@code LAG} 检查"序号连号 + 余额连续"）依赖这个连续性——
     * 删掉任何一段，巡检都会报"漏流水"假警，把真正的账不平淹没掉。
     *
     * <p>所以"只看最近 N 天"这件事只在**展示层**解决：改这个配置就能放开，
     * 不需要动数据。真要减负应该走归档或分区，那是另一回事。
     */
    private long billRetentionDays = 7;
}
