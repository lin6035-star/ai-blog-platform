-- 2026-10-19 充值订单真实化：历史 PAID 订单补录支付单
--
-- 背景：改造前 pay 直接入账，没有 wallet_payment 记录。部署新代码后，
-- 对账任务会把「订单 PAID 但无成功支付单」当成脏单报警（本地实测 3 条）。
-- 旧订单的渠道凭证不可能再拿到，这里补一条标记为 LEGACY_MIGRATION 的支付单，
-- 让「订单 ↔ 支付单」重新对齐；对账自查时能看出它是迁移补录、不是真实渠道单。
--
-- ⚠️ 幂等：WHERE NOT EXISTS 保证重复执行不会补第二条。
-- ⚠️ 只在「本地开发库 / 线上首次升级」时执行一次；执行前先跑下面的查询确认数量：
--    SELECT COUNT(*) FROM wallet_recharge_order o
--    WHERE o.status = 'PAID'
--      AND NOT EXISTS (SELECT 1 FROM wallet_payment p WHERE p.order_no = o.order_no);

INSERT INTO wallet_payment
  (id, payment_no, order_no, user_id, channel, channel_trade_no, pay_amount, status,
   pay_token, paid_at, fail_reason, callback_payload, created_at, updated_at)
SELECT
  (SELECT COALESCE(MAX(id), 0) FROM wallet_payment) + ROW_NUMBER() OVER (ORDER BY o.id),
  CONCAT('LEGACY-', o.order_no),
  o.order_no,
  o.user_id,
  'MOCK',
  CONCAT('LEGACY-', o.order_no),
  o.pay_amount,
  'SUCCESS',
  NULL,
  o.paid_at,
  NULL,
  '{"source":"LEGACY_MIGRATION","reason":"旧代码直接入账，无渠道支付单"}',
  o.created_at,
  o.created_at
FROM wallet_recharge_order o
WHERE o.status = 'PAID'
  AND NOT EXISTS (SELECT 1 FROM wallet_payment p WHERE p.order_no = o.order_no);
