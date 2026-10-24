# RocketMQ（本地开发 / 生产接入说明）

> 2026-10-22 · 当前接入范围：**只承载 AI 记忆事件**（候选记忆 / 情景记忆 / 会话摘要），
> 秒杀继续用 Redis Stream；文章索引同步（第二批）仍待单独设计。
> 设计与取舍见 `docs/mq/rocketmq-integration-design.md`。

## 1. 本地开发

```bash
cd deploy/rocketmq
docker compose up -d
docker ps --filter name=rmq
```

> ⚠️ 如果你之前在本地**手工**起过 `rmqnamesrv` / `rmqbroker`（旧方案），先清掉再交给 compose：
> `docker rm -f rmqnamesrv rmqbroker && docker network rm rmq-net`。
> 否则会撞容器名；而且手工建的 `rmq-net` 没有 compose 标签，compose 会报
> `network rmq-net was found but has incorrect label`。二选一：继续用手工容器，或者用本 compose。

预期看到 `rmqnamesrv` 与 `rmqbroker` 两个容器；Broker 启动日志里应该有
`The broker[broker-a, <brokerIP1>:10911] boot success`。

### brokerIP1 是最容易踩的坑

`broker.conf` 里的 `brokerIP1` 是 Broker **注册给客户端**的地址。填错的现象是
`connect to 172.17.0.2:10911 failed`（看着像网络问题，其实是注册地址不对）。

- Windows / macOS Docker Desktop：填宿主机在 Docker 网桥上的网关地址。
  本机实测 `192.168.65.1`；换机器或 Docker 重启后可能变化，变了就改 `broker.conf` 再
  `docker compose restart rmqbroker`。
- Linux 生产（后端与 MQ 同一个 compose 网络）：填 Broker 服务名，例如 `rocketmq-broker`；
  客户端也是容器，能通过 compose 网络解析它。

### 验证三层

```bash
# ① 生产端能发（也可以用 RocketMQ dashboard / mqadmin）
# ② 应用内开关打开后发一条真实对话，看日志
# ③ 消费端日志（INFO 级别能看到重复投递；DEBUG 能看到每次消费）
```

应用侧需要同时打开开关（本地可用环境变量或 `config/application.yml`）：

```yaml
blog:
  ai:
    memory:
      extraction:
        use-mq: true
rocketmq:
  name-server: 127.0.0.1:9876
```

启动后日志应出现 `[MQ-MEMORY]` 与 `blog-memory-consumer` / `blog-episodic-consumer` /
`blog-summary-consumer` 的消费线程。

## 2. 事件与 topic

| 项 | 值 |
|---|---|
| topic | `ai-memory-extraction`（`blog.ai.memory.memory-extraction-topic`） |
| tag | `CANDIDATE` / `EPISODIC` / `SESSION_SUMMARY` |
| 生产组 | `blog-memory-producer`（`ROCKETMQ_PRODUCER_GROUP`） |
| 消费组 | `blog-memory-consumer` / `blog-episodic-consumer` / `blog-summary-consumer` |
| 消费语义 | at-least-once；事件级幂等表 `ai_memory_extraction_events`；失败抛异常触发重试，重试耗尽进 DLQ |
| 消费并发 | 每链路 `consumeThreadMax=2`（对齐原线程池，防止把 LLM 供应商打爆） |

## 3. 生产 compose 片段（部署到 `/opt/blog/docker-compose.yml`）

> 当前 `deploy-package/docker-compose.yml` 还没有这一段；加之前先确认服务器内存档位
> （香港 2C8G，现有 ES 1G + backend 1.5G + MySQL 512M + Redis 256M，实测约用 4.7G）。
> **Broker/NameServer 必须压堆**，否则会把 ES 挤到 OOM。

```yaml
  rocketmq-namesrv:
    image: apache/rocketmq:5.3.1
    container_name: blog-rocketmq-namesrv
    restart: always
    command: sh mqnamesrv
    environment:
      JAVA_OPT_EXT: "-Xms256m -Xmx256m -Xmn128m"
    networks: [blog]

  rocketmq-broker:
    image: apache/rocketmq:5.3.1
    container_name: blog-rocketmq-broker
    restart: always
    depends_on: [rocketmq-namesrv]
    command: sh mqbroker -c /home/rocketmq/conf/broker.conf
    environment:
      JAVA_OPT_EXT: "-Xms512m -Xmx512m -Xmn256m"
    volumes:
      - ./rocketmq/broker.conf:/home/rocketmq/conf/broker.conf:ro
      - ./rocketmq/store:/home/rocketmq/store
    networks: [blog]
```

生产 `broker.conf` 与本地不同点：

- `namesrvAddr = rocketmq-namesrv:9876`（compose 服务名）
- `brokerIP1 = rocketmq-broker`（客户端是 backend 容器）
- `autoCreateTopicEnable = false` / `autoCreateSubscriptionGroup = false`
  （生产必须关：拼错的 topic 名会静默创建一个没人消费的 topic）
- 可靠性档位按需：单机练习 `ASYNC_MASTER + ASYNC_FLUSH` 可接受，
  要讲"不丢"的完整口径就改成 `SYNC_MASTER` + `SYNC_FLUSH`

然后在后端容器的环境变量里加：

```
ROCKETMQ_NAME_SERVER=rocketmq-namesrv:9876
AI_MEMORY_EXTRACTION_USE_MQ=true
ROCKETMQ_MEMORY_CONSUMER_GROUP=blog-memory-consumer
ROCKETMQ_EPISODIC_CONSUMER_GROUP=blog-episodic-consumer
ROCKETMQ_SUMMARY_CONSUMER_GROUP=blog-summary-consumer
```

## 4. 排障速查

| 现象 | 先看什么 |
|---|---|
| 启动后没有消费线程 | `use-mq` 是否 true；`publisher` 是否存在；`rocketmq.name-server` 是否可达 |
| `connect to 172.x.x.x:10911 failed` | `brokerIP1` 注册错（见上） |
| 日志 `convert failed` | 消息体不是合法 JSON（常见于手工 `mqadmin sendMessage` 探针）；应用生产端不会这样发 |
| 消息一直重试 | 消费端抛异常（LLM 超时/DB 失败）；重试耗尽后进 `%DLQ%<consumerGroup>` |
| 记忆没写但聊天正常 | 这是 fail-open 的设计：投递失败只 WARN，不阻塞聊天；按 `[MQ-MEMORY] publish failed` 排查 |
