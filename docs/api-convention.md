# API Convention

本文档是 `01myBlog` 的接口约定。新增或修改前后端接口前，先看本文档。

## 核心规则

- 前端统一请求 `/api/...`。
- 后端 Controller 统一提供 `/api/...`。
- 页面组件不直接写接口地址，统一调用 `frontend/src/api/*.ts`。
- Axios 实例统一放在 `frontend/src/utils/request.ts`。
- `request.ts` 配置 `baseURL: '/api'`，`api/*.ts` 中不要重复写 `/api`。
- 开发环境用 Vite proxy 转发 `/api` 到后端。
- 生产环境用 Nginx 反向代理 `/api` 到后端。

## 前端调用约定

页面组件只调用业务方法：

```ts
articleApi.getList()
```

接口路径集中写在 `api/*.ts`：

```ts
request.get('/articles')
```

最终请求地址是：

```text
GET /api/articles
```

公开接口和用户接口分文件维护：

- `api/article.ts`：公开文章接口
- `api/category.ts`：公开分类接口
- `api/tag.ts`：公开标签接口
- `api/auth.ts`：登录注册接口
- `api/user.ts`：当前登录用户资料接口
- `api/myArticle.ts`：当前登录用户的文章管理接口

## 访问权限

本项目是公开博客，不是强制登录系统。

游客可访问：

- 首页
- 文章列表
- 文章详情
- 分类列表
- 标签列表

登录用户可使用：

- 个人中心
- 发布文章
- 编辑自己的文章
- 删除自己的文章
- 隐藏自己的文章
- 查看自己的草稿和隐藏文章
- 点赞
- 收藏
- 评论
- 关注

文章管理的核心规则是：用户只能操作自己的文章。

V2 互动功能当前状态：

1. 评论、评论点赞、文章点赞 / 收藏、关注作者已接入。
2. 公开用户主页、用户发布文章、喜欢、收藏、评论过的文章、关注列表、粉丝列表已接入。
3. Redis 缓存和热点数据优化已部分接入，继续记录在 `docs/reids-notes.md`。
4. 站内消息通知、私信、WebSocket、RocketMQ 暂缓，后续作为独立通知业务设计。

## 接口路径

### 公开接口

```text
GET /api/articles?cursor=&pageSize=10             获取公开文章列表（游标分页）
GET    /api/articles/{id}         获取公开文章详情
GET    /api/categories            获取分类列表
GET    /api/tags                  获取标签列表
```

删除为软删除（`deleted_at` 记录删除时间），不物理删除数据。

### 搜索接口

```text
GET /api/articles?keyword=&cursor=&pageSize=10     搜文章（ES 全文检索）
GET /api/users/search?keyword=&page=1&pageSize=10  搜用户（按昵称）
```

文章搜索：

- `keyword` 非空时走 ES（标题 + 摘要 + 正文，相关性排序 + 高亮）；ES 不可用时**降级到 LIKE**，不报错。
- 分页仍是游标（`cursor` / `nextCursor`），与普通文章列表一致。
- **`sort` 参数在关键字路径下目前不生效**——`ArticleSearchService` 把排序固定成
  `_score DESC, articleId DESC`，没有把 `sort` 接进排序数组（该类注释已登记）。
  前端搜索页因此不提供排序 tab，别为了"看起来完整"加一个假的排序。

用户搜索：

- 只搜 `nickname`。**不搜 `username`**（登录账号，能搜等于让任何人批量试探某个账号是否存在）；
  **不搜 `bio`**（噪声太大，简介里出现一个通用词就会把无关用户全捞出来）。
- 公开接口，游客可访问（`/api/users/*` 在 `WebMvcConfig` 里挂的是 `OptionalJwtInterceptor`）。
  登录用户访问时返回 `followed` / `self`，游客访问时两者为默认值。
- 传统分页（`page` / `pageSize` + `total`），与关注 / 粉丝列表一致——用户量小，不需要游标。
- 关键词为空返回空页，**不返回全站用户列表**（那不是「搜索」语义）；
  软删除与禁用（`status != 1`）账号不出现在结果里。
- 路径 `/api/users/search` 与 `/api/users/{id}` 不冲突：Spring 路径匹配**字面量优先于变量段**，
  由 `UsersControllerTests#searchesUsersByNicknameWithoutAuth` 锁住。
- ⚠️ `users.nickname` **没有索引**，`LIKE '%kw%'` 前置通配本来也用不上 B+ 树索引。
  当前用户量下无影响；**量到万级要换 ES 或前缀索引**，给 nickname 建普通索引解决不了这个问题。

### 分类和标签

分类和标签都是全站公共资源，所有用户和游客都能看到。

V1 规则：

- 分类先固定，不开放普通用户创建。
- 标签允许登录用户在发布文章时自由创建 / 复用。

```text
GET    /api/categories            获取分类列表
GET    /api/tags                  获取标签列表
POST   /api/tags                  创建标签（登录用户发文时调用）
```

如果后续要开放分类管理，再单独新增接口。

### 认证接口

```text
POST /api/auth/register             注册
POST /api/auth/login                登录
POST /api/auth/logout               退出登录
GET  /api/auth/github/url           获取 GitHub OAuth 授权地址
GET  /api/auth/github/callback      GitHub OAuth 回调
```

GitHub OAuth 当前是原型接入状态：

- 前端登录 / 注册页已有 GitHub 登录入口。
- 后端完成授权地址生成、callback 换取 GitHub 用户信息、创建或查询本地用户、签发本站 JWT。
- 后续需要补 `state` 校验，防止 CSRF。
- 后续建议用一次性登录码或后端会话承接登录结果，替代 callback URL 直接携带 JWT。
- `client-secret` 等敏感配置后续要外置到环境变量或服务器安全配置中。

### 评论接口

评论属于 V2 互动功能，后端接口和文章详情页评论区已接入。

评论规则：

- 游客可以查看公开文章的评论。
- 登录用户可以发表评论和回复评论。
- 评论不允许修改。
- 评论者可以删除自己的评论。
- 文章作者可以删除自己文章下的任意评论。
- 删除评论使用软删除。
- 删除父级评论时，递归软删除它下面的所有子评论。
- 前端只展示 `ipLocation`，不展示原始 IP。
- 多级回复使用 `parentId` 表示直接回复的评论，使用 `rootId` 表示所属主评论楼层。


评论列表排序：

| sort | 含义 |
|------|------|
| `time` | 按时间排序，默认值 |
| `hot` | 当前按点赞数排序，点赞数相同再按时间排序 |

热门排序阶段约定：

- 当前阶段先用数据库字段实现：`like_count DESC, created_at DESC`。
- 暂时不把回复数和时间衰减加入 SQL 排序，避免评论列表查询变复杂。
- Redis 阶段再升级综合热度，例如：`hotScore = likeCount * 2 + replyCount - timeDecay`。
- 综合热度适合后续用 Redis ZSET 维护，避免每次请求都实时计算回复数和时间衰减。

发表评论请求体：

```json
{
  "content": "写得不错",
  "parentId": null
}
```

回复评论请求体：

```json
{
  "content": "回复上一条评论",
  "parentId": 12
}
```

评论 IP 规则：

- `ip` 和 `ipLocation` 不属于发表评论请求体，前端不能提交这两个字段。
- 生产环境后端优先从 Cloudflare 的 `CF-Connecting-IP` 请求头获取访客真实 IP。
- 没有 Cloudflare 请求头时，依次使用 `X-Forwarded-For`、`X-Real-IP` 和连接远端地址，方便本地开发和直接访问。
- IPv4 属地使用后端内置的 `ip2region` 数据库离线解析，不调用第三方网络接口。
- 中国大陆 IPv4 显示省份，海外 IPv4 显示国家，内网地址显示“本地网络”。
- 公网 IPv6 使用 Cloudflare 的 `CF-IPCountry` 国家代码作为兜底，Cloudflare 后台需要开启 `IP Geolocation`。
- 公网 IPv6 缺少有效国家代码、非法 IP 或无法解析时显示“未知”。
- 原始 IP 只保存在数据库中，不通过评论 VO 返回给前端。

评论返回字段约定：

```json
{
  "id": 1,
  "articleId": 10,
  "userId": 3,
  "nickname": "hailin",
  "avatarUrl": "https://example.com/avatar.png",
  "content": "写得不错",
  "rootId": null,
  "parentId": null,
  "replyToNickname": null,
  "ipLocation": "广东",
  "likeCount": 0,
  "liked": false,
  "createdAt": "2026-07-12 10:00:00",
  "replies": []
}
```

字段说明：

| 字段 | 含义 |
|------|------|
| `rootId` | 所属主评论 ID，主评论可为 `null` |
| `parentId` | 直接回复的评论 ID，主评论为 `null` |
| `replyToNickname` | 通过 `parentId` 关联父评论用户得到，用于前端显示“回复 @某某” |
| `ipLocation` | 评论 IP 属地，只展示国家或省份 |
| `liked` | 当前登录用户是否点赞；游客访问时为 `false` |
| `replies` | 主评论下的回复列表 |


公开用户主页规则：

- 默认展示该用户发布的公开文章。
- 其他 tab 可以展示喜欢、收藏、评论过的文章、关注列表、粉丝列表。
- 暂不做隐私设置；隐藏文章、草稿、已删除文章不在公开主页展示。
- 当前登录用户访问别人主页时，返回 `followed` 表示自己是否已关注该用户。

### AI / Agent 接口（已实现）

方向是全站统一 AI Agent Shell，不是每篇文章一个独立助手。已实现 SSE 流式聊天、会话管理、RAG 检索：

```text
POST   /api/ai/workflows/article/create      创建文章 Workflow
GET    /api/ai/workflows/{id}                查询 Workflow 状态
GET    /api/ai/workflows/{id}/steps          查询 Workflow Step 执行日志
POST   /api/ai/workflows/{id}/approve        同意当前步骤
POST   /api/ai/workflows/{id}/reject         拒绝当前步骤并提交反馈
POST   /api/ai/workflows/{id}/retry          重试失败的 Workflow 当前步骤
POST   /api/ai/workflows/{id}/approve/stream 同意当前步骤（SSE）
POST   /api/ai/workflows/{id}/reject/stream  拒绝当前步骤并提交反馈（SSE）
POST   /api/ai/workflows/{id}/retry/stream   重试失败的 Workflow 当前步骤（SSE）
POST   /api/ai/workflows/{id}/complete       完成 Workflow
POST   /api/ai/workflows/{id}/cancel         取消 Workflow
```

聊天请求带上页面上下文：

```json
{
  "conversationId": 1,
  "message": "这篇文章大概讲什么？",
  "pageContext": {
    "pageType": "article-detail",
    "path": "/articles/10",
    "articleId": 10,
    "articleTitle": "Spring AI 入门"
  }
}
```

`pageContext` 约定：

| 字段 | 含义 |
|------|------|
| `pageType` | 当前页面类型，例如 `home`、`article-detail`、`profile`、`editor`、`other` |
| `path` | 当前前端路由路径 |
| `articleId` | 文章详情页可传，其他页面可为空 |
| `articleTitle` | 文章详情页可传，用于辅助提示词 |

游客规则：

- 游客使用临时会话，不入库。
- 游客提问次数限制（约 3 次）尚未实现。
- 游客不提供历史会话列表。

登录用户规则：

- 登录用户会话和消息持久化到 MySQL。
- 登录用户可以新建、查看、删除历史会话。

### 学习计划接口（Workflow 产物）

LEARNING_PLAN / LEARNING_PROGRESS Workflow 生成的学习计划，登录用户长期拥有：

```text
GET   /api/learning-plans                                         我的学习计划列表（createdAt 倒序）
GET   /api/learning-plans/{id}                                    计划详情（阶段/任务/聚合进度，校验归属）
PATCH /api/learning-plans/{planId}/stages/{stageId}/tasks/{index} 任务勾选（body {"done": true/false}，index 从 0 开始）
```

规则：

- 进度实时聚合（done 任务数 / 总数），不存库
- 计划调整（LEARNING_PROGRESS）按 planId 覆盖原计划，不新建计划

### 钱包接口（登录用户）

额度（credit）用于 AI 助手消费，按实际 token 用量扣减。钱包是**纯 DB** 实现，不使用 Redis
（判据是"数据可不可以丢"——余额少一分就是账不平，引入 Redis 只会凭空造出不一致窗口）。

```text
GET  /api/wallet                                    当前余额 + 进行中的预扣笔数
GET  /api/wallet/bill?page=1&pageSize=10            账单（按业务聚合，倒序）
GET  /api/wallet/packages                           充值套餐列表
GET  /api/wallet/recharge/custom-config             自定义充值的上下限与兑换比例
POST /api/wallet/recharge/orders                    套餐下单（body: {"packageCode": "PACK_10"}）
POST /api/wallet/recharge/orders/custom             自定义金额下单（body: {"payYuan": 5}）
POST /api/wallet/recharge/orders/{orderNo}/pay      支付（虚拟支付）
```

规则：

- **套餐入口**只传 `packageCode`，应付金额与到账额度一律由后端按套餐决定——请求体里根本没有金额字段。
  这是靠**类型**保证的契约（`WalletRechargeDTO` 只有一个 `String` 字段），不是靠 if 判断，别把金额字段加进去。
- **自定义金额入口**是独立的 `POST /api/wallet/recharge/orders/custom`，请求体 `{"payYuan": 5}`
  ——**唯一接受金额的充值接口**。传的是**元**（整数，1 ~ 1000），字段名带 `Yuan` 是刻意的：
  这个项目里 `payAmount` 一律是**分**。到账额度仍由后端按 `blog.wallet.custom.credit-per-yuan` 算
  ——用户能决定「充多少钱」，决定不了「到账多少 credit」。金额越界 / 非整数返回 `40001`。
- ⚠️ **元 / 分 / credit 三者不许混**：`payYuan` 是元，`payAmount` 是分，额度是 credit。
  前端 `formatPayAmount` 吃**分**、`formatCredit` 吃 **credit**，两个函数不可互换
  （把 5 元喂给 `formatPayAmount` 会显示成 ¥0.05）。
- 支付是**幂等**的：订单号 + 状态 CAS 保证重复支付只加一次额度，前端不需要做去重，也不需要传 `Idempotency-Key`。
- 支付时校验订单归属，越权返回 `40300`。
- 余额**允许为负**（透支一轮）：预扣门槛是"余额 > 0"，所以一次调用可能把余额扣成负数，结算后按实际用量退回。
- **注册即送**：新账号注册完立刻带上 `blog.wallet.initial-credit` 额度（默认 10000），
  幂等键 `INITIAL:{userId}` 保证一个用户只送一次。没有它，计费上线后每个新用户注册完立刻就是「额度不足」。

`GET /api/wallet/bill` **只展示最近 N 天**（后端 `blog.wallet.bill-retention-days`，默认 7；
`GET /api/wallet` 会把它透出成 `billRetentionDays`，前端据此写「仅显示最近 N 天」，
**不要在前端硬编码那个 7**）。

⚠️ 这个时间窗口**只在展示层**：`wallet_transaction` 一行都不删。它是按 `balance_seq`
**连续编号**的账本，逐笔回放对账（`LAG` 检查"序号连号 + 余额连续"）依赖这个连续性——
删掉任何一段，巡检都会报"漏流水"假警，把真正的账不平淹没掉。
真要减负应该走归档或分区，不是 DELETE。

`GET /api/wallet` 返回 `pendingReserveCount`——负余额靠它区分两种完全不同的含义：

```json
{ "balance": -5000, "pendingReserveCount": 1 }
```

| 条件 | 前端展示 |
|------|----------|
| `balance > 0` | 正常可用 |
| `balance <= 0` 且 `pendingReserveCount > 0` | "本次调用进行中"——**不要提示欠款** |
| `balance <= 0` 且 `pendingReserveCount == 0` | "额度不足，请充值" |

- 额度单位是 credit，不是"分"。套餐里的 `payAmount` 才是分（真实货币口径，虚拟支付下仅展示）。

**账单是聚合视角，不是原始流水**：

`GET /api/wallet/bill` 的每一条是**一笔业务**，不是一行流水——一次 AI 调用只有一条净额
（比如 `-35`），不会出现「预扣 -400 / 退回 +365」的中间态。

原因：`wallet_transaction` 是**记账视角**。一次 AI 调用必须写两条（预扣 + 结算退回），
因为「改余额」和「记账」要逐笔对应，否则按 `balance_seq` 的逐笔回放会断档。
但用户要看的是「充了多少、花了多少」，中间态属于记账的实现细节。

两条过滤规则：

| 规则 | 理由 |
|---|---|
| **预扣中的不显示**（退款还没配对） | 净额没定下来，不是一笔已发生的事实 |
| **净额为 0 的不显示**（取消的对话全额退回） | 用户没花钱，账单里就没有"一笔消费"这回事 |

字段：`bizType`（`RECHARGE_ORDER` / `SECKILL_ORDER` / `INITIAL_GRANT` / `AI_BILLING`）、
`amount`（**净额**，有符号）、`balanceAfter`（该笔业务**结算后**的余额快照）。

> 原始流水仍完整保存在 `wallet_transaction` 里，对账时直接查表（见验证指南的对账 SQL）。

### 秒杀接口

额度秒杀：抢到的额度直接进钱包，可用于 AI 助手消费。

```text
GET  /api/seckill/activities                   活动列表（游客可见）
POST /api/seckill/activities/{id}/grab         抢购（登录）
GET  /api/seckill/activities/{id}/result       查结果（登录，前端轮询）
POST /api/seckill/admin/preheat?activityId=    预热 / 重建 Redis 状态（白名单）
```

**抢购返回的是「排队中」，不是「抢到了」**：

`grab` 成功只代表**名额占住了、入账事件已入队**——真正的发放由后端消费者异步完成
（Redis 预占 → Stream → DB 条件更新裁决 → 钱包入账）。所以拿到 `QUEUED` 之后
**必须轮询 `/result`** 直到出现确定性结论。把这两者混为一谈，用户就会看到
「抢到了」然后额度迟迟不到。

`status` 取值与前端应有的表现：

| status | 含义 | 前端 |
|---|---|---|
| `NONE` | 没参与过 | 显示「立即抢」 |
| `QUEUED` | 已占住名额，等待入账 | 显示「排队中…」，**不要提示成功** |
| `GRANTED` | 已到账（`creditAmount` 有值） | 显示「已到账」 |
| `FAILED` | 售罄失败（终态） | 显示「未抢到」 |
| `FAILED_RETRY` | 发放异常重试超限（终态，需人工） | 显示「发放异常」 |
| `SOLD_OUT` / `DUPLICATE` / `NOT_ACTIVE` | grab 被当场拒绝的理由 | 弹提示，不轮询 |

> 秒杀的业务失败**走 `status` 字段而不是错误码**：抢购失败是正常业务分支不是异常，
> 而且 `/result` 轮询返回的是同一套取值，复用它能少一套前端分支。

规则：

- **一人一单**：Redis Set 挡重复点击（快速路径）+ `uk_user_activity` 唯一索引兜底。
- **限流**：只拦 `grab`（20 次/分钟）。**结果轮询不限流**——它是前端每秒一次的正常行为，
  限流它等于让用户还没抢到就先撞 429。
- **预热**：服务启动时自动把 ACTIVE 活动灌进 Redis，运维接口可手动重建。
  由 `blog.seckill.admin-user-ids` 白名单保护，**默认空 = 谁都不能调**
  （项目还没有角色体系，这是最小权限的临时方案）。
- **余额到账口径**：`creditAmount` 是快照值，活动改额度不影响已发出的。

### 当前用户接口

当前用户接口统一使用 `/api/users/me/...`，需要登录后访问。

```text
GET /api/users/me
获取当前登录用户资料

PUT /api/users/me/profile
修改当前登录用户的昵称和个人简介

POST /api/users/me/avatar
上传当前登录用户头像

GET /api/users/me/articles?page=1&pageSize=10&status=1
获取自己发布/草稿/隐藏的文章列表

GET /api/users/me/liked?page=1&pageSize=10
获取自己喜欢的文章列表

GET /api/users/me/favorited?page=1&pageSize=10
获取自己收藏的文章列表

GET /api/users/me/commented?page=1&pageSize=10
获取自己评论过的文章列表，按最近评论时间倒序，重复评论同一篇文章只返回一次
```

修改个人资料请求体：

```json
{
  "nickname": "新的昵称",
  "bio": "新的个人简介"
}
```

## 返回格式

所有接口统一返回：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

分页数据统一放在 `data` 中。项目里有**两种**分页，返回体是同一个 `PageVO`，靠字段区分。

**传统分页**（评论 / 钱包账单 / 我的文章 / 公开主页列表）：

```json
{
  "code": 0,
  "message": "success",
  "data": { "list": [], "total": 120, "page": 2, "pageSize": 10 }
}
```

请求传 `page` / `pageSize`；`total` 是命中总数；`nextCursor` 恒为 `null`。

**游标分页**（**首页公开文章列表**）：

```json
{
  "code": 0,
  "message": "success",
  "data": { "list": [], "total": null, "page": null, "pageSize": 10, "nextCursor": "MTAwMHwyMDg1" }
}
```

- 请求传 `cursor` / `pageSize`：不传 `cursor` = 第一页；之后把上一页返回的 `nextCursor` **原样带回**。
- `nextCursor` 为 `null` = 已经到底。**判断"还有没有下一页"看它，不要看 `total`。**
- `total` / `page` 恒为 `null`——游标分页不需要 total，算它要多一条 `COUNT(*)`。
  （后端返回里它们是真的 `null`，所以前端 `PageData.total` 是可选的，取值时要 `?? 0`。）
- `nextCursor` 是**不透明 token**：前端不要解析、不要拼装，拿到就传回。
  它不是权限边界（Base64 不是加密），只保证"格式非法"被识别成 `40001` 而不是 500。

> **为什么首页用游标、其他列表不换**：首页是无限滚动，用户不会跳到第 37 页，但会一直往下滑——
> 而 offset 分页恰恰"越往下滑越慢"。游标分页的代价恒定为 `O(log N + size)`，换来的是不能跳页，
> 而无限滚动本来就不跳页，所以这个代价在这里等于零。
> 有页码的后台列表（我的文章、账单）保留 `page` 语义是对的，**不要一起换**。

前端响应拦截器默认返回完整 `Result<T>`，业务数据从 `result.data` 读取。

## 错误码

| code | 含义 |
|------|------|
| 0 | 成功 |
| 40001 | 参数校验失败 |
| 40100 | 未登录 |
| 40101 | 用户名或密码错误 |
| 40200 | 额度不足（HTTP **402**，不是默认的 200） |
| 40300 | 无权限 |
| 40400 | 资源不存在 |
| 50000 | 服务器内部错误 |

> `40200` 走独立的 `@ExceptionHandler` 显式返回 HTTP 402。**不能**用默认的 `BusinessException`（HTTP 200 + JSON）：
> 前端的 SSE 请求在 200 时只认 `data:` 前缀的行，JSON 错误体会被静默忽略，用户看到的是"连接意外中断"。

## 命名约定

- URL 使用复数名词：`/articles`、`/categories`、`/tags`。
- 公开浏览接口使用 `/api/...`。
- 当前登录用户自己的资源使用 `/api/users/me/...`。
- 查询条件使用 query string，例如 `/api/articles?page=1&pageSize=10`。
- 新增和修改使用 JSON body。

## 修改接口前检查

- 页面里是否没有直接写 URL。
- `api/*.ts` 是否没有重复写 `/api`。
- 是否区分公开接口和登录用户接口。
- 登录用户修改文章时，后端是否校验只能操作自己的文章。
- 后端返回格式是否统一。
- Vite proxy 和 Nginx 反向代理是否都按 `/api` 转发。
