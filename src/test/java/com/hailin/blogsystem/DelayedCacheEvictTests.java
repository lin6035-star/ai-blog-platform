package com.hailin.blogsystem;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hailin.blogsystem.component.DelayedCacheEvictor;
import com.hailin.blogsystem.config.BlogCacheProperties;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.ai.rag.ArticleRagSyncService;
import com.hailin.blogsystem.entity.ArticleLikes;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.mapper.ArticleLikesMapper;
import com.hailin.blogsystem.service.ArticleLikesService;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.task.ArticleViewCountSyncTask;
import com.hailin.blogsystem.utils.UserContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存**延迟双删**的行为验证（真 Redis + H2 内存库 MySQL 兼容模式）。
 *
 * 设计稿：docs/redis/delayed-double-delete-design.md
 *
 * <p><b>三组验证各自要证什么</b>：
 * <ul>
 *   <li><b>A 组</b>——组件机制本身：重放真的发生了、开关关掉就退化、回滚不排重放、积压上限会丢弃</li>
 *   <li><b>B 组</b>——<b>残留窗口真的被关上</b>：用一个可控挂起的 MyBatis 拦截器精确复现
 *       "读请求在提交前查库、在删除之后才写回"，然后看第二次删除是否清掉它。
 *       这是唯一能证明这一刀有效的用例，其余都是辅助</li>
 *   <li><b>C 组</b>——接线验证：单测证明不了"业务路径真的接上了"（第四刀的教训），
 *       所以走真实的 {@code likeArticle} 端到端跑一遍</li>
 * </ul>
 *
 * <p>测试方法<b>不能</b>带 {@code @Transactional}——外层事务会把被测方法的事务一起包住，
 * {@code afterCommit} 永不触发（同 {@code AfterCommitCacheEvictTests}）。
 */
@SpringBootTest
class DelayedCacheEvictTests {

    private static final String PROBE_TITLE_PREFIX = "delayed_evict_probe_";
    /** 等在途重放完成用的余量：延迟本身 + 调度开销 */
    private static final long REPLAY_MARGIN_MS = 200L;
    /** B 组专用的放大延迟，理由见 {@code readerCrossingCommitIsOverwrittenByReplay} */
    private static final long READER_CROSSING_DELAY_MS = 2_000L;

    @Autowired
    private DelayedCacheEvictor delayedCacheEvictor;

    @Autowired
    private ArticlesService articlesService;

    @Autowired
    private ArticleLikesService articleLikesService;

    @Autowired
    private ArticleLikesMapper articleLikesMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BlogCacheProperties cacheProperties;

    @Autowired
    private BlockingProbe blockingProbe;

    /** 索引同步是 @Async + 真实 ES：测的是缓存时机，不该被它拖下水 */
    @MockBean
    private ArticleRagSyncService articleRagSyncService;

    /** 关掉定时任务，否则它会在用例中途动浏览量 key */
    @MockBean
    private ArticleViewCountSyncTask articleViewCountSyncTask;

    private TransactionTemplate transactionTemplate;
    private Long probeId;
    private String probeTitle;
    private String detailKey;
    private String lockKey;
    /** 配置里的原始延迟值：用例改动后必须还原，否则会污染共享上下文里的其它用例 */
    private long originalDelayMs;

    /** 探针用户：**刻意不复用 1L**——用户态集合缓存是按 userId 共享的，用真实用户会污染开发环境 */
    private static final Long PROBE_USER_ID = 999_999L;

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        blockingProbe.reset();
        originalDelayMs = cacheProperties.getDoubleDelete().getDelayMs();
        cacheProperties.getDoubleDelete().setEnabled(true);

        Articles article = new Articles();
        probeTitle = PROBE_TITLE_PREFIX + System.nanoTime();
        article.setTitle(probeTitle);
        article.setSummary("probe");
        article.setContent("probe content");
        article.setAuthorId(PROBE_USER_ID);
        article.setCategoryId(1L);
        article.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        article.setViewCount(0);
        article.setLikeCount(0);
        article.setFavoriteCount(0);
        article.setCommentCount(0);
        article.setShareCount(0);
        article.setPublishedAt(LocalDateTime.now());
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articlesService.save(article);

        probeId = article.getId();
        detailKey = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + probeId;
        lockKey = RedisConstants.CACHE_LOCK_ARTICLE_DETAIL_KEY_PREFIX + probeId;

        cleanCacheKeys();
    }

    @AfterEach
    void tearDown() {
        cacheProperties.getDoubleDelete().setEnabled(true);
        cacheProperties.getDoubleDelete().setMaxPending(4096);
        cacheProperties.getDoubleDelete().setDelayMs(originalDelayMs);
        blockingProbe.releaseAll();

        cleanCacheKeys();
        if (probeId != null) {
            articleLikesMapper.delete(new LambdaQueryWrapper<ArticleLikes>()
                    .eq(ArticleLikes::getArticleId, probeId));
            articlesService.removeById(probeId);
        }
        UserContext.clear();
    }

    private void cleanCacheKeys() {
        stringRedisTemplate.delete(detailKey);
        stringRedisTemplate.delete(lockKey);
        stringRedisTemplate.delete(RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + PROBE_USER_ID);
        stringRedisTemplate.delete(RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + PROBE_USER_ID
                + RedisConstants.USER_SET_LOADED_SUFFIX);
        stringRedisTemplate.opsForZSet().remove(RedisConstants.ARTICLE_HOT_KEY, String.valueOf(probeId));
    }

    private long delayMs() {
        return cacheProperties.getDoubleDelete().getDelayMs();
    }

    // ==================== A 组：组件机制 ====================

    /**
     * 核心机制：提交后删一次，D 毫秒后再删一次。
     *
     * <p>用"单线程重排事件顺序"精确建模竞争读者的迟到写回——不需要真的并发。
     */
    @Test
    void evictsAgainAfterDelay() throws InterruptedException {
        stringRedisTemplate.opsForValue().set(detailKey, "v1");

        delayedCacheEvictor.evict(detailKey);

        // 无事务调用方 → 第一次删除就地同步执行
        assertThat(stringRedisTemplate.hasKey(detailKey)).isFalse();
        assertThat(delayMs()).isPositive();

        // 模拟竞争读者：在删除之后把旧值写回（就是残留窗口的产物）
        stringRedisTemplate.opsForValue().set(detailKey, "stale");

        Thread.sleep(delayMs() + REPLAY_MARGIN_MS);

        assertThat(stringRedisTemplate.hasKey(detailKey))
                .as("延迟重删应清掉这次迟到写回")
                .isFalse();
    }

    /** pattern 版本同形；顺带证明通配边界没有被放大（不匹配的 key 不许被删） */
    @Test
    void patternEvictReplays() throws InterruptedException {
        String pattern = "test:delayed-evict:pattern:";
        String k1 = pattern + "1";
        String k2 = pattern + "2";
        String other = "test:delayed-evict:other";

        stringRedisTemplate.opsForValue().set(k1, "v");
        stringRedisTemplate.opsForValue().set(k2, "v");
        stringRedisTemplate.opsForValue().set(other, "v");

        try {
            delayedCacheEvictor.evictPattern(pattern + "*");
            assertThat(stringRedisTemplate.hasKey(k1)).isFalse();
            assertThat(stringRedisTemplate.hasKey(k2)).isFalse();
            assertThat(stringRedisTemplate.hasKey(other)).as("不匹配的 key 不该被删").isTrue();

            stringRedisTemplate.opsForValue().set(k1, "stale");
            stringRedisTemplate.opsForValue().set(k2, "stale");
            stringRedisTemplate.opsForValue().set(other, "barely-touched");

            Thread.sleep(delayMs() + REPLAY_MARGIN_MS);

            assertThat(stringRedisTemplate.hasKey(k1)).isFalse();
            assertThat(stringRedisTemplate.hasKey(k2)).isFalse();
            assertThat(stringRedisTemplate.hasKey(other))
                    .as("重放也不许越界删不匹配的 key")
                    .isTrue();
        } finally {
            stringRedisTemplate.delete(k1);
            stringRedisTemplate.delete(k2);
            stringRedisTemplate.delete(other);
        }
    }

    /** 开关关掉 = 退化成"提交后删一次"。同一个用例也是反向验证的常驻版本 */
    @Test
    void disabledSwitchDegradesToSingleDelete() throws InterruptedException {
        cacheProperties.getDoubleDelete().setEnabled(false);

        stringRedisTemplate.opsForValue().set(detailKey, "v1");
        delayedCacheEvictor.evict(detailKey);

        assertThat(stringRedisTemplate.hasKey(detailKey)).isFalse();

        stringRedisTemplate.opsForValue().set(detailKey, "stale");
        Thread.sleep(delayMs() + REPLAY_MARGIN_MS);

        assertThat(stringRedisTemplate.hasKey(detailKey))
                .as("开关关掉后不该再重放，旧值会一直留到 TTL——这正是加这一刀之前的行为")
                .isTrue();
    }

    /** 事务回滚：既不删、也不排重放（数据没变，重放纯属多余） */
    @Test
    void rollbackNeitherDeletesNorSchedulesReplay() throws InterruptedException {
        stringRedisTemplate.opsForValue().set(detailKey, "v1");

        transactionTemplate.executeWithoutResult(status -> {
            delayedCacheEvictor.evict(detailKey);
            assertThat(stringRedisTemplate.hasKey(detailKey)).as("事务内不该删").isTrue();
            status.setRollbackOnly();
        });

        assertThat(stringRedisTemplate.hasKey(detailKey)).as("回滚后也不该删").isTrue();

        Thread.sleep(delayMs() + REPLAY_MARGIN_MS);

        assertThat(stringRedisTemplate.hasKey(detailKey)).as("回滚不该留下重放任务").isTrue();
    }

    /**
     * 积压上限：超限时丢弃本次重放，且**只丢超出的那个**。
     *
     * <p>调度器底层是 {@code DelayedWorkQueue}，无界且 JDK 层面不提供背压，所以上限得自己兜。
     * 丢任务是安全降级——本功能丢了只是退回加这一刀之前的行为。
     */
    @Test
    void dropsReplayWhenPendingExceedsLimit() throws InterruptedException {
        cacheProperties.getDoubleDelete().setMaxPending(1);
        String k1 = detailKey;
        String k2 = RedisConstants.ARTICLE_LIST_KEY_PREFIX + "probe:pending";

        stringRedisTemplate.opsForValue().set(k1, "v");
        stringRedisTemplate.opsForValue().set(k2, "v");

        try {
            delayedCacheEvictor.evict(k1);   // 占满唯一名额
            delayedCacheEvictor.evict(k2);   // 超限 → 丢弃重放

            assertThat(stringRedisTemplate.hasKey(k1)).isFalse();
            assertThat(stringRedisTemplate.hasKey(k2)).isFalse();

            stringRedisTemplate.opsForValue().set(k1, "stale");
            stringRedisTemplate.opsForValue().set(k2, "stale");

            Thread.sleep(delayMs() + REPLAY_MARGIN_MS);

            assertThat(stringRedisTemplate.hasKey(k1)).as("名额内的重放照常执行").isFalse();
            assertThat(stringRedisTemplate.hasKey(k2)).as("超限的那个被丢弃").isTrue();
        } finally {
            stringRedisTemplate.delete(k2);
        }
    }

    // ==================== B 组：真实并发复现残留窗口 ====================

    /**
     * <b>本方案的核心用例</b>：精确复现"读请求跨越写事务"，验证第二次删除关掉了它。
     *
     * <p>时序全程确定性，不靠赛跑——读线程被 MyBatis 拦截器**精确挂在**
     * 「已从库里拿到旧标题、尚未写回缓存」的位置。
     *
     * <pre>
     * 1. 缓存清空 → 读线程 R 进 miss 路径，查库拿到旧标题后被挂住
     * 2. 主线程改标题 → 提交 → 第一次删除（此刻缓存为空）
     * 3. 放行 R → R 用**旧标题**写回缓存   ← 这就是残留窗口的产物
     * 4. 断言①：缓存里是旧标题（窗口复现成功）
     * 5. 等 D 毫秒
     * 6. 断言②：缓存被清空（第二次删除覆盖了迟到写回）
     * </pre>
     *
     * <p>反向验证：把 {@code enabled} 关掉或把 {@code delay-ms} 调大，<b>断言②会精确变红</b>。
     */
    @Test
    void readerCrossingCommitIsOverwrittenByReplay() throws Exception {
        stringRedisTemplate.delete(detailKey);
        stringRedisTemplate.delete(lockKey);

        // ⚠️ 把延迟临时放大：断言① 要观察的是「迟到写回之后、重放之前」那个中间状态。
        // 默认 200ms 太紧——放行读线程 + join 的调度开销就可能把它耗光，于是重放先跑完、
        // 断言① 看到的是空缓存（那是**实现正确、测试写错了**）。
        cacheProperties.getDoubleDelete().setDelayMs(READER_CROSSING_DELAY_MS);

        blockingProbe.arm();

        AtomicReference<ArticleDetailVO> readerResult = new AtomicReference<>();
        Thread reader = new Thread(() ->
                readerResult.set(articlesService.getPublicArticleById(probeId, "9.9.9.9")),
                "delayed-evict-reader");
        reader.start();

        assertThat(blockingProbe.awaitReaderInside(5, TimeUnit.SECONDS))
                .as("读线程应被拦截器挂在「已拿到旧值、尚未写缓存」处")
                .isTrue();

        String newTitle = probeTitle + "_renamed";
        transactionTemplate.executeWithoutResult(status ->
                articlesService.updateArticleTitle(probeId, probeTitle, newTitle, PROBE_USER_ID));

        blockingProbe.releaseReader();
        reader.join(5_000);
        assertThat(reader.isAlive()).as("读线程应在放行后正常结束").isFalse();
        assertThat(readerResult.get()).isNotNull();

        // 断言①：窗口被精确复现——缓存里是读线程写回的**旧**标题
        String cached = stringRedisTemplate.opsForValue().get(detailKey);
        assertThat(cached).as("读线程应已把旧值写回缓存").isNotNull();
        assertThat(cached).contains(probeTitle);
        assertThat(cached).doesNotContain(newTitle);

        Thread.sleep(READER_CROSSING_DELAY_MS + REPLAY_MARGIN_MS);

        // 断言②：第二次删除清掉了这次迟到写回
        assertThat(stringRedisTemplate.hasKey(detailKey))
                .as("延迟重删应覆盖「读请求跨提交写回旧值」这个残留窗口")
                .isFalse();
    }

    // ==================== C 组：接线验证 ====================

    /**
     * 端到端版：真实的 {@code likeArticle}（{@code @Transactional}）+ 真实缓存 key。
     *
     * <p>一例覆盖三件事：<b>第五刀遗留的裸删 bug 已修</b>（事务内不再删）、
     * 提交后才删、以及延迟重放真的接上了。
     */
    @Test
    void likeArticleEvictsAfterCommitAndReplays() throws InterruptedException {
        UserContext.set(PROBE_USER_ID);

        stringRedisTemplate.opsForValue().set(detailKey, "{\"id\":1}");

        transactionTemplate.executeWithoutResult(status -> {
            articleLikesService.likeArticle(probeId);
            assertThat(stringRedisTemplate.hasKey(detailKey))
                    .as("修复前这里是裸删，事务内就把缓存删了——必然留下「删完到提交之间读旧值回填」的窗口")
                    .isTrue();
        });

        assertThat(stringRedisTemplate.hasKey(detailKey)).as("提交后才删").isFalse();

        // 模拟竞争读者的迟到写回
        stringRedisTemplate.opsForValue().set(detailKey, "{\"stale\":true}");
        Thread.sleep(delayMs() + REPLAY_MARGIN_MS);

        assertThat(stringRedisTemplate.hasKey(detailKey)).as("点赞路径也接上了延迟重删").isFalse();
    }

    // ==================== 探针与拦截器 ====================

    /**
     * 可控挂起的探针：只挂**第一个**进入 ArticlesMapper 查询的线程，其余线程不受影响。
     *
     * <p>这个"只挂第一个"很关键——主线程随后要发起写事务（也会查 articles 表），
     * 如果不加区分会把自己也挂死。
     */
    static class BlockingProbe {

        private final AtomicBoolean armed = new AtomicBoolean(false);

        // ⚠️ 刻意不是 final：每个用例都要一对**全新的** latch。
        // 初版写成 final 并在 reset() 里 countDown 复用，结果 releaseReader 在 @BeforeEach
        // 就被归零——读线程"挂起"时 await 立即返回、根本没停住，主线程于是跑到了它后面，
        // 断言① 看到的自然是被主线程删过的空缓存。**挂起类测试的 latch 必须每例重建**。
        private volatile CountDownLatch readerInside = new CountDownLatch(1);
        private volatile CountDownLatch releaseReader = new CountDownLatch(1);

        void arm() {
            armed.set(true);
        }

        void reset() {
            armed.set(false);
            readerInside = new CountDownLatch(1);
            releaseReader = new CountDownLatch(1);
        }

        /** 仅供 tearDown 兜底释放：解掉可能还挂着的线程。**不重置 latch** */
        void releaseAll() {
            releaseReader.countDown();
        }

        /** @return true 表示这次调用该被挂起（且已完成占位，后续调用不再挂） */
        boolean tryHook(String statementId) {
            if (!armed.get() || !statementId.contains("ArticlesMapper")) {
                return false;
            }
            return armed.compareAndSet(true, false);
        }

        void signalInside() {
            readerInside.countDown();
        }

        boolean awaitReaderInside(long timeout, TimeUnit unit) throws InterruptedException {
            return readerInside.await(timeout, unit);
        }

        void releaseReader() {
            releaseReader.countDown();
        }

        void awaitRelease(long timeout, TimeUnit unit) throws InterruptedException {
            releaseReader.await(timeout, unit);
        }
    }

    @Intercepts({
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
            @Signature(type = Executor.class, method = "query",
                    args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                            org.apache.ibatis.cache.CacheKey.class, org.apache.ibatis.mapping.BoundSql.class})
    })
    static class BlockingInterceptor implements Interceptor {

        private final BlockingProbe probe;

        BlockingInterceptor(BlockingProbe probe) {
            this.probe = probe;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            // 挂在 proceed 之后：此刻语句已经执行完（旧值已拿到），但调用方还没写缓存
            if (probe.tryHook(((MappedStatement) invocation.getArgs()[0]).getId())) {
                probe.signalInside();
                probe.awaitRelease(5, TimeUnit.SECONDS);
            }
            return result;
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(Properties properties) {
            // 无配置项
        }
    }

    @TestConfiguration
    static class BlockingProbeConfig {
        @Bean
        BlockingProbe blockingProbe() {
            return new BlockingProbe();
        }

        @Bean
        Interceptor delayedEvictBlockingInterceptor(BlockingProbe blockingProbe) {
            return new BlockingInterceptor(blockingProbe);
        }
    }
}
