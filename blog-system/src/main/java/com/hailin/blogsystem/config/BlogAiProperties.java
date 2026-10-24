package com.hailin.blogsystem.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "blog.ai")
public class BlogAiProperties {
    private String systemPrompt;
    private String projectKey = "global";
    private ClassifierEngine classifierEngine = ClassifierEngine.JEV_FAST_PATH;
    private ClassifierMode classifierMode = ClassifierMode.ON_DEMAND;
    private Jev jev = new Jev();
    private Memory memory = new Memory();
    private Rag rag = new Rag();
    private Agent agent = new Agent();
    private RateLimit rateLimit = new RateLimit();
    private Guest guest = new Guest();
    private Inspection inspection = new Inspection();
    private Billing billing = new Billing();
    private Task task = new Task();
    private Thinking thinking = new Thinking();

    /**
     * AI 长任务（Agent / Workflow 编排）的准入与专用线程池。
     *
     * <p>⚠️ {@code workerCount} 是<b>成本闸门</b>不是容量结论：它等于「同时向模型供应商发起的
     * 并发请求数上限」，直接决定花钱速率。定它看的是供应商并发限制 + 成本速率 + HTTP 连接池 +
     * 可接受的排队延迟，<b>不能按 CPU 核数推导</b>——IO 密集任务的瓶颈在外部服务，不在本机。
     */
    @Data
    public static class Task {
        /** 专用池 worker 数。固定 core == max，保证「worker + queue」容量可预测。 */
        private int workerCount = 8;
        /** 有界队列容量。 */
        private int queueCapacity = 32;
        /** 每用户并发上限（硬上限：竞态结果是拒绝，不是多跑一个）。 */
        private int maxConcurrentPerUser = 2;
        private String threadNamePrefix = "ai-task-";
        /** 外层 Task run 停在 RUNNING 多久后才允许补偿巡检接管。 */
        private long handoffRecoveryStaleMinutes = 15;
    }

    /**
     * 分类器 system prompt 的模式（P3 分类器收缩，2026-10-01）。
     *
     * <p>分类器是全站 AI 请求的第一跳，它判错会让整条路走偏，所以它必须有一个**一键回退**：
     * 线上发现分界漂移时改配置重启即可，不需要回滚代码、不需要重新发版。
     *
     * <p>P3 激活后默认使用 {@link #ON_DEMAND}；线上发现分界漂移时显式切回
     * {@link #FULL}，仍能逐字恢复激活前的完整 prompt。
     */
    public enum ClassifierMode {
        /** 19 块全装（原行为，安全兜底） */
        FULL,
        /** 候选 A 的按需装载：三级装载 + fail-open，复合目标信号直接 FULL（见 CandidateA） */
        ON_DEMAND
    }

    /** 分类引擎总开关；与 plus 内部的 Prompt 裁剪模式相互独立。 */
    public enum ClassifierEngine {
        /** 完全沿用 plus 分类器，不发起 Jev 请求。 */
        PLUS_ONLY,
        /** Jev 只处理白名单只读意图，其他情况回退 plus。 */
        JEV_FAST_PATH
    }

    @Data
    public static class Jev {
        private String apiKey;
        private String endpoint = "https://openrouter.ai/api/alpha/decisions";
        /*
         * 超时预算（2026-10-07 调整）：原值 500 / 1200 比实测延迟还紧。
         *
         * 同日线上 12 次成功调用的延迟是 457~1005ms（P50≈600ms），max 离 1200 只差 200ms，
         * 于是三次失败全部是**超时**而非服务端报错：503ms 撞连接超时、1207/1208ms 撞请求超时。
         * 每次超时的代价不是"少一次优化"——是白等一次再回退 plus（plus 单次 7.8~12s）。
         *
         * 放宽到实测 max 的三倍：最坏多等 1.8s，换掉一次 8s 量级的回退。
         */
        private long connectTimeoutMs = 1500;
        private long requestTimeoutMs = 3000;
    }

    @Data
    public static class Memory{
        private boolean enabled = true;
        private int maxMessages = 40;
        private Extraction extraction = new Extraction();
    }

    @Data
    public static class Extraction {
        /** 显式开启后才使用 RocketMQ；默认保留原有 @Async 链路。 */
        private boolean useMq = false;
        private int coreSize = 1;
        private int maxSize = 2;
        private int queueCapacity = 30;
        private String threadNamePrefix = "memory-extract-";
    }

    @Data
    public static class Rag {
        private int topK = 10;
        private double similarityThreshold = 0.55;
        private Corpus corpus = new Corpus();
        private Async async = new Async();
        private Rebuild rebuild = new Rebuild();
        private Rerank rerank = new Rerank();
        private Gate gate = new Gate();
        private Health health = new Health();
        private Keyword keyword = new Keyword();
    }

    /**
     * keyword（词法）检索的参数。
     *
     * <p><b>为什么它和向量那边的 {@code topK} 不是一个量级</b>：这是 **chunk 级**检索，
     * 而同一篇文章的多个 chunk 相关度接近、会**连续排在前面**。原来硬编码 {@code size=5}，
     * 实测问"有没有关于 Java 的文章"时，ES 里明明有 **51 个 chunk / 12 篇**命中，
     * 却只捞回 5 个 chunk——它们几乎全来自同一两篇，于是最终只喂给 LLM 一篇内容，
     * 用户看到的就是"只找到 1 篇、而且没讲全"。
     *
     * <p>所以这里按「**要覆盖多少篇文章**」来配，而不是按"要多少片段"：
     * 先多捞（{@link #fetchSize}），再按 articleId 分组，每组保留前
     * {@link #chunksPerArticle} 个（组内第一个就是该篇最相关的，因为 ES 按分数降序返回），
     * 最后取前 {@link #maxArticles} 篇。
     */
    @Data
    public static class Keyword {
        /** 一次从 ES 捞多少 chunk。要够"覆盖 {@code maxArticles} 篇 × 每篇 {@code chunksPerArticle} 个"再留足余量 */
        private int fetchSize = 60;
        /** 最多保留几篇**文章**（这才是"召回几篇"的真正上限） */
        private int maxArticles = 8;
        /** 每篇文章最多保留几个 chunk */
        private int chunksPerArticle = 2;
    }

    @Data
    public static class Corpus {
        private String articleIndexName = "spring-ai-document-index";
        private String memoryIndexName = "spring-ai-document-index";
        private String embeddingModel = "qwen3.7-text-embedding";
        private int dimensions = 1024;
        /**
         * 文章 corpus 的自动建索引开关。**必须保持 false**：article 侧用的是 alias
         * {@code article_rag_active}，物理索引由 rebuild 流程建，Spring AI 不支持把 alias
         * 当作待建物理索引处理。
         */
        private boolean initializeSchema = true;
        /**
         * 记忆 corpus 的自动建索引开关（与文章独立）。
         *
         * <p><b>为什么默认 true</b>：记忆写的是**物理索引**（默认
         * {@code spring-ai-document-index}），但项目里没有任何重建/初始化流程创建它——
         * 旧环境里它是早期用 initialize-schema=true 自动建过的。换新服务器（ES 数据卷全新）后
         * 这个索引不存在，而 {@code ElasticsearchVectorStore.doAdd()} 第一步就 indexExists()，
         * 缺失时直接抛 {@code IllegalArgumentException("Index not found")}；保存记忆是在
         * {@code @Transactional} 里写 ES 的，异常会让事务回滚 → 前端「保存记忆失败」，
         * 召回侧则被 fail-open 吞掉（AI 静默读不到记忆）。
         *
         * <p><b>测试里必须设 false</b>：{@code src/test/resources/application.properties}
         * 的约定是「测试不依赖真实 ES」，而开了之后 bean 初始化阶段就会发一次 HEAD 探测。
         */
        private boolean memoryInitializeSchema = true;
    }

    @Data
    public static class Rerank {
        private boolean enabled = true;
        private String model = "gte-rerank-v2";
        private String workspaceId;
        private int topN = 5;
        private int maxDocuments = 30;
        private int maxChunksPerArticle = 2;
    }

    @Data
    public static class Gate {
        private boolean enabled = true;
        private double rerankThreshold = 0.75D;
    }

    @Data
    public static class Health {
        private double canaryThreshold = 0.9D;
        private long initialDelayMs = 10_000L;
        private long intervalMs = 300_000L;
    }

    @Data
    public static class Rebuild{
        private boolean enabled = true;
        private List<Long> allowedUserIds = List.of();
    }

    @Data
    public static class Async {
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 100;
        private String threadNamePrefix = "rag-sync-";
    }

    @Data
    public static class Agent{
        private double autoStartConfidenceThreshold = 0.7;
        private int autoStartLimitPerSession = 2;
        private List<String> learningToolWhitelist = List.of("getLearningDashboard");
        private Dashboard dashboard = new Dashboard();
    }
    @Data
    public static class Dashboard{
        private int titleMaxLength = 30;
        private int taskTitleMaxLength = 20;
        private int memoryMaxLength = 80;
        private int memoryLimit = 3;
        private int hintLimit = 3;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private boolean failOpen = false;
        private Limit chat = new Limit(10, 60);
        private Limit workflow = new Limit(3, 600);
        private Limit rag = new Limit(30, 60);
        // 秒杀抢购：比 chat 宽松（网络抖动、手速都会让用户多点几次），
        // 但仍能挡住脚本级频率——真正的防重在更前面（Redis 的 SISMEMBER 一人一单）
        private Limit seckill = new Limit(20, 60);

        /**
         * 全站 AI 请求每分钟上限（所有 AI 入口共享，0 = 不限制）。
         *
         * <p>2026-10-17 上线审计补：分类器在返回 Flux 之前<b>同步</b>跑在 Tomcat 请求线程上
         * （plus 路径实测 7.8~12s），而 AI 长任务准入池只覆盖 Agent/Workflow、不覆盖聊天。
         * 没有全站闸门时，几百个并发慢请求就能把 Tomcat 线程占满，拖垮整站。
         *
         * <p>这是「止血」闸门，不是替代品：真正把分类器移出请求线程是后续独立改动。
         * 秒杀抢购不占这个额度（它是独立桶，且 50 库存的正常抢购就可能超过 120/分钟）。
         */
        private int globalPerMinute = 120;
    }

    /**
     * 游客 AI 额度（2026-10-17 上线审计补）。
     *
     * <p>产品决策：<b>游客 AI 保留、不扣用户账单</b>。但游客没有钱包不等于没有成本——
     * 每次调用都是真金白银付给模型供应商，而改造前游客路径唯一的门禁是「按 IP 5 次/分钟」，
     * 且 IP 取自客户端可伪造的请求头（见 {@code ClientIpUtils} 类注释），实测换头即可无限调用。
     *
     * <p>所以保留游客免费的前提是两道日额度：单 IP 日额度限制单来源，全站日预算兜底
     * 分布式/代理池刷。超出后游客 AI 关闭到次日 0 点，<b>登录用户不受影响</b>。
     */
    @Data
    public static class Guest {
        /** 每个 IP 每日游客 AI 请求上限（0 = 不限制）。默认 10 次/天。 */
        private int dailyLimitPerIp = 10;

        /** 全站游客每日请求总上限（0 = 不限制）。默认 2000 次/天，防分布式刷。 */
        private int globalDailyLimit = 2000;
    }

    @Data
    public static class Limit {
        private int limit;
        private long windowSeconds;

        public Limit() {
        }

        public Limit(int limit, long windowSeconds) {
            this.limit = limit;
            this.windowSeconds = windowSeconds;
        }
    }

    /**
     * V4 第一刀：开发者只读面板（run / step 完整 inspection）的访问控制。
     *
     * 项目无角色体系，沿用 {@link Rag.Rebuild} 的范式：开关 + userId 白名单。
     * 数据范围仍限「当前用户自己的 run」（Service 层归属校验不动）。
     */
    @Data
    public static class Inspection {
        private boolean enabled = true;
        private List<Long> allowedUserIds = List.of();
    }

    /**
     * AI 计费：预扣 / 结算 / 退款。设计稿：docs/redis/钱包与秒杀计划.md §5。
     *
     * <p>钱包本体配置在 {@link BlogWalletProperties}（blog.wallet.*），这里只管 AI 侧的单价与预扣上限。
     * 计费单元是「每次用户可见的 AI 请求」，预扣取该路径的<b>可执行硬上限</b>，
     * 结算按实际用量退差额（结算必须满足 actualCredit &lt;= reservedCredit）。
     */
    @Data
    public static class Billing {
        /** 总开关。关闭时计费埋点整体跳过，不改变调用链行为 */
        private boolean enabled = true;

        /** 内部积分价：每 1000 token 折算多少 credit（结算向上取整，避免小请求被整数除法算成 0） */
        private long creditPer1kTokens = 10;

        /**
         * 普通聊天的输出上限（maxTokens），也是预扣公式的一项。
         *
         * <p>⚠️ 这是<b>计费硬上限</b>，不是"建议长度"：调小会截断长回答，
         * 调大则预扣更保守。改之前先看 {@code AiChatBillingSupport} 的预扣公式。
         */
        private int chatMaxTokens = 4000;

        /**
         * 工具调用的最大轮数。
         *
         * <p>每轮都会把完整上下文重发一遍，token 会成倍增长，所以预扣要按轮数放大。
         */
        private int maxToolRounds = 5;

        /**
         * 各 bizType 的单次预扣硬上限（credit），key = CHAT / AGENT / WORKFLOW_ACTION。
         *
         * <p>⚠️ 必须来自<b>可执行硬上限</b>——最大输入 + maxTokens + 最大工具轮数 / maxSteps。
         * <b>不能取 P95</b>：P95 只覆盖 95% 的请求，第 96 百分位以后照样透支；
         * 更关键的是结算要满足 actualCredit &lt;= reservedCredit，用 P95 会直接破坏这个不变量。
         */
        private Map<String, Long> maxReservePerBizType = new LinkedHashMap<>();

        /**
         * 预扣单的超时释放时间（分钟）。
         *
         * <p>必须大于该路径的最大全链路超时并留余量——这是<b>进程崩溃时的兜底</b>，
         * 正常路径永远走不到它。晚到的结算因状态不再是 RESERVED 必须失败，不能二次扣款。
         */
        private long reserveExpireMinutes = 30;
    }

    /**
     * 思考模式统一策略（2026-10-04）。
     *
     * <p><b>默认全关</b>：对所有走 Spring AI 的 chat 调用注入 {@code enable_thinking=false}。
     * 实测（2026-10-04，qwen3.7-flash）首个可见正文从 <b>13189ms → 1502ms</b>。
     *
     * <p><b>为什么不再用「前缀白名单」</b>：老实现只对 {@code qwen3} / {@code deepseek} 前缀注入，
     * 即<b>默认不关</b>——新接一个模型就悄悄开着思考，而且没人会发现；再加上一层
     * {@code qwen3.7-max} 豁免前缀，把整个 max 系列都放过了，但实测 05-20 / 06-08 两个版本
     * 其实都接受该参数。白名单是人工维护的，会过时，代价是用户白等十几秒。
     *
     * <p>现在反过来：<b>默认注入，确证不接受的才登记豁免</b>。
     */
    @Data
    public static class Thinking {

        /**
         * 总开关。{@code false} = 完全不注入，一键回退（与 {@code classifier-mode} 同一套路）。
         */
        private boolean disable = true;

        /**
         * 确证<b>不接受</b> {@code enable_thinking=false} 的模型前缀，默认空 = 不豁免任何模型。
         *
         * <p>命中后按「<b>省略参数</b>」处理，而不是发 {@code true}——省略即用模型默认，
         * 将来该模型支持关闭时删掉前缀即可回归，不需要改代码逻辑。
         *
         * <p>登记前必须实测：早期 {@code qwen3.7-max-2026-05-17} 确实会返回
         * {@code InvalidParameter: enable_thinking is restricted to True}，但 05-20 / 06-08 已支持。
         * <b>按前缀登记会误伤同系列的其它版本</b>，能用具体版本号就别用系列名。
         */
        private List<String> exemptModels = List.of();
    }

}
