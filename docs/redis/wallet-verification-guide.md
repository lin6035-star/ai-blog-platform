# 钱包 + AI 计费 + 秒杀：验证指南

> 适用：2026-09-18（**批次 A 钱包本体 + 批次 B 聊天计费 + 批次 C 秒杀**，三批全部完成）
> 设计稿：`docs/redis/钱包与秒杀计划.md`

---

## 一、先执行 DDL

无 migration 框架，DDL 手动执行。新增的 6 张表在 `db/init.sql` 第 21–26 节，
秒杀活动的种子数据在第 27 节——时间窗从**执行 SQL 的时刻**算起，所以要部署时执行，不能写死日期。

```bash
mysql -u myRoot -p blog_system < db/init.sql
```

只**新增**表（`CREATE TABLE IF NOT EXISTS`），不改任何已有表，可以安全地在生产库执行。
执行完确认：

```sql
SHOW TABLES LIKE '%wallet%';    -- user_wallet / wallet_transaction / wallet_recharge_order
SHOW TABLES LIKE '%billing%';   -- ai_billing_order
SHOW TABLES LIKE 'seckill%';    -- seckill_activity / seckill_order（批次 C 才用，先建着）
```

## ⚠️ 开始测试前必读

**计费开关默认是开的**（`blog.ai.billing.enabled: true`）。所以 DDL 执行 + 后端重启之后，
**所有登录账号的 AI 聊天立刻开始计费**——余额为 0 的账号会被直接拒绝（HTTP 402 + 「额度不足」）。

所以：**新注册的账号自动带 10000 初始额度**（`blog.wallet.initial-credit`），注册完直接就能测。
老账号余额是 0，去钱包页点一下套餐即可。

> ⚠️ 上线到生产时同样要注意：**注册赠送只管新账号，存量用户的余额都是 0**。
> 计费一上线他们的 AI 就不能用了——要么让他们自己充值，要么你手动发一笔。

## 二、启动自检

后端起来后，日志里应当有这两行（没有就是配置没生效）：

```
[计费上限] 最大预扣 10000 credit × 2 = 20000 < 下界 1000000，校验通过（各 bizType：{CHAT=5000, AGENT=8000, WORKFLOW_ACTION=10000}）
[敏感配置] profile=default | ...
```

---

## 三、批次 A：钱包与充值

### A0 注册即送（新账号）

| | |
|---|---|
| **步骤** | 注册一个全新账号 → 进个人中心 `/me` |
| **预期** | 余额卡显示 **10,000**，不是红色的「额度不足」 |
| **再看** | 进钱包页：余额 10,000，账单里有一条「注册赠送 **+10,000** 余额 10,000」 |
| **验证点** | 只可能送一次——幂等键 `INITIAL:{userId}` + 流水唯一索引；重复调用撞索引**抛错**，而不是悄悄多送一笔 |
| **关掉它** | `blog.wallet.initial-credit` 改成 0 即可不送 |

### A1 零余额：三态里的「真欠费」

| | |
|---|---|
| **前置** | 把余额清零（**测试专用，绕过流水，仅用于造场景**）：<br>`UPDATE user_wallet SET balance = 0 WHERE user_id = <你的 userId>;` |
| **步骤** | 刷新个人中心 `/me` |
| **预期** | 余额卡「我的额度 / 0 / 额度不足，请充值」（红色） |
| **对照** | 同样是负余额，「预占中」是**暖黄**不是红色——见 B5 |
| **验证点** | 钱包行是**懒创建**的：读路径读不到行就按 0 返回、不写库，只有入账时才真正建行 |

### A2 充值闭环

| | |
|---|---|
| **步骤** | 钱包页 → 点任意套餐（如「10,000 额度 ¥10.00」）→ 弹「模拟支付」→ 确认支付 |
| **预期** | ① toast「充值成功，到账 10,000 额度」 ② 余额变成 10,000 ③ 账单出现一条「充值到账 **+10,000** 余额 10,000」 |
| **再验证** | 刷新页面余额仍在；个人中心的余额卡同步变成 10,000 |

### A3 重复支付只加一次钱（幂等）

这个用前端不好复现（每次点套餐都是**新订单**，本来就该各加一次）。
用 curl 验证**同一张订单**支付两次：

```bash
TOKEN='<登录后从 localStorage 的 blog_token 复制>'
BASE=http://localhost:8080/api/wallet

# 1) 下单，记下返回的 orderNo
curl -s -X POST $BASE/recharge/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"packageCode":"PACK_10"}'

# 2) 支付两次（第二次也应当成功返回，但余额只加一次）
curl -s -X POST $BASE/recharge/orders/<orderNo>/pay -H "Authorization: Bearer $TOKEN"
curl -s -X POST $BASE/recharge/orders/<orderNo>/pay -H "Authorization: Bearer $TOKEN"

# 3) 看余额与账单：只应有一条「充值到账」
curl -s $BASE -H "Authorization: Bearer $TOKEN"
curl -s "$BASE/bill?page=1&pageSize=10" -H "Authorization: Bearer $TOKEN"
```

### A4 越权：不能付别人的订单

```bash
# 用 A 账号下单，拿 orderNo；再用 B 账号的 TOKEN 支付
curl -s -X POST $BASE/recharge/orders/<orderNo>/pay -H "Authorization: Bearer $TOKEN_B"
```
**预期**：`{"code":40300,"message":"无权操作该订单"}`，且 A 的余额**没有任何变化**。
（`orderNo` 是可枚举的短字符串，不校验归属等于把别人的订单交给任何人「代付」。）

### A5 金额由后端决定

```bash
# 试图自己指定金额
curl -s -X POST $BASE/recharge/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"packageCode":"PACK_10","creditAmount":999999}'
```
**预期**：到账额度仍是套餐里的 10000——请求体里**根本没有金额字段**，多传的被忽略。

### A6 自定义金额充值（2026-09-20 新增）

```bash
# 配置（前端拿它渲染输入提示）
curl -s $BASE/recharge/custom-config -H "Authorization: Bearer $TOKEN"
# → {"code":0,"data":{"minYuan":1,"maxYuan":1000,"creditPerYuan":1000}}

# 充 5 元
curl -s -X POST $BASE/recharge/orders/custom \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payYuan":5}'
# → {"orderNo":"W...","packageCode":"CUSTOM","payAmount":500,"creditAmount":5000,"status":"PENDING"}

# 支付（和套餐走的是同一个接口，不是另开的一条）
curl -s -X POST $BASE/recharge/orders/<orderNo>/pay -H "Authorization: Bearer $TOKEN"
```

**预期**：余额 +5000；账单多一条「充值到账」（`bizType` 仍是 `RECHARGE_ORDER`，用户不需要区分）；
流水的 `remark` 是「自定义充值 5 元」——**不是**「充值套餐 CUSTOM」（那句话对账时没人看得懂）。

边界，全部应返回 `40001`：

```bash
for v in 0 -1 1001 5.5; do
  curl -s -X POST $BASE/recharge/orders/custom \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"payYuan\":$v}"
done
# → "充值金额需在 1 ~ 1000 元之间"（0 / -1 / 1001）、"充值金额必须是整数元"（5.5）
```

⚠️ **`5.5` 这条要特别看**：如果它返回的是「充值成功」而不是报错，说明
`WalletCustomRechargeDTO.payYuan` 的字段类型被从 `BigDecimal` 改回了 `Long`
——Jackson 会**静默把 1.5 截断成 1**，不报错、无日志，用户要充 1.5 元拿到 1 元的额度。
`WalletCustomRechargeTests#dtoKeepsFractionalInputInsteadOfTruncating` 锁的就是这个。

---

## 四、批次 B：AI 计费

### B1 充值后能消费

| | |
|---|---|
| **前置** | 余额 ≥ 10000（A2 充过就行） |
| **步骤** | 打开右下角 AI 助手 → 发一句「你好」 |
| **预期** | ① 助手头部出现余额 chip（如 `9984`） ② 回答正常流式返回 ③ 回答结束后余额 chip 变小 |
| **再看钱包页** | 账单多出**一条**「AI 消费 **-N**」——N 就是这次对话真实烧掉的 token 折算的额度 |

**为什么只有一条**：调用前按硬上限预扣、调用后按实际用量结算退回，这在**记账上是两条流水**，
但那是实现细节。账单按业务聚合，只给你看净额。

**想看预扣/结算的细节**（核对系统有没有多扣）：

```sql
SELECT order_no, reserved_credit, actual_credit, total_tokens, status, created_at, settled_at
FROM ai_billing_order WHERE user_id = <你的 userId> ORDER BY id DESC LIMIT 5;
```

`reserved_credit` 是预扣的、`actual_credit` 是真实扣的，两者之差就是退回的部分。

### B2 余额不足时的提示（重点）

先把余额清零（**测试专用，绕过流水，仅用于造场景**）：

```sql
UPDATE user_wallet SET balance = 0 WHERE user_id = <你的 userId>;
```

然后发一条消息。

| | |
|---|---|
| **预期** | toast 提示「**额度不足（当前 0），请先充值后再使用 AI 助手**」 |
| **绝对不该出现** | 「连接意外中断」 |

> 这一条是本次特意处理的坑：项目默认的业务异常返回 **HTTP 200 + JSON**，
> 而前端聊天的 SSE 请求在 200 时只认 `data:` 前缀的行，JSON 错误体会被静默忽略，
> 用户看到的就是「连接意外中断」，完全指不到根因。所以额度不足单独走 **HTTP 402**。

### B3 透支一轮

```sql
-- 把余额调成「不够一次预扣，但明显大于 0」
UPDATE user_wallet SET balance = 100 WHERE user_id = <你的 userId>;
```

再发一条消息。

| | |
|---|---|
| **预期** | 消息**能发出去**（预扣 5000 把余额扣成 −4900），回答正常返回 |
| **结束后** | 结算退回大部分，余额回到正数（真实消耗只有几十 credit） |
| **再发一条** | 仍然能用（余额已回正） |

**为什么允许扣成负数**：预扣取的是**硬上限**（最大输入 + maxTokens × 工具轮数），
比真实消耗大得多。若用刚性门槛，余额 100 的用户会被直接拒绝——而他的钱**够**一次真实对话。
放行后扣成负数，结算时按实际用量退回。负余额的幅度**恒小于造成它的那笔预扣**。

### B4 取消也退款

| | |
|---|---|
| **步骤** | 发一条会生成较长回答的问题，然后在回答**写到一半时刷新页面** |
| **预期** | 余额回到调用前的水位；**账单里不会多出条目**（净额为 0 的调用不显示） |

> V1 的政策是「取消也全额退」。代价是承认一个窗口：用户可以在模型已经产出 token 之后
> 取消、拿回预扣再发起下一次，累计成本无界。这是**产品取舍不是 bug**，升级路径见设计稿 §3。

### B5 三态区分（负余额不等于欠费）

| | |
|---|---|
| **步骤** | 余额小的时候发一条消息，趁回答还没结束时看助手头部的余额 chip |
| **预期** | chip 显示负数，但颜色是**暖黄**（预占中），**不是红色告警** |
| **回答结束后** | 余额回正 → chip 恢复常态 |
| **对照** | 余额 ≤ 0 且**没有**进行中的调用时，chip 才是**红色**（真的该充值了） |

> 只看余额数字会把「预扣进行中」误报成欠费——用户刚发一条消息就看到负余额，
> 会以为已经欠款了。所以 `GET /api/wallet` 同时返回 `pendingReserveCount`。

---

## 五、批次 C：秒杀

设计稿 §6。**抢购是「先排队、后到账」的两段式**——这个区别是这一组测试的重点。

### C0 预热

**活动不再由种子数据提供**——`db/init.sql` 里那条已经删掉了，现在由 `SeckillDailyTask`
每天自动创建（见 C7）。所以先看清楚库里有什么：

```sql
-- 8 位日期的那个就是每日自动开场的；end_at 最近的排最前
SELECT id, name, status, start_at, end_at FROM seckill_activity ORDER BY end_at LIMIT 5;
```

下面用 `<AID>` 代指「正在进行的那场的 id」。

服务启动时会自动预热全部 `ACTIVE` 活动，日志里应当有：

```
[SECKILL] 启动预热完成，共 1 个 ACTIVE 活动
[SECKILL] 预热活动 20260921（额度秒杀 · 9月21日）：剩余 50 / 总 50，已入账 0 人，TTL 604800 秒
```

> 启动时若不在 10:00–10:30 之间，**预热数就是 0**（那时活动还是 DRAFT）——
> 这是对的，不是坏了。等生命周期任务到点激活它即可（C7）。

预热建出来的三个 key（`stock` / `users` / `meta`）**都带 TTL**，这是部署模板
`maxmemory-policy: volatile-lru` 能工作的前提——它**只淘汰设了 TTL 的 key**，
没 TTL 的会一直占着内存，内存满了走 OOM 而不是淘汰。

`events` 和 `users` 是**抢购时才创建**的（`XADD` / `SADD`），所以它们的 TTL 由抢购脚本补，
固定 30 天兜底。想确认：

```bash
# 抢一次之后看 TTL，应当都是正数（-1 = 永不过期，就是没修好的状态）
redis-cli -n 1 TTL 'seckill:{<AID>}:stock'
redis-cli -n 1 TTL 'seckill:{<AID>}:events'
```

没有的话先确认 `blog.seckill.preheat-on-startup`（默认 true），再手动重建：

```bash
SECKILL=http://localhost:8080/api/seckill
TOKEN='<登录后从 localStorage 的 blog_token 复制>'

curl -s -X POST "$SECKILL/admin/preheat" -H "Authorization: Bearer $TOKEN"
```

> ⚠️ 这个接口**默认谁都不能调**——`blog.seckill.admin-user-ids` 是个空列表。
> 要先把自己的 userId 填进去再重启：
> `SELECT id FROM users WHERE username = '<你的用户名>';`

### C1 抢购闭环（重点）

**前置：得有一场正在进行中的活动。** 每日那场的窗口是 10:00–10:30，不在这个时间点就跑
不了抢购——把它挪到现在（等 5 秒让生命周期任务激活）：

```sql
UPDATE seckill_activity
SET start_at = NOW() - INTERVAL 1 MINUTE,
    end_at   = NOW() + INTERVAL 10 MINUTE,
    status   = 'DRAFT'                      -- 若已被结束，先退回 DRAFT 让任务重新激活
WHERE id = <AID>;
```

| | |
|---|---|
| **步骤** | 个人中心 → 我的额度 → 点「额度秒杀」→ 点「立即抢」 |
| **预期** | ① 按钮变**暖黄**「排队中…」 ② 1–2 秒内变**绿色**「已到账」 ③ toast「已到账 5,000 额度」 |
| **再看** | 返回钱包页：余额 +5,000，账单多一条「秒杀到账 **+5,000**」 |
| **验证点** | 「排队中」和「已到账」是**两个不同的状态**——前者只是占住了名额，后者才是钱进钱包 |

> 把这两个状态合成一个「抢到了」，就会出现「我明明抢到了，额度怎么没到」这种无解的问题。
> 后端返回 `QUEUED` 时**没有**说抢到，前端也不该替它说。

### C2 一人一单

| | |
|---|---|
| **步骤** | 用 curl 再抢一次同一场 |
| **预期** | `{"code":0,"data":{"status":"DUPLICATE","message":"你已经抢过这一场了"}}` |
| **验证点** | Redis 的 Set 挡住绝大多数重复点击，DB 的 `uk_user_activity` 兜住漏网的那一个 |

```bash
curl -s -X POST "$SECKILL/activities/1/grab" -H "Authorization: Bearer $TOKEN"
```

### C3 售罄（会看到「Redis 多发」的正常现象）

> ⚠️ **重来一次请新建活动，不要重置旧活动**（这条是踩过坑之后补的）。
>
> `UPDATE seckill_activity SET sold_count = 0` + `DELETE FROM seckill_order` 这种"重置"
> 只会清掉 DB 账本和订单，而 `wallet_transaction` 里的流水**不能删**（那是账本）。
> 于是同一批用户再次抢到时，钱包流水的幂等键 `SECKILL:{activityId}:{userId}`
> 会撞 `uk_user_idem`，表现为「入账失败 → 重试 5 次 → 转 FAILED_RETRY 死信」，
> 而堆栈里只能看到唯一键冲突，**完全指不到"重置没清干净"这个根因**。
>
> 新建一场活动则天然隔离，不碰任何历史数据。

```sql
INSERT INTO seckill_activity
    (id, name, total_stock, sold_count, credit_amount, start_at, end_at, status)
VALUES
    (2, '售罄测试', 1, 0, 5000, NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY), 'ACTIVE');
```

重启后端（或调预热接口），然后把请求里的 `activities/1` 换成 `activities/2`。

用两个账号分别抢（A 先、B 后）。

| | |
|---|---|
| **A** | 正常到账 |
| **B** | `QUEUED` → 几秒后变 `FAILED`「很遗憾，名额已经发完了」 |

**看库里**：

```sql
SELECT user_id, status, credit_amount FROM seckill_order WHERE activity_id = 1;
-- 一条 GRANTED、一条 FAILED
```

**看日志**：`[SECKILL-OVERSOLD]`。

> 这条日志**不是 bug**：Redis 认为还有货所以放行了 B，DB 才是最终裁决者，它说了不算。
> 设计上就是「Redis 挡流量、DB 保正确」，代价是 Redis 可能**少发**，绝不会**多发**。

### C4 权限

```bash
# 不带 token 抢购 → 40100 未登录
curl -s -X POST "$SECKILL/activities/1/grab"

# 不在白名单的人调预热 → 40300 无权执行该操作
curl -s -X POST "$SECKILL/admin/preheat" -H "Authorization: Bearer $OTHER_TOKEN"
```

### C5 对账

对账任务每 5 分钟一轮，看日志里有没有 `[SECKILL-RECONCILE]`：

| 日志 | 含义 | 要不要管 |
|---|---|---|
| 没有 | 三个视图一致 | — |
| `WARN ... 第 N 轮不一致` | 异步落库的正常延迟 | **不用**——判据是收敛不是相等 |
| `ERROR ... 连续 3 轮不收敛` | 需要人工介入 | 要 |

手工核对：

```sql
SELECT status, COUNT(*) FROM seckill_order WHERE activity_id = 1 GROUP BY status;
SELECT total_stock, sold_count FROM seckill_activity WHERE id = 1;
```

`GRANTED` 数应当等于 `sold_count`；Redis 的 `SCARD seckill:{1}:users` 应当 ≥ 这个数
（差额 = 在途事件 + 已 FAILED 的）。

### C6 活动状态自动流转（2026-09-20 新增）

**造一个 30 秒后开始的 DRAFT 活动**：

```sql
INSERT INTO seckill_activity (id, name, total_stock, sold_count, credit_amount, start_at, end_at, status)
VALUES (9001, '生命周期测试', 2, 0, 5000,
        DATE_ADD(NOW(), INTERVAL 30 SECOND), DATE_ADD(NOW(), INTERVAL 5 MINUTE), 'DRAFT');
```

**看**：打开 `/seckill`，卡片显示**「未开始」且置灰**（按钮点不动）。

**等 30 秒**——不用刷新页面。前端在 `startAt` 边界会自动重拉，
后端任务（5 秒一轮）把 status 翻成 ACTIVE 并预热。最坏 5 秒后卡片变成**可点**。

```sql
SELECT status, status_changed_at FROM seckill_activity WHERE id = 9001;
-- → ACTIVE，且 status_changed_at 有值
```

**抢完它**（库存只有 2）：抢满 2 个 → 再查一次 → `status = 'ENDED'`。

⚠️ **售罄不会立刻 ENDED**：要等 Stream 排空（events 读空 + PEL 清空）。
正常情况下 2 秒内就翻，看到它慢几秒是对的——那是「ENDED ⟺ 无在途工作」这条不变量在起作用，
不是卡住了。超过 10 分钟还没翻会打 `[SECKILL-LIFECYCLE] ... 仍有在途事件未排空`。

**验证「排空门」真的在挡**（可选）：

```sql
-- 手工把一场已到期的活动重新激活（注意 end_at 还在过去，5 秒后会被任务改回去）
UPDATE seckill_activity SET status = 'ACTIVE' WHERE id = 9001;
```

```bash
# 手工往 events 里塞一条没人消费的事件 → 它不该被结束
redis-cli XADD seckill:9001:events '*' activityId 9001 userId 1
```

再查 `status`——应当**仍是 ACTIVE**（有在途）。删掉这条事件后下一轮就会变 ENDED。

### C7 每日自动开场（2026-09-20 新增）

**不用做任何事**——服务起来后 `SeckillDailyTask` 每分钟检查一次，几秒内就会建出「下一场」。

**看**：打开 `/seckill`，应当**永远有一张卡**（哪怕当前不是活动时间）：

| 当前时刻 | 列表里 |
|---|---|
| 10:30 之前 | 今天的场（未开始 / 进行中 / 已抢完） |
| 10:30 之后 | 明天的场（「未开始 · 明日 10:00」）——今天的已结束，被 `end_at > now` 过滤掉 |

```sql
SELECT id, name, status, start_at, end_at FROM seckill_activity ORDER BY end_at LIMIT 5;
```

id 是 8 位日期（如 `20260921`）的那个就是它建的。

**验证幂等**：等几分钟再查一次——**不会多出第二场**。幂等靠的是主键
（活动 id 就是日期），不是任何额外机制。

**验证「窗口已过就不再建」**：把 `blog.seckill.daily.end-time` 临时改成过去时刻再启动，
**不应该**出现一场窗口全在过去的今天的活动——`activateDueActivities` 要求 `end_at > now`，
建出来就永远停在 DRAFT、永不激活、还不报错。

**反向验证配置**：把 `end-time` 改成 `"09:00"`（早于 `start-time`）→ **启动应当直接失败**，
并指出时间窗不自洽。改回去再启。
（对照：把 `"10:00"` 的引号去掉 → 也会启动失败，但那条是 YAML 把 `10:00` 当 60 进制整数
解析成了 `600`，和上一条不是一回事。）

⚠️ **手工建活动要避开 8 位 id**（那是每日任务的地盘）：12 位 `yyyyMMddHHmm` 开「一天两场」，
19 位雪花建临时活动。

> **存量库升级注意**：`db/init.sql` 只在建新库时执行，**老库里那条 id=1 的种子活动还活着**
> （100 份、`end_at` 在 30 天后），它会一直挂在列表上且能抢。上线这个功能时把它收掉：
>
> ```sql
> -- 最安全的做法：不删数据，只让它「已结束」——列表会立刻不再显示它
> UPDATE seckill_activity SET status = 'ENDED', end_at = NOW() WHERE id = 1;
> ```
>
> 之后生命周期任务会发现它到期且已排空，把它正式置成 ENDED（对齐时间戳）。
> 不要 `DELETE`——如果它已经发过额度，`seckill_order` 和钱包流水还引着它。

---

## 六、对账

### 6.1 钱包流水（`WalletReconcileTask`，每小时一轮）

查三件事：

| 检查 | 抓到什么 |
|---|---|
| `prev_seq + 1 == balance_seq` | **漏流水**——序号断档，有一笔没写进来 |
| `prev_balance + amount == balance_after` | **记错了**——余额快照接不上 |
| 每个用户最早一笔必须是第 1 号 | **开头的流水被删过**（前一条依赖 `LAG`，对第一笔返回 NULL，够不着这一格） |

判据是「**必须相等**」，不是「最终收敛」——**和秒杀对账正好相反**。秒杀的不一致有合理来源
（异步落库延迟），等一会儿会长回来；而流水是只增不改的账本，余额变更与流水在同一个事务里写
（`WalletService.applyChange`），**断档不会自愈**。所以这里不设容忍轮数，发现即 ERROR。

日志前缀 `[WALLET-RECONCILE]`。**看到就是真出事了**，要人工判断。
任务**刻意不自动修复**——余额和流水哪个是对的只有人知道，补一笔或改回去都是拿猜测覆盖事实。

⚠️ 它查的是**流水内部自洽**，不是「余额 = 流水累计」。所以下面那些手改
`user_wallet.balance` 的造场景 SQL **不会**让它误报（流水自身仍然自洽）。
这也是为什么校验的是前者：后者会被任何一次手改余额打破，那是造场景不是坏账。

### 6.2 手工核对（想看具体某个用户）

⚠️ 查的是**原始流水**（`wallet_transaction`），不是账单接口——对账必须逐笔，
只看聚合后的净额发现不了漏流水。

```sql
SELECT id, type, amount, balance_after, balance_seq,
       LAG(balance_after) OVER (PARTITION BY user_id ORDER BY balance_seq) AS prev_balance,
       LAG(balance_seq)   OVER (PARTITION BY user_id ORDER BY balance_seq) AS prev_seq
FROM wallet_transaction
WHERE user_id = <你的 userId>
ORDER BY balance_seq;
```

**判读**：
- `prev_balance + amount = balance_after` —— 记对了
- `balance_seq = prev_seq + 1` —— **没有漏流水**（断档说明有一笔没写进来）
- ⚠️ 回放必须按 `balance_seq` 排序，**不能按 `created_at` 或 `id`**：
  并发下时间戳会相同，而雪花 ID 的大小也不等于钱包行锁的获得顺序

---

## 七、已知缺口（本次有意不做，不是 bug）

| 缺口 | 说明 |
|---|---|
| **Agent / Workflow 不扣费**（2026-09-21 起是**终态**，不是待补的缺口） | 批次 B 只接了普通聊天，走 Agent 路径和 Workflow 确认动作**免费**。原计划「设计稿第 4、5 步推广」已判为**重复劳动、明确不做**——聊天侧已有完整的预扣 → 结算 → 差额退回，把同一套再接一遍收益不匹配。所以这是**产品边界**，不再是缺口 |
| **降级流不扣费** | Workflow 消失后降级的 `streamFallbackChatFlow` 没接计费（触发条件罕见） |
| **取消全额退** | 见 B4，V1 的产品取舍 |
| `ai_messages.token_count` 的 3 个遗漏点 | SSE 取消补存、Agent 异常分支、5 个 workflow-from-chat 入口——token 确实烧了但没记到消息上（存量问题，与计费无关） |
| 嵌入调用未统计 | `text-embedding-v3`（RAG 索引/召回）有实际成本但无 usage 采集 |
| **自定义充值未接限流** | `POST /recharge/orders/custom` 和套餐下单一样没有限流，理论上可被脚本刷出大量 `PENDING` 订单。**不付款不会到账**（`pay` 是独立动作，且那一步也没限流），但订单表会涨。要补就挂现有的限流桶 |
| **自定义充值只有单笔上限，没有每日上限** | 单笔 1 ~ 1000 元（`blog.wallet.custom`），但反复充可以绕过。鉴于这是虚拟支付、且每笔都要点一次「确认支付」，优先级低 |
| **秒杀没有活动管理后台** | **每日那场是自动建的**（C7），但手工建/改活动仍然只能写 SQL——没有 CRUD 和管理页（设计稿 §10 的取舍）。手工改时两个注意：**重来一次只能新建活动**（重置旧活动会撞钱包流水的幂等键，原因见 C3 的警告），以及**避开 8 位 id**（那是每日任务的地盘） |
| **活动表每天自动增长一行** | 一年 365 行，没有清理机制。不影响使用（列表有 `end_at > now` 过滤 + `LIMIT 20`，对账只处理 `status = ACTIVE`）。真要清理就按 `end_at < NOW() - INTERVAL N DAY AND status = 'ENDED'` 删，但删之前要确认那些活动的 `seckill_order` 和钱包流水不再被引用——**流水是账本，不能跟着删** |
| **重开秒杀活动必须同时改 `end_at`**（运维注意事项，不是 bug） | 状态流转任务是个「向时间窗收敛」的循环：只把 `ENDED` 改回 `ACTIVE` 而 `end_at` 还在过去的话，**5 秒内就会被改回去**，用户只有 5 秒窗口。改完还要调一次 `POST /api/seckill/admin/preheat`，否则 Redis 的 `meta.status` 还是 ENDED（Lua 检查的是它，不是 DB） |
| **多实例部署要改消费者名** | `SeckillSettleTask.CONSUMER` 写死成 `consumer-1`。多实例时两个实例共用一个消费者名，PEL 里的消息归属就分不清了——改成 hostname / Pod 名即可 |
| **秒杀发放的额度只能被普通聊天消费** | 消费侧只接了聊天（Agent / Workflow 免费是**终态**，见上一行），所以抢到的额度只有一条消费出口 |
| **秒杀深度对账不做**（设计稿 §7.2） | 「用 `SSCAN` 逐个核对每个预占者有没有对应订单」未实现。它要抓的是「总数相等但个体错位」，但 Lua 是 event-first（`XADD` → `DECR` → `SADD`），且 §7.1 数的是**全部**订单（含 `FAILED`）而不是只数 `GRANTED`——要让它漏，得同时出现「一个用户多」和「一个用户少」互相抵消，而 §7.1 的双向检查在那之前就会先报一次。成本（`SSCAN` 全量 + 逐个查库）换一个双重故障下的可见性，不划算。数据量上来再补 |

---

## 八、跑自动化测试

```bash
cd blog-system
./mvnw test -Dtest='Wallet*Tests,AiBillingServiceTests,ChatReserveCalculatorTests,InitialCreditGrantTests,Seckill*Tests'
```

当前：**97 例全绿**（钱包 18 + **自定义充值 19** + 计费 9 + 预扣公式 5 + 账单 6 + 钱包对账 4 + 注册赠送 2 + **秒杀 32（含生命周期 13、每日开场 5）** + HTTP 并发 2）。

其中并发用例是重点，不是摆设：
- 20 线程抢扣 100，余额 1000 → **恰好 10 次成功**（拿掉 `AND balance > 0` 会变成 20 次）
- 8 线程并发支付同一订单 → **只加一次钱**
- 30 线程抢 10 份库存 → **恰好 10 个「排队中」**
- 同一用户并发点 10 次 → **只成功 1 次**（拿掉 Lua 里的 `SISMEMBER` 实测变成 10 次）

对账用例靠**直接插流水**造坏数据——走 service 根本造不出不自洽的账本，而坏数据正是要检测的对象。
有一条专门锁住那个盲区：删掉开头的流水后，剩下的序列仍然首尾自洽，窗口函数一条都不报，
只有「首笔必须是 1」这个不变量能发现（反向验证：摘掉 `prev_balance + amount` 那条检查，
精确红在 `balanceMismatchIsDetected` 上）。
