# 敏感配置外置设计稿

> 创建：2026-09-14  
> 定位：工程化第一刀，先把密钥和密码从版本库配置中移出；不顺手做登录失败限制、OAuth state、Docker 或 CI/CD。
>
> **实施后修订（2026-09-14）**：配置文件最终形态是 `config/application.yml` / `config/application.example.yml`——
> **不带 `-local` 后缀、也不需要 `profile=local`**。下文 §四、§十二 里残留的 `application-local` 字样属初稿讨论，
> 以 §6.2 的定稿描述与 §13.2 的偏离表为准。

## 一、结论先行

本刀建议采用**配置外置 + 示例配置 + 启动校验**的窄方案：

1. `application.yml` / `application-prod.yml` 不再保存真实密钥、密码或可复用 token。
2. 本地开发使用环境变量或本机私有配置文件承接真实值。
3. 仓库只提交 `config/application-local.example.yml`，不提交 `config/application-local.yml`。
4. 生产环境对关键配置 fail-fast，本地环境允许更友好的报错，但不静默使用线上级密钥。
5. 启动日志只打印“缺哪项配置”，绝不打印配置值。

这一刀的价值不是功能变化，而是安全底线和工程规范：面试时能讲清楚“密钥不进 Git、环境隔离、启动校验、缺配置如何失败”。

## 二、当前证据

当前 `blog-system/src/main/resources/application.yml` 仍存在以下问题：

| 配置 | 当前状态 | 判断 |
|---|---|---|
| `spring.datasource.password` | 写死本地密码 | 应外置 |
| `spring.data.redis.password` | 写死本地密码 | 应外置 |
| `jwt.secret` | 写死 dev secret | 应外置，且长度要校验 |
| `github.oauth.client-secret` | 写死 secret | 必须外置 |
| `spring.ai.openai.api-key` | 已使用 `${ALIYUN_API_KEY}` | 保持 |
| `aliyun.oss.endpoint/bucketName/region` | 非密钥配置写死 | 可保留默认，也可支持 env override |
| OSS AccessKey | 代码使用 `EnvironmentVariableCredentialsProvider` | 已外置，不在 yml 中加 key |

`application-prod.yml` 已把 `DB_PASSWORD` 和 `JWT_SECRET` 外置，但覆盖不完整：GitHub OAuth、Redis、AI key、前端回调地址等还没有形成统一约定。

## 三、目标与非目标

### 目标

- 移除版本库中的真实 secret / password。
- 给出本地开发和生产部署的明确配置入口。
- 启动时尽早发现关键配置缺失，而不是等用户点接口才报错。
- 保持现有业务行为不变：登录、GitHub OAuth、AI、OSS、Redis 连接语义不在本刀重做。

### 非目标

- 不做 GitHub OAuth `state` 校验。
- 不做登录失败次数限制。
- 不做 JWT refresh token / 黑名单。
- 不引入 Spring Cloud Config、Nacos、Vault、KMS。
- 不把所有普通配置都强行环境变量化。
- 不提交 `.env` 或任何真实本地配置文件。

## 四、方案取舍

### 方案 A：全部配置都用环境变量

优点：简单直接，生产友好。  
缺点：本地开发启动麻烦，变量多时不利于学习和排查。

### 方案 B：环境变量 + 本机私有 local 配置

优点：生产用环境变量，本地可用 `application-local.yml`，开发体验和安全性平衡。  
缺点：需要维护一个 example 文件，并确保真实 local 文件进 `.gitignore`。

### 方案 C：继续保留 dev 默认密钥

优点：最省事。  
缺点：达不到“敏感配置外置”的目标，面试时也站不住。

**建议采用 B。**

原因：这个项目是学习项目，本地频繁启动；纯环境变量会增加摩擦。但真实密钥不能进 Git，所以用 `application-local.yml` 承接本机值，仓库只放 `application-local.example.yml`。

## 五、配置分级

### 5.1 必须外置的敏感项

| 配置键 | 建议环境变量 | 是否允许 Git 默认值 |
|---|---|---:|
| `spring.datasource.password` | `DB_PASSWORD` | 否 |
| `spring.data.redis.password` | `REDIS_PASSWORD` | 否 |
| `jwt.secret` | `JWT_SECRET` | 否 |
| `github.oauth.client-secret` | `GITHUB_OAUTH_CLIENT_SECRET` | 否 |
| `spring.ai.openai.api-key` | `ALIYUN_API_KEY` | 否 |
| `blog.ai.rag.rerank.workspace-id` | `ALIYUN_WORKSPACE_ID` | 否，空值可禁用 rerank 或走 fallback |

### 5.2 可外置但不是密钥的环境差异项

| 配置键 | 建议环境变量 | 说明 |
|---|---|---|
| `spring.datasource.url` | `DB_URL` | 本地和生产库地址不同 |
| `spring.datasource.username` | `DB_USERNAME` | 可外置 |
| `spring.data.redis.host` | `REDIS_HOST` | 可保留 localhost 默认 |
| `spring.data.redis.port` | `REDIS_PORT` | 可保留 6379 默认 |
| `spring.elasticsearch.uris` | `ES_URIS` | 本地端口可能不同 |
| `github.oauth.client-id` | `GITHUB_OAUTH_CLIENT_ID` | client id 不是 secret，但按环境变化 |
| `github.oauth.redirect-uri` | `GITHUB_OAUTH_REDIRECT_URI` | 本地 / 生产不同 |
| `app.frontend-url` | `APP_FRONTEND_URL` | 本地 / 生产不同 |
| `aliyun.oss.endpoint` | `OSS_ENDPOINT` | 非密钥 |
| `aliyun.oss.bucketName` | `OSS_BUCKET_NAME` | 非密钥 |
| `aliyun.oss.region` | `OSS_REGION` | 非密钥 |

## 六、配置文件形态

### 6.1 `application.yml`

保留通用默认值与非敏感默认值：

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/blog_system?...}
    username: ${DB_USERNAME:myRoot}
    password: ${DB_PASSWORD:}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      database: ${REDIS_DATABASE:1}

jwt:
  secret: ${JWT_SECRET:}

github:
  oauth:
    client-id: ${GITHUB_OAUTH_CLIENT_ID:}
    client-secret: ${GITHUB_OAUTH_CLIENT_SECRET:}
    redirect-uri: ${GITHUB_OAUTH_REDIRECT_URI:http://localhost:8080/api/auth/github/callback}
```

注意：示例里 `password: ${DB_PASSWORD:}` 表示没有默认真实值，是否允许启动由启动校验决定。

### 6.2 `config/application.example.yml`（实施定稿名）

新增示例文件，**只放真正需要覆盖的项**——`application.yml` 的开发默认值已经覆盖了本地绝大多数场景，
唯一给不了的是真实密钥：

```yaml
github:
  oauth:
    client-secret: your-github-oauth-client-secret
```

真实的 `config/application.yml` 不提交，加入 `.gitignore`。

启动方式**不变**（不需要激活任何 profile）：

```bash
./mvnw spring-boot:run
```

Spring Boot 会从当前工作目录的 `config/` 目录读取外部配置，其优先级高于 classpath 里的 `application.yml`
（已实测验证，见 §13.3）；该目录位于 `src/main/resources` 之外，不会被打进 jar。

### 6.3 `application-prod.yml`

生产配置只允许环境变量或部署平台注入：

```yaml
jwt:
  secret: ${JWT_SECRET}
```

生产不写 `:default`。缺失时让 Spring 占位符解析失败或由启动校验明确失败。

## 七、启动校验

建议新增一个轻量启动校验组件，例如 `SensitiveConfigStartupValidator`。

校验原则：

1. `prod` profile 下核心配置缺失直接启动失败。
2. `local/dev` profile 下核心配置缺失也不应静默生成固定默认密钥；可以给出清晰报错，引导复制 example。
3. 可选能力按功能决定是否 fail-fast：
   - AI 对话能力启用时，`ALIYUN_API_KEY` 为空应失败或明确禁用 AI。
   - GitHub OAuth 如果当前没有 `enabled` 开关，则缺 `client-secret` 更适合 fail-fast；否则接口会等到用户点击才炸。
   - OSS AccessKey 当前走阿里云 SDK 环境变量，上传文件时才需要。是否启动强校验待讨论。
4. `JWT_SECRET` 至少 32 字符，不能等于明显示例值。
5. 只记录 key 名，不记录 value。

### 待讨论点 A：GitHub OAuth 缺配置时怎么处理

| 选择 | 行为 | 代价 |
|---|---|---|
| A. fail-fast | 缺 `client-secret` 直接启动失败 | 本地不用 GitHub 登录时也要配 |
| B. 加 `github.oauth.enabled=false` | 缺配置时禁用 OAuth 接口 | 多一个开关，代码要处理禁用态 |

建议第一刀选 A，先简单收口；如果本地经常不用 GitHub 登录，再改 B。

### 待讨论点 B：OSS 凭证是否启动校验

当前 `AliyunOSSOperator` 使用 `EnvironmentVariableCredentialsProvider`，凭证不在 yml。  
建议本刀不强制启动校验 OSS 凭证，避免“只是本地跑博客也必须配 OSS”。上传失败时保持现有错误处理。后续若做文件上传工程化，再单独补 OSS 配置校验。

## 八、实施清单

1. 修改 `application.yml`：敏感项改成环境变量占位符，移除真实值。
2. 补齐 `application-prod.yml`：统一生产环境变量命名。
3. 新增 `config/application.example.yml`。
4. `.gitignore` 加 `blog-system/config/application.yml`。（初稿还要求忽略 `.env`——实施时未加：项目没有 dotenv 使用场景，加一条用不上的规则只会让人以为它在用）
5. 新增启动校验组件，覆盖 `JWT_SECRET`、数据库密码、Redis 密码、GitHub secret、AI key。
6. 更新 README 或 `AGENTS.md` 本地启动说明，列出最小必需环境变量。
7. 补单测：校验缺配置、短 JWT secret、示例值拒绝、正常配置通过。

## 九、验证

### 自动化

```bash
./mvnw test -Dtest=SensitiveConfigStartupValidatorTests
./mvnw test
```

如果全量测试受真实 LLM / 外部服务影响，应至少跑配置相关单测、登录相关测试和应用上下文启动测试。

### 手测

1. 缺 `JWT_SECRET` 启动，确认报错明确且不打印 secret。
2. 设置本地完整变量后启动成功。
3. 普通账号登录成功。
4. GitHub OAuth URL 生成正常。
5. AI 聊天能正常调用，确认 `ALIYUN_API_KEY` 未出现在日志。

## 十、风险与边界

| 风险 | 处理 |
|---|---|
| 本地启动成本上升 | 提供 `config/application-local.example.yml` 和启动说明 |
| 测试环境缺环境变量导致失败 | 测试类使用 `@TestPropertySource` 或测试 profile 注入假值 |
| 把 secret 打进日志 | 校验只输出配置 key，不输出 value |
| GitHub OAuth 本地不用但被迫配置 | 先记录为待讨论；必要时加 `enabled` 开关 |
| 配置改动影响生产 | 生产配置保持 env-only，部署前列检查清单 |

## 十一、验收标准

- 仓库内不再出现真实 JWT secret、GitHub client-secret、DB/Redis 密码。
- 本地可按文档启动，不需要改 Git 跟踪文件。
- 缺关键配置时失败位置清晰。
- 日志不输出敏感值。
- 业务主路径不变：登录、AI 聊天、GitHub OAuth、文章上传不因配置外置改变语义。

## 十二、给评审的入口

1. 本地环境是否接受“缺核心配置也 fail-fast”？如果觉得太硬，需要引入 `config/application-local.yml` 的明确启动方式。
2. GitHub OAuth 缺配置是 fail-fast，还是加 `github.oauth.enabled` 禁用？
3. OSS 凭证是否要在启动时校验？我倾向暂不校验。
4. `jwt.secret` 是否强制 32 字符以上？我倾向强制。
5. 这刀是否顺手更新 `capability-audit` 状态？我倾向实施完成后再改，避免文档先行写成已完成。

---

## 十三、实施记录（2026-09-14）

### 13.1 落地范围

| 文件 | 改动 |
|---|---|
| `blog-system/src/main/resources/application.yml` | 6 项敏感配置占位符化 + 顶部"配置分级"说明块 |
| `blog-system/src/main/resources/application-prod.yml` | 补 Redis / GitHub OAuth / `app.frontend-url` 外置 + 完整环境变量清单 |
| `blog-system/src/main/java/.../component/SensitiveConfigStartupValidator.java` | 新增：启动校验 + 配置来源日志 |
| `blog-system/src/test/java/.../SensitiveConfigStartupValidatorTests.java` | 新增：7 个用例 |
| `blog-system/config/application.example.yml` | 新增：本地私有配置模板 |
| `blog-system/.gitignore` | 加 `config/application.yml` |
| `blog-system/src/test/resources/application.properties` | 测试侧固定 `jwt.secret` |
| `deploy/blog-system.env.example` | 重写：按"必须 / 按需 / 可选"分组列出完整清单 |
| `CLAUDE.md` / `AGENTS.md` | 本地启动章节补敏感配置说明（两份同步） |
| `docs/capability-audit-2026-08-06.md` | 安全章节状态 + 优先级表第 1 项改已完成 |

### 13.2 与设计稿的偏离（5 处，评估后主动调整）

| 设计稿 | 实际做法 | 理由 |
|---|---|---|
| §6.1 用 `${DB_PASSWORD:}` **空默认值** | `${DB_PASSWORD:123456}` **保留开发默认值** | 空默认值会让本地启动变成"报错再查文档"；而"开发值静默上线"在 prod 侧已被无默认值机制堵死，本地再严没有额外收益 |
| §7.2 本地/dev 缺核心配置也 fail-fast | 本地只记录，**prod 才抛异常** | 项目有 31 个 `@SpringBootTest`，本地 fail-fast 会让它们全部启动失败；且本地默认值的实际风险为 0 |
| §6.2 `config/application-local.yml` + `-Dspring-boot.run.profiles=local` | `config/application.yml`（**无 profile**） | 利用 Spring 原生外部配置优先级（`file:./config/` 高于 classpath），**不改动现有启动命令**。已实测验证（见 13.3） |
| §5.2 列出 11 项"可外置"的环境差异项 | **只做 §5.1 的 6 项敏感项** | 这刀的边界是"敏感配置"，非敏感项（DB_URL / ES_URIS / OSS_ENDPOINT 等）等真有环境差异需求时再外置，避免范围膨胀 |
| §7 校验器覆盖全部存在性校验 | 存在性交给 **Spring 占位符**（声明式），校验器只做**质量**（长度 / 弱值 / 来源日志） | 职责不重叠：同一个问题只在报错一处，排查时不用读两段堆栈 |

### 13.3 验证结果

**自动化**

- `SensitiveConfigStartupValidatorTests`：**7/7 通过**
- `BlogSystemApplicationTests`（Spring 上下文装配）：**1/1 通过**——确认校验器在真实容器中正常、`github.client-secret` 为空时仍能注入

**核心假设实测**（这两条不成立则方案为空，故单独验证）

1. **`config/application.yml` 优先级高于 classpath** — 用临时探针测试断言（不看日志，因 Windows 控制台中文乱码不可靠）：放入 `config/application.yml` 后，`@Value("${jwt.secret}")` 取到的确实是外部文件的值。**通过**，验证后文件已删。
2. **prod 缺配置启动失败** — 实跑 `./mvnw spring-boot:run -Dspring-boot.run.profiles=prod`（不设任何环境变量）：
   ```
   Caused by: org.springframework.util.PlaceholderResolutionException:
     Could not resolve placeholder 'JWT_SECRET' in value "${JWT_SECRET}"
   [INFO] BUILD FAILURE
   ```
   **通过**——fail-fast 是真实生效的，不是文档承诺。

**未验证**：生产环境的"配齐变量后启动成功"路径（本地无生产库与生产域名），留待部署时确认。

### 13.4 上线前必做（部署清单）

服务器 `/etc/blog-system/blog-system.env` 需**新增**以下项，否则 prod 启动即失败：

```
GITHUB_OAUTH_CLIENT_ID
GITHUB_OAUTH_CLIENT_SECRET
GITHUB_OAUTH_REDIRECT_URI      # 须与 GitHub 应用后台登记的 callback URL 一致
APP_FRONTEND_URL               # 前端域名，OAuth 登录后跳回这里
REDIS_PASSWORD                 # 生产 Redis 设了密码则必填
```

**顺带修掉一个线上 bug**：`app.frontend-url` 原先在 `application.yml` 写死 `http://localhost:5173` 且生产从未覆盖，而 `OAuthController` 用它做登录后的重定向——即线上点 GitHub 登录会被跳回用户自己的电脑。本次外置后，只要 `APP_FRONTEND_URL` 填对，这个问题一并消除。

### 13.5 已知边界

| 边界 | 说明 |
|---|---|
| 生产 Redis 密码未提供时**不报错** | Redis 客户端懒连接，启动不失败、运行时才连不上。缓解：启动日志会打印 `redis.password=未配置`，据此判断。不改成 fail-fast 是因为"Redis 无密码"本身是合法状态 |
| 已泄漏的 client-secret **未吊销** | 决策：跳过。`application.yml` 里的旧值仍存在于 git 历史中，外置只阻止继续泄漏，不撤销已发生的 |
| 本地 `config/application.yml` 优先级**高于 jar 内全部配置** | 包括 `application-prod.yml`。这是 Spring 外部配置的既定语义（人为指定 > 打包内），本地若用它跑 prod profile 会出现非预期覆盖 |
| 测试也会加载 `config/application.yml`（若存在） | 当前仓库无此文件，不受影响；开发者本地建了之后，测试会读到它的值 |
