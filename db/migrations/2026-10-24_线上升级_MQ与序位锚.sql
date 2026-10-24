-- 2026-10-24 线上升级：本地 2026-10-19 ~ 10-23 未部署改动的 DDL
--
-- 目标：2026-10-18 用旧 init.sql 建的生产库 blog_system（已有数据，不能重跑 init.sql）。
-- 执行方式（在服务器 /opt/blog 下）：
--   docker exec -i blog-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" blog_system \
--     < /opt/blog/db/migrations/2026-10-24_线上升级_MQ与序位锚.sql
--
-- 执行前必须：
--   1) 备份：docker exec blog-mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction blog_system | gzip > /opt/blog/backup-before-2026-10-24.sql.gz
--   2) 记录当前 collation：SHOW CREATE TABLE wallet_recharge_order;
--      - 若它已是 utf8mb4_unicode_ci，第 2 步的 CONVERT 是空操作；
--      - 若是 utf8mb4_0900_ai_ci（旧库默认），本脚本会把它转成 unicode_ci，保证与新建的 wallet_payment 一致。
--   3) 脚本对「列是否存在」做了判断，可重复执行；CREATE TABLE 均为 IF NOT EXISTS。
--
-- 说明：本脚本只建「新表 + 新列」，不改历史业务数据（CANCELLED→CLOSED 除外）。

SET @db := DATABASE();

-- ============================================================
-- 1) ai_sessions.last_rag_articles_json（2026-10-19 序位指代锚）
-- ============================================================
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_sessions' AND COLUMN_NAME = 'last_rag_articles_json');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE ai_sessions ADD COLUMN last_rag_articles_json TEXT NULL COMMENT ''会话最近一次文章检索的有序候选（JSON：articleId + title，序位指代用）''',
  'SELECT ''ai_sessions.last_rag_articles_json 已存在''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 2) wallet_recharge_order 扩列 + 索引 + 状态收敛（2026-10-19 充值订单真实化）
-- ============================================================
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_recharge_order' AND COLUMN_NAME = 'expire_at');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE wallet_recharge_order ADD COLUMN expire_at DATETIME NULL COMMENT ''支付截止时间；空 = 历史订单不过期''',
  'SELECT ''wallet_recharge_order.expire_at 已存在''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_recharge_order' AND COLUMN_NAME = 'closed_at');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE wallet_recharge_order ADD COLUMN closed_at DATETIME NULL COMMENT ''关闭时间''',
  'SELECT ''wallet_recharge_order.closed_at 已存在''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_recharge_order' AND COLUMN_NAME = 'close_reason');
SET @ddl := IF(@has_col = 0,
  'ALTER TABLE wallet_recharge_order ADD COLUMN close_reason VARCHAR(32) NULL COMMENT ''TIMEOUT / USER_CANCELLED''',
  'SELECT ''wallet_recharge_order.close_reason 已存在''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'wallet_recharge_order' AND INDEX_NAME = 'idx_order_status_expire');
SET @ddl := IF(@has_idx = 0,
  'ALTER TABLE wallet_recharge_order ADD KEY idx_order_status_expire (status, expire_at)',
  'SELECT ''idx_order_status_expire 已存在''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 历史状态收敛：新代码只认 PENDING / PAID / CLOSED
UPDATE wallet_recharge_order SET status = 'CLOSED' WHERE status = 'CANCELLED';

-- COLLATE 统一：wallet_payment 用 utf8mb4_unicode_ci，订单表必须与它一致，否则对账 JOIN 报
-- Illegal mix of collations。已经在 unicode_ci 的库上这条等同于重建一次空表，可安全重复执行。
ALTER TABLE wallet_recharge_order CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================
-- 3) wallet_payment 支付单表（一个订单可多次支付尝试）
-- ============================================================
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

-- ============================================================
-- 4) ai_memory_extraction_events（RocketMQ 记忆事件消费幂等表）
--    记忆 MQ 开关默认 false，建表只是提前对齐；应用开启后也会兜底 CREATE TABLE IF NOT EXISTS
-- ============================================================
CREATE TABLE IF NOT EXISTS ai_memory_extraction_events (
    event_id VARCHAR(128) NOT NULL COMMENT '事件 ID：memory:candidate:<userMessageId> / memory:episodic:<assistantMessageId> / memory:summary:<assistantMessageId>',
    event_type VARCHAR(32) NOT NULL COMMENT 'CANDIDATE / EPISODIC / SESSION_SUMMARY',
    status VARCHAR(32) NOT NULL COMMENT 'PROCESSING / SUCCEEDED',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 记忆事件消费幂等表（RocketMQ at-least-once）';

-- 旧库若已有旧版记忆幂等表（datetime 默认值风格），把列类型收敛到当前定义，保留已有数据。
SET @legacy := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = @db AND TABLE_NAME = 'ai_memory_extraction_events'
    AND COLUMN_NAME = 'created_at' AND DATA_TYPE = 'datetime');
SET @ddl := IF(@legacy > 0,
  'ALTER TABLE ai_memory_extraction_events MODIFY COLUMN created_at TIMESTAMP NOT NULL, MODIFY COLUMN updated_at TIMESTAMP NOT NULL',
  'SELECT ''ai_memory_extraction_events 已是当前结构''');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 5) article_index_outbox（文章索引同步 MQ 第二批）
--    开关默认 false，建表不产生运行时行为；以后开 AI_ARTICLE_INDEX_USE_MQ=true 时直接可用
-- ============================================================
CREATE TABLE IF NOT EXISTS article_index_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键（投递顺序）',
    article_id BIGINT UNSIGNED NOT NULL COMMENT '文章 ID（同一文章的事件按 id 顺序投递）',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT/FAILED',
    attempts INT NOT NULL DEFAULT 0 COMMENT 'relay 投递失败次数（消费失败由 MQ 重试，不计这里）',
    next_retry_at DATETIME NOT NULL COMMENT 'PENDING 状态下最早可投递时间',
    locked_until DATETIME DEFAULT NULL COMMENT 'SENDING 租约到期时间（崩溃恢复用）',
    sent_at DATETIME DEFAULT NULL COMMENT '成功投递到 RocketMQ 的时间',
    trace_id VARCHAR(64) DEFAULT NULL COMMENT '写入请求的 traceId（relay 线程没有请求上下文，必须落库）',
    last_error VARCHAR(500) DEFAULT NULL COMMENT '最近一次投递失败原因',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_outbox_due (status, next_retry_at),
    INDEX idx_outbox_article (article_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文章索引同步 outbox（事务内落事件，relay 投递）';