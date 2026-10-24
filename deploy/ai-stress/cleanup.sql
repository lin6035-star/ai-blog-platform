-- 压测数据清理（2026-09-19 L1 压测）
--
-- 清理对象两类：
--   ① jmeter_test_* 测试账号（秒杀压测遗留的 200 个）
--   ② "幽灵用户"：userId 不在 users 表里的记录 —— 压测脚本早期版本踩了 JS 数字精度坑
--      （雪花 ID 19 位超过 Number.MAX_SAFE_INTEGER，末位被抹成 000），签出来的 token
--      挂在不存在的用户上，而后端不校验 userId 存在性，于是照单全收建了数据。
--
-- ⚠️ 删除原则：**按用户整体删，不能删一半**。
--   `wallet_transaction` 是只增不改的账本，`WalletReconcileTask` 校验它自身的自洽性
--   （balance_seq 连续、每个用户首笔必须是 1）。只删中间几笔会留下断档 → 对账直接报 error。
--   把某个用户的所有流水整段删掉，该用户就从对账视野里消失，不变量不受影响。
--
-- 删除顺序按依赖从叶到根：step 日志 → 消息 → run → session → 钱包。
-- 执行前建议先跑一遍「统计版」确认行数（见文件末尾注释）。

-- ---------- ① 依赖表（先删）----------
DELETE FROM ai_agent_steps
WHERE agent_run_id IN (
    SELECT id FROM ai_agent_runs
    WHERE user_id NOT IN (SELECT id FROM users)
       OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%')
);

DELETE FROM ai_workflow_step_logs
WHERE workflow_run_id IN (
    SELECT id FROM ai_workflow_runs
    WHERE user_id NOT IN (SELECT id FROM users)
       OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%')
);

DELETE FROM ai_messages
WHERE session_id IN (
    SELECT id FROM ai_sessions
    WHERE user_id NOT IN (SELECT id FROM users)
       OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%')
);

DELETE FROM ai_conversation_summaries
WHERE user_id NOT IN (SELECT id FROM users)
   OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

DELETE FROM ai_episodic_memories
WHERE user_id NOT IN (SELECT id FROM users)
   OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

DELETE FROM ai_user_memory_candidates
WHERE user_id NOT IN (SELECT id FROM users)
   OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

-- ---------- ② 主表 ----------
DELETE FROM ai_workflow_runs
WHERE user_id NOT IN (SELECT id FROM users)
   OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

DELETE FROM ai_agent_runs
WHERE user_id NOT IN (SELECT id FROM users)
   OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

DELETE FROM ai_sessions
WHERE user_id NOT IN (SELECT id FROM users)
   OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

DELETE FROM ai_user_memories
WHERE user_id NOT IN (SELECT id FROM users)
   OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

-- ---------- ③ 钱包（压测走 chat/stream 入口，计费挂在聊天上，确实扣了钱）----------
DELETE FROM ai_billing_order
WHERE user_id NOT IN (SELECT id FROM users)
   OR user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

-- 账本：必须整用户删除（见文件头的说明）
DELETE FROM wallet_transaction
WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

-- 钱包本体：与流水一起删，避免"有余额没流水"的半截状态。
-- 这些账号如果以后还要用于压测，钱包会在首次使用时重新初始化。
DELETE FROM user_wallet
WHERE user_id IN (SELECT id FROM users WHERE username LIKE 'jmeter_test_%');

-- ---------- 清理后自检 ----------
-- 对账不变量：每个用户的首笔 balance_seq 必须是 1，且序列连续。
-- 正常情况下下面两条都该返回空集。
-- SELECT user_id FROM wallet_transaction GROUP BY user_id HAVING MIN(balance_seq) <> 1 LIMIT 20;
-- SELECT user_id, balance_seq, prev_seq FROM (
--     SELECT user_id, balance_seq, LAG(balance_seq) OVER (PARTITION BY user_id ORDER BY balance_seq) AS prev_seq
--     FROM wallet_transaction
-- ) t WHERE prev_seq IS NOT NULL AND prev_seq <> balance_seq - 1 LIMIT 20;
