package com.hailin.blogsystem.constants;

public class BlogConstants {

    private BlogConstants() {

    }

    /* 用户状态 */
    public static final class UserStatus{
        public static final int DISABLED = 0;  // 禁用
        public static final int NORMAL = 1;    // 正常

        private UserStatus() {

        }
    }

    /* 博文状态 */
    public static final class ArticlesStatus{
        public static final int DRAFT = 0;      // 草稿
        public static final int PUBLISHED = 1;  // 已发布
        public static final int HIDDEN = 2;     // 隐藏

        private ArticlesStatus() {

        }
    }

    /* 文章点赞状态 */
    public static final class ArticleLikes{
        public static final int LIKED = 1;
        public static final int UNLIKED = 0;
    }

    /* 错误码 */
    public static final class ErrorCode {
        public static final int SUCCESS = 0;
        public static final int BAD_REQUEST = 40001;    // 参数校验失败
        public static final int UNAUTHORIZED = 40100;   // 未登录
        public static final int LOGIN_FAILED = 40101;   // 用户名或密码错误
        /**
         * 验证码未通过 / 凭证已失效。
         *
         * <p>单开一个码而不是复用 40001：前端要靠它区分「该重新弹验证码」和
         * 「参数填错了」——两者的界面动作完全不同（前者重开滑块，后者标红输入框）。
         * 靠 message 字符串判断会随文案改动而静默失效。
         */
        public static final int CAPTCHA_INVALID = 40002;
        /**
         * 重置链接无效 / 已过期 / 已被用过。
         *
         * <p>单开一个码是为了让前端能给出**准确的下一步动作**（"重新申请一次"），
         * 而不是笼统的"参数错误"——用户看到后者只会反复重试同一个死链接。
         */
        public static final int PASSWORD_RESET_INVALID = 40003;
        public static final int FORBIDDEN = 40300;      // 无权限
        public static final int NOT_FOUND = 40400;      // 资源不存在
        public static final int SERVER_ERROR = 50000;   // 服务器内部错误
        public static final int CONFLICT = 40900;  // 资源正在处理中
        public static final int RATE_LIMITED = 42900;   // 请求过于频繁
        public static final int INSUFFICIENT_BALANCE = 40200;  // 额度不足（HTTP 402）
        public static final int AI_TASK_USER_LIMIT = 42901;   // 该用户 AI 长任务并发超限（HTTP 429）
        public static final int AI_TASK_POOL_FULL = 50300;    // AI 长任务全局容量耗尽（HTTP 503）
        /**
         * 邮件服务不可用（未配置 / 发不出去）。
         *
         * <p><b>为什么它可以从"防枚举"里豁免</b>：防枚举要保护的是
         * 「<b>某个邮箱</b>是否注册过」这类**用户级**信息；而"邮件服务没配"是
         * **全局状态**，跟任何用户都无关——把它说出来不会泄露任何信息，
         * 却能让配置错误在第一次点击时就暴露，而不是界面一片绿、实际什么都没发生。
         */
        public static final int MAIL_UNAVAILABLE = 50301;

        private ErrorCode() {

        }
    }

    /* 钱包 */
    public static final class Wallet {
        /**
         * 余额下界，<b>必须与 db/init.sql 的 chk_wallet_balance_floor 约束保持一致</b>。
         *
         * <p>余额允许为负（预扣门槛是 balance &gt; 0 而非 balance &gt;= reserved，
         * 透支一轮是正常业务状态），留一个宽松下界是为了让「符号写反 / 扣减跑飞」
         * 这类 bug 仍然被 DB 拦住——放弃非负约束不等于放弃这道防线。
         *
         * <p>启动时由 {@link com.hailin.blogsystem.component.BillingReserveLimitValidator}
         * 校验「最大预扣 × 安全系数 &lt; |下界|」：预扣调大后触碰它时，
         * 报错信息（约束冲突）完全指不到根因。
         */
        public static final long BALANCE_FLOOR = -1000000L;

        /**
         * 自定义金额订单的 package_code。
         *
         * <p>该列是 {@code VARCHAR(32) NOT NULL}，自定义订单没有套餐码但必须填一个。
         * 刻意不用「CUSTOM_500」这种带金额的形式——金额已经在 {@code pay_amount} 列上，
         * 再编码一次就多一个会漂移的真源。
         */
        public static final String CUSTOM_PACKAGE_CODE = "CUSTOM";

        /* ---------- 充值订单状态（真实支付模型：回调驱动入账） ---------- */
        /** 待支付 */
        public static final String ORDER_PENDING = "PENDING";
        /** 已支付（只能由「支付单 SUCCESS + 金额校验通过」驱动） */
        public static final String ORDER_PAID = "PAID";
        /** 已关闭（超时 / 用户取消） */
        public static final String ORDER_CLOSED = "CLOSED";

        /* 订单关闭原因 */
        public static final String CLOSE_TIMEOUT = "TIMEOUT";
        public static final String CLOSE_USER_CANCELLED = "USER_CANCELLED";

        /* ---------- 支付单状态（渠道凭证） ---------- */
        public static final String PAYMENT_CREATED = "CREATED";
        public static final String PAYMENT_SUCCESS = "SUCCESS";
        public static final String PAYMENT_FAILED = "FAILED";
        public static final String PAYMENT_CLOSED = "CLOSED";

        /** 支付渠道：虚拟支付（真实接入时新增 WECHAT / ALIPAY） */
        public static final String CHANNEL_MOCK = "MOCK";

        /** 充值入账的流水类型 / 业务类型（与 wallet_transaction 的列取值一致） */
        public static final String TX_RECHARGE = "RECHARGE";
        public static final String BIZ_RECHARGE_ORDER = "RECHARGE_ORDER";

        private Wallet() {
        }
    }

    /* 秒杀 */
    public static final class Seckill {

        /* 活动状态 */
        public static final String ACTIVITY_DRAFT = "DRAFT";
        public static final String ACTIVITY_ACTIVE = "ACTIVE";
        public static final String ACTIVITY_ENDED = "ENDED";

        /* 订单状态 */
        public static final String ORDER_GRANTED = "GRANTED";

        /** 业务失败（DB 条件更新影响 0 行 = 售罄）。终态，重投不会有不同结果，必须 ACK */
        public static final String ORDER_FAILED = "FAILED";

        /** 技术失败重试超限的死信终态——没有它，「重试中」和「消费者崩了」在对账眼里是同一个样子 */
        public static final String ORDER_FAILED_RETRY = "FAILED_RETRY";

        private Seckill() {
        }
    }
}
