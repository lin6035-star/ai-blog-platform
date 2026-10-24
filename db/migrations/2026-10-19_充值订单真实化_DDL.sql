-- 2026-10-19 充值订单真实化：DDL 迁移（已有库升级用）
--
-- ⚠️ 执行前必读：wallet_payment 的 COLLATE 必须与「目标库已有的 wallet_recharge_order」一致，
--    否则对账任务的 JOIN 会报 Illegal mix of collations。
--
--    先查：
--      SHOW CREATE TABLE wallet_recharge_order;   -- 看结尾的 COLLATE=
--
--    两种已知情况：
--      · 本地开发库 / 照 2026-10-19 版 init.sql 新建的库 → utf8mb4_unicode_ci
--      · 2026-10-18 用旧 init.sql 建的线上库（表带 DEFAULT CHARSET=utf8mb4 但没写 COLLATE）
--        → 很可能是 MySQL 8 的默认 utf8mb4_0900_ai_ci。此时把下面 wallet_payment 的
--        COLLATE 改成 utf8mb4_0900_ai_ci，或者建完再执行：
--        ALTER TABLE wallet_payment CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--
-- 本脚本的 DDL 与 db/init.sql 中 wallet_recharge_order / wallet_payment 的定义一致。

ALTER TABLE wallet_recharge_order
  ADD COLUMN expire_at DATETIME NULL COMMENT '支付截止时间；空 = 历史订单不过期',
  ADD COLUMN closed_at DATETIME NULL COMMENT '关闭时间',
  ADD COLUMN close_reason VARCHAR(32) NULL COMMENT 'TIMEOUT / USER_CANCELLED',
  ADD KEY idx_order_status_expire (status, expire_at);

CREATE TABLE IF NOT EXISTS wallet_payment (
  id                BIGINT       NOT NULL PRIMARY KEY COMMENT '主键（雪花）',
  payment_no        VARCHAR(64)  NOT NULL COMMENT '商户支付单号（真实场景即 out_trade_no）',
  order_no          VARCHAR(64)  NOT NULL COMMENT '对应 wallet_recharge_order.order_no',
  user_id           BIGINT       NOT NULL COMMENT '下单用户（冗余：回调无登录态，靠它定位）',
  channel           VARCHAR(32)  NOT NULL COMMENT '支付渠道：MOCK /（将来 WECHAT / ALIPAY）',
  channel_trade_no  VARCHAR(64)  NULL COMMENT '渠道交易号（回调回填）',
  pay_amount        BIGINT       NOT NULL COMMENT '应付金额（分），下单时快照',
  status            VARCHAR(16)  NOT NULL COMMENT 'CREATED / SUCCESS / FAILED / CLOSED',
  pay_token         VARCHAR(128) NULL COMMENT '模拟支付页凭证（真实场景是 prepay_id）',
  paid_at           DATETIME     NULL COMMENT '渠道支付成功时间',
  fail_reason       VARCHAR(255) NULL COMMENT '失败原因',
  callback_payload  TEXT         NULL COMMENT '原始回调报文（验签通过后落库）',
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_payment_no (payment_no),
  UNIQUE KEY uk_channel_trade_no (channel, channel_trade_no),
  KEY idx_payment_order_no (order_no),
  KEY idx_payment_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='充值支付单（渠道凭证）';
