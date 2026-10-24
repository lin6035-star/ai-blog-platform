**Redis 配置**

- DB1
- 01star
- key 命名风格

**已接入的 Redis key**

- `article:view:{articleId}` String
- `article:hot` ZSET
- `article:detail:{articleId}` String JSON
- `article:list:...` String JSON
- `category:list` String JSON
- `tag:list` String JSON
- `article:liked:user:{userId}` Set
- `article:favorited:user:{userId}` Set
- `comment:liked:user:{userId}` Set
- `comment:list:article:{articleId}:...` String JSON
- `rag:index:fail:{action}:{articleId}` String，RAG 索引同步失败标记（action = index / delete / rebuild，TTL 7 天）

**缓存规则**

- 公共数据可以缓存
- 用户态字段不缓存，返回前动态补
- `liked/favorited` 动态补
- `viewCount` 动态叠加 Redis 浏览量
- `likeCount/commentCount/favoriteCount` 允许短 TTL 延迟

**缓存失效规则**

- 文章本体变更删文章详情 + 文章列表
- 点赞/收藏删文章详情，不删文章列表
- 发/删评论删文章详情 + 评论列表
- 评论点赞不删评论列表

**后续优化**

- GitHub OAuth 原型已接入，后续补 `state` 校验、一次性登录码替代 URL token、敏感配置外置
- 登录失败次数限制可用 Redis
- 搜索可从标题模糊匹配升级到 summary/content 或 Elasticsearch
- AI 助手游客临时次数限制可用 Redis
- SSE 流式输出阶段可评估是否需要 Redis 保存短期生成状态
