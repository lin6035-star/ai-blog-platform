package com.hailin.blogsystem.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.hailin.blogsystem.ai.index.ArticleIndexMutationPublisher;
import com.hailin.blogsystem.ai.rag.ArticleRagIndexService;
import com.hailin.blogsystem.component.DelayedCacheEvictor;
import com.hailin.blogsystem.component.CacheTtlSupport;
import com.hailin.blogsystem.component.RedisKeyScanner;
import com.hailin.blogsystem.component.UserActivityTracker;
import com.hailin.blogsystem.component.UserSetCache;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.constants.RedisConstants;
import com.hailin.blogsystem.entity.*;
import com.hailin.blogsystem.entity.dto.ArticlesDTO;
import com.hailin.blogsystem.entity.vo.PageVO;
import com.hailin.blogsystem.mapper.*;
import com.hailin.blogsystem.search.ArticleSearchService;
import com.hailin.blogsystem.service.ArticlesService;
import com.hailin.blogsystem.service.FollowService;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.utils.CursorCodec;
import com.hailin.blogsystem.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArticlesServiceImpl extends ServiceImpl<ArticlesMapper, Articles> implements ArticlesService {

    /** 详情缓存互斥锁 TTL：短 TTL——持锁进程崩溃后最多 5 秒自动释放（对比 Workflow 锁的 120s） */
    private static final long DETAIL_LOCK_TTL_SECONDS = 5L;
    /** 未抢到锁时的短轮询：50ms × 10 ≈ 500ms 上限，超时 fail-open 自己查库 */
    private static final long DETAIL_LOCK_POLL_INTERVAL_MILLIS = 50L;
    private static final int DETAIL_LOCK_POLL_MAX_ATTEMPTS = 10;
    private static final String DETAIL_NULL_NOT_FOUND_VALUE = RedisConstants.CACHE_NULL_VALUE + ":not_found";
    private static final String DETAIL_NULL_INVISIBLE_PREFIX = RedisConstants.CACHE_NULL_VALUE + ":invisible:";

    /** 释放锁：只有 token 匹配才删——否则慢请求会删掉别人的锁，互斥形同虚设 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
            """,
            Long.class
    );

    /** 浏览量自增 + 兜底 TTL（一次 Lua 完成，避免 INCR 与 EXPIRE 之间的故障与竞态）。
     *  判 TTL &lt; 0 而非 INCR == 1：后者补不上"上线前就存在的无 TTL 旧 key"。 */
    private static final DefaultRedisScript<Long> VIEW_INCR_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('INCR', KEYS[1])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """,
            Long.class
    );

    /** 原子取走浏览量增量：GET + DEL 一次 Lua（GETDEL 需 Redis 6.2+，这里兼容更低版本） */
    private static final DefaultRedisScript<String> VIEW_TAKE_SCRIPT = new DefaultRedisScript<>(
            """
            local value = redis.call('GET', KEYS[1])
            if value then
                redis.call('DEL', KEYS[1])
            end
            return value
            """,
            String.class
    );

    /** 记录独立访客 + 兜底 TTL（一次 Lua 完成）。
     *  与 VIEW_INCR_SCRIPT 同构、理由也相同：PFADD 与 EXPIRE 分两次调用，中间故障会留下
     *  **无 TTL 的 key**——而"允许丢失的数据都有 TTL"是 volatile-lru 能正常淘汰的前提。
     *  判 TTL &lt; 0 而非"首次 PFADD"：补得上任何原因造成的无 TTL 残留。 */
    private static final DefaultRedisScript<Long> UV_ADD_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('PFADD', KEYS[1], ARGV[1])
            if redis.call('TTL', KEYS[1]) < 0 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 1
            """,
            Long.class
    );

    /**
     * 热度榜原子替换：EXISTS 检查 + RENAME + EXPIRE 一次完成。
     *
     * **为什么不能分三步写**：
     * 1. `RENAME` 的源 key 不存在会直接抛错（`ERR no such key`）——两个执行者同时重建时，
     *    先完成的那个已经把临时 key RENAME 走了，后一个就会炸。生产只有定时任务一个调用方
     *    （调度池 size=1）撞不上，但测试 / 手工触发会成为第二个调用方——这个错就是这么暴露的
     * 2. `EXPIRE` 必须跟在 `RENAME` **之后**：`RENAME` 会把目标 key 的 TTL 换成源 key 的，
     *    而临时 key 每轮都是新建、无 TTL，写在前面等于白写
     *
     * 打包之后这两个约束都不再由调用方保证。
     *
     * KEYS[1] = 临时榜，KEYS[2] = 正式榜；ARGV[1] = TTL(秒)
     *
     * @return 1 = 替换成功；0 = 临时榜不存在（已有并发重建完成本轮），本次跳过
     */
    private static final DefaultRedisScript<Long> RENAME_HOT_RANK_SCRIPT = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
                return 0
            end
            redis.call('RENAME', KEYS[1], KEYS[2])
            redis.call('EXPIRE', KEYS[2], ARGV[1])
            return 1
            """,
            Long.class
    );

    private final UsersMapper usersMapper;
    private final CategoryMapper categoryMapper;
    private final ArticleLikesMapper articleLikesMapper;
    private final ArticleFavoritesMapper articleFavoritesMapper;
    private final CommentsMapper commentsMapper;
    /** 索引变更统一入口：use-mq=true 落 outbox，false 走 after-commit 旧链路 */
    private final ArticleIndexMutationPublisher articleIndexMutationPublisher;
    /** 只包住「文章写库 + 落索引事件」的事务，不把缓存/活跃度统计卷进来 */
    private final TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper;

    private final StringRedisTemplate stringRedisTemplate;

    private final CacheTtlSupport cacheTtlSupport;

    private final RedisKeyScanner redisKeyScanner;

    private final UserSetCache userSetCache;
    private final DelayedCacheEvictor delayedCacheEvictor;
    private final UserActivityTracker userActivityTracker;
    /** 站内搜索：keyword 非空时走 ES 全文检索，ES 不可用则降级回原本的 LIKE。 */
    private final ArticleSearchService articleSearchService;

    /** 首页「关注」流要用它拿「我关注了谁」。方向是 Articles → Follow，不会成环 */
    private final FollowService followService;

    /** 排序档位：按浏览量。 */
    private static final String SORT_RECOMMEND = "recommend";

    /**
     * 排序档位：只看我关注的人。
     *
     * <p>排序键与 `latest` **完全相同**（`published_at DESC, id DESC`）——多个作者的文章
     * 因此天然按时间交替出现，而不是「看完一个人的全部再看下一个」；游标机制也原样复用。
     */
    private static final String SORT_FOLLOWING = "following";

    @Override  //1.获取公开文章列表（游标分页 / keyset，语义见接口 Javadoc）
    public PageVO<ArticleDetailVO> getArticles(String cursor, Long pageSize, String keyword, Long categoryId, String sort) {

        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword;
        String normalizedSort = normalizeSort(sort);
        boolean firstPage = cursor == null || cursor.isBlank();

        // 「关注」流：先把作者范围取出来。**空集合必须在这里短路**——
        // 放进 SQL 会生成 `IN ()`（语法错误），或者被当成"无条件"，
        // 那样这个 tab 就变成全站最新了，用户还以为自己在看关注流。
        // 未登录也走这条：首页游客可见，那个 tab 显示空状态即可，不该报错。
        List<Long> authorScope = null;
        if (SORT_FOLLOWING.equals(normalizedSort)) {
            authorScope = followService.listFollowingIds(UserContext.get());
            if (authorScope.isEmpty()) {
                return emptyPage();
            }
        }
        /*
         * 单页上限。游标分页下 pageSize **直接就是 LIMIT**，客户端可控的 LIMIT 必须有天花板。
         * （offset 分页同样暴露，只是那时 page 和 pageSize 一起传进来更显眼。）
         * 50 沿用项目里同类接口的口径，见 AgentRunInspectionService。
         */
        long safeSize = pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, 50L);

        // 有关键词 → 优先走 ES 全文检索（标题 + 摘要 + 正文，带相关性排序与高亮）。
        // 无关键词 → 走下面的 DB + 缓存路径。
        //
        // 为什么用「keyword 非空」而不是「整体替换」：无关键词的列表页是**热路径且有缓存**，
        // 走 ES 只会平白多一次网络往返；而关键词查询本来就不走缓存（缓存 key 里没有 keyword）。
        //
        // ⚠️ 关注流**不进 ES**：它的排序档位只有推荐/最新两套，塞 following 进去没有对应语义。
        // 它退回 DB 的 `title LIKE + author_id IN`——关注流本来就是个小集合，够用。
        if (normalizedKeyword != null && !SORT_FOLLOWING.equals(normalizedSort)) {
            PageVO<ArticleDetailVO> esResult =
                    searchByEs(cursor, safeSize, normalizedKeyword, categoryId, normalizedSort);
            if (esResult != null) {
                return esResult;
            }
            // ES 不可用：**不报错**，继续往下走 LIKE —— 搜索是增强功能，挂了不该让列表页也打不开
            log.warn("[SEARCH] ES 不可用，降级到 LIKE 查询：keyword={}", normalizedKeyword);
        }

        /*
         * 只缓存第一页，深页不缓存。
         *
         * 游标分页下深页**缓存不了**：cursor 是每个调用方各自的位置，变不出稳定的 key。
         * 但也不需要——keyset 是"索引定位 + 取 N 行"，代价与翻到多深无关，回源不疼。
         *
         * 顺带把老问题消掉了：原来 key 是 `page:N`，翻多少页就有多少 key，
         * 而任何一次文章变更都要 `SCAN article:list:*` 全删 + 延迟重删。
         * 现在 key 数量是「分类数 × 排序数」，与翻页深度无关。
         *
         * ⚠️ 关注流**不进缓存**：key 里没有 userId，而它是每个用户各自的私有视图——
         * 缓存了就是 A 用户读到 B 用户的关注流（不是性能问题，是数据泄露）。
         * 它低频、私有，且 keyset 本身便宜，不缓存不亏。
         */
        boolean cacheable = normalizedKeyword == null && firstPage
                && !SORT_FOLLOWING.equals(normalizedSort);
        String cacheKey = cacheable
                ? buildArticleListCacheKey(safeSize, categoryId, normalizedSort)
                : null;

        if (cacheable) {
            PageVO<ArticleDetailVO> cachedPage = getArticleListFromCache(cacheKey);

            if (cachedPage != null) {
                fillArticleLiked(cachedPage.getList());
                fillArticleFavorited(cachedPage.getList());
                fillArticleViewCount(cachedPage.getList());
                return cachedPage;
            }
        }

        LambdaQueryChainWrapper<Articles> query = lambdaQuery()
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .like(normalizedKeyword != null, Articles::getTitle, normalizedKeyword)
                .eq(categoryId != null, Articles::getCategoryId, categoryId)
                // 关注流：只要这些作者的文章。authorScope 为 null 时这个条件整个不生效
                .in(authorScope != null, Articles::getAuthorId, authorScope);

        applySortAndCursor(query, normalizedSort, firstPage ? null : cursor);

        // searchCount=false：游标分页不需要 total，省掉一条 COUNT(*)。
        // 这里恒取"第 1 页"——位移由游标条件表达，查询里不再出现 OFFSET。
        Page<Articles> pageResult = query.page(new Page<>(1, safeSize, false));

        List<Articles> records = pageResult.getRecords();
        List<ArticleDetailVO> list = records.stream()
                .map(ArticleDetailVO::from)
                .toList();
        fillArticleMeta(list);

        PageVO<ArticleDetailVO> result = new PageVO<>(list, null, null, safeSize);
        result.setNextCursor(buildNextCursor(records, safeSize, normalizedSort));

        if (cacheable) {
            saveArticleListToCache(cacheKey, result);
        }

        fillArticleLiked(list);
        fillArticleFavorited(list);
        fillArticleViewCount(list);

        return result;
    }

    /**
     * 排序档位归一化。
     *
     * <p>白名单而不是直接用入参：未知值静默退回「最新」，
     * 免得客户端传来的任意字符串进入后面的分支判断。
     */
    private static String normalizeSort(String sort) {
        if (SORT_RECOMMEND.equals(sort)) {
            return SORT_RECOMMEND;
        }
        if (SORT_FOLLOWING.equals(sort)) {
            return SORT_FOLLOWING;
        }
        // 未知值静默退回「最新」——白名单而不是直接用入参，免得把客户端字符串带进分支判断
        return "latest";
    }

    /** 空页。关注流在「未登录 / 一个都没关注」时直接返回它，不查库 */
    private static PageVO<ArticleDetailVO> emptyPage() {
        PageVO<ArticleDetailVO> empty = new PageVO<>();
        empty.setList(List.of());
        empty.setNextCursor(null);
        return empty;
    }

    /**
     * 排序 + 游标条件。
     *
     * <p><b>为什么用行值比较 {@code (view_count, id) < (?, ?)}，而不是
     * {@code view_count < ? OR (view_count = ? AND id < ?)}</b>：两者语义完全等价，
     * 但 MySQL 8.0 对行值构造器有专门的 range 优化，能直接走
     * {@code (status, deleted_at, view_count, id)} 定位；OR 展开则要优化器自己推，
     * 更容易退化成"扫一段再回表过滤"。**改之前先在真实数据上 EXPLAIN，别凭写法判断。**
     *
     * <p>{@code id} 必须参与比较，它保证**全序**：只按浏览量取游标时，
     * 浏览量并列的那几行会被整段跳过或重复。
     *
     * <p>⚠️ {@code following} **故意落进**下面 `latest` 这一支：它的排序键与「最新」完全相同
     * （{@code published_at DESC, id DESC}），差别只在调用方多带了一个
     * {@code author_id IN (...)} 过滤。所以游标格式天然通用、不需要第三套编解码，
     * 多个作者的文章也因此按时间交替出现。
     */
    private void applySortAndCursor(LambdaQueryChainWrapper<Articles> query, String sort, String cursor) {
        if (SORT_RECOMMEND.equals(sort)) {
            query.orderByDesc(Articles::getViewCount).orderByDesc(Articles::getId);
            if (cursor != null) {
                List<String> parts = CursorCodec.decode(cursor);
                query.apply("(view_count, id) < ({0}, {1})",
                        CursorCodec.toLong(parts.get(0)), CursorCodec.toLong(parts.get(1)));
            }
            return;
        }

        query.orderByDesc(Articles::getPublishedAt).orderByDesc(Articles::getId);
        if (cursor != null) {
            List<String> parts = CursorCodec.decode(cursor);
            query.apply("(published_at, id) < ({0}, {1})",
                    toLocalDateTime(CursorCodec.toLong(parts.get(0))), CursorCodec.toLong(parts.get(1)));
        }
    }

    /**
     * 生成下一页游标：**只在本页取满时才给**，取不满就说明后面没有了。
     *
     * <p>代价是"最后一页刚好取满"会多发一次请求（拿到空列表 + null 游标）。
     * 用"多取一条"能消掉这一次，但那条多出来的数据要额外剥掉（缓存里也一样），
     * 权衡后选了简单的那侧——前端的"加载失败重试"分支本来就要处理空响应，不引入新状态。
     */
    private String buildNextCursor(List<Articles> records, Long pageSize, String sort) {
        if (records == null || records.isEmpty() || records.size() < pageSize) {
            return null;
        }
        Articles last = records.get(records.size() - 1);

        if (SORT_RECOMMEND.equals(sort)) {
            return CursorCodec.encode(
                    String.valueOf(safe(last.getViewCount())), String.valueOf(last.getId()));
        }

        if (last.getPublishedAt() == null) {
            // 已发布文章理论上都有发布时间。真为空就停在这一页——不要生成一个语义不明的游标，
            // 那会让下一页从头开始，变成"永远翻不到底"
            return null;
        }
        long epochMilli = last.getPublishedAt()
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return CursorCodec.encode(String.valueOf(epochMilli), String.valueOf(last.getId()));
    }

    /**
     * 游标里的 {@code publishedAt} 存的是 **epoch 毫秒**而不是格式化字符串：
     * 字符串带 {@code :} 会和游标的分段分隔符打架，还会引入时区歧义。按系统时区还原，
     * 与写入游标时用的是同一套时区。
     */
    private static LocalDateTime toLocalDateTime(long epochMilli) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneId.systemDefault());
    }

    /**
     * ES 检索路径。**返回 null 表示 ES 不可用**，调用方据此降级到 LIKE。
     *
     * <p>分页方式与 DB 路径一致：ES 侧用 {@code search_after}，游标语义见
     * {@link ArticleSearchService#search}。{@code total} 仍是 ES 的命中总数（只作展示），
     * **不参与"有没有下一页"的判断**——那看 {@code nextCursor}。
     */
    private PageVO<ArticleDetailVO> searchByEs(String cursor, Long pageSize, String keyword,
                                               Long categoryId, String sort) {
        ArticleSearchService.SearchHits hits =
                articleSearchService.search(keyword, categoryId, sort, cursor, pageSize);
        if (hits == null) {
            return null;
        }
        if (hits.articleIds().isEmpty()) {
            return new PageVO<>(List.of(), null, null, pageSize);
        }

        /*
         * ⚠️ 按 id 查库之后**必须按 ES 的顺序重排**：
         * SQL 的 `IN (...)` 返回顺序是**不保证**的（MySQL 通常按主键，但那是实现细节不是承诺），
         * 直接拿来用会让 ES 辛苦算的相关性排序白做——用户看到的是"按 id 排的搜索结果"。
         */
        List<Articles> found = lambdaQuery()
                .in(Articles::getId, hits.articleIds())
                /*
                 * 2026-10-17 上线审计补：ES 索引是**异步**的，"文章已隐藏/删除但索引还没更新"
                 * 的窗口里，只按 id 回查会把隐藏文章透给公开搜索。
                 * 上文说的「检索期 status 过滤」用的是索引里的旧值，靠不住——
                 * 这里按 DB 的权威状态再筛一遍，这才算第二道防线。
                 */
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .isNull(Articles::getDeletedAt)
                .list();
        Map<Long, Articles> byId = new HashMap<>();
        for (Articles a : found) {
            byId.put(a.getId(), a);
        }

        List<ArticleDetailVO> list = new ArrayList<>();
        for (Long id : hits.articleIds()) {
            Articles article = byId.get(id);
            if (article == null) {
                // 索引比库"新"：文章已删但索引还没清干净。跳过，不补空对象
                continue;
            }
            ArticleDetailVO vo = ArticleDetailVO.from(article);
            // 标题高亮与片段高亮分开透出：只命中标题时 fragment 为 null，
            // 由前端两处各自决定回退（见 ArticleSearchService.Highlight）
            ArticleSearchService.Highlight highlight = hits.highlights().get(id);
            if (highlight != null) {
                vo.setHighlightTitle(highlight.title());
                vo.setHighlight(highlight.fragment());
            }
            list.add(vo);
        }

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);
        fillArticleViewCount(list);

        PageVO<ArticleDetailVO> result = new PageVO<>(list, hits.total(), null, pageSize);
        result.setNextCursor(hits.nextCursor());
        return result;
    }

    /**
     * 列表缓存 key。**只给第一页用**——游标分页下深页没有稳定 key 可建，
     * 所以 key 段是 {@code first} 而不是页码。
     */
    private String buildArticleListCacheKey(Long pageSize, Long categoryId, String sort) {
        String categoryPart = categoryId == null ? "all" : String.valueOf(categoryId);
        return RedisConstants.ARTICLE_LIST_KEY_PREFIX
                + "first:size:" + pageSize
                + ":category:" + categoryPart
                + ":sort:" + sort;
    }

    private PageVO<ArticleDetailVO> getArticleListFromCache(String key) {
        String json = null;

        try{
            json = stringRedisTemplate.opsForValue().get(key);
        }catch(Exception e){
            // Redis读取失败不影响公开文章列表，继续查数据库
            return null;
        }

        if(json == null || json.isBlank()){
            return null;
        }

        try{
            return objectMapper.readValue(json,new TypeReference<PageVO<ArticleDetailVO>>(){});
        }catch(JsonProcessingException e){
            try{
                stringRedisTemplate.delete(key);
            }catch(Exception ignored){
                // Redis删除失败不影响，继续查数据库
            }
            return null;
        }
    }

    private void saveArticleListToCache(String key, PageVO<ArticleDetailVO> pageVO) {
        if(pageVO == null){
            return;
        }

        try{
            stringRedisTemplate.opsForValue()
                    .set(
                            key,
                            objectMapper.writeValueAsString(pageVO),
                            cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.ARTICLE_LIST_TTL_MINUTES))
                    );
        }catch(Exception e){
            // 缓存失败不影响文章列表返回
        }
    }

    /** 文章详情缓存的读取结果（三态）——必须区分"空值缓存"与"未命中"，两者后续动作完全不同 */
    private enum CacheReadState { HIT, NULL_CACHED, MISS }

    private record DetailCacheRead(CacheReadState state, ArticleDetailVO value) {
        static DetailCacheRead hit(ArticleDetailVO value) {
            return new DetailCacheRead(CacheReadState.HIT, value);
        }

        static DetailCacheRead nullCached() {
            return new DetailCacheRead(CacheReadState.NULL_CACHED, null);
        }

        static DetailCacheRead miss() {
            return new DetailCacheRead(CacheReadState.MISS, null);
        }
    }

    @Override  //2.获取公开文章详情
    public ArticleDetailVO getPublicArticleById(Long id, String clientIp) {  //2.获取公开文章详情

        DetailCacheRead read = readArticleDetailCache(id);

        DetailCacheRead result = read.state() == CacheReadState.HIT
                ? read
                : loadArticleDetailWithMutex(id);

        if (result.state() != CacheReadState.HIT) {
            // NULL_CACHED = 文章不存在 / 当前身份不可见；MISS = 兜底（正常不会走到）
            return null;
        }

        ArticleDetailVO vo = result.value();

        Long userId = UserContext.get();

        // 活跃统计的对象是"人"，不是"阅读行为"——所以这一句不在下面的分支里：
        // 作者看自己的文章不计浏览量，但他本人**确实今天活跃了**。
        // 未登录时内部直接返回，游客没有 userId 可做 offset
        userActivityTracker.markActiveToday();

        if (userId == null || !userId.equals(vo.getAuthorId())) {
            recordArticleView(id, clientIp);
        }

        fillArticleLiked(vo);
        fillArticleFavorited(vo);
        fillArticleViewCount(List.of(vo));
        // UV 必须**在返回前现算**，不能进详情缓存：缓存 10 分钟内 UV 一直不动，
        // 而它恰恰是"现在有多少人正在看"这个感觉的来源。与 fillArticleViewCount 同一位置
        vo.setUvCount(readTodayUv(id));

        return vo;
    }

    /**
     * 文章详情缓存的**唯一**读入口（三态）。
     *
     * 收口的原因：原先读路径有两条（外层判空值 + getArticleDetailFromCache 内部再读一次），
     * 互斥回源的轮询就是第三条——三条对"空值 / 反序列化失败"的处理必须一致。否则轮询路径
     * 读到空值走反序列化，会触发"解析失败即删 key"，把别人刚写好的空值缓存误删，互斥自我破坏。
     *
     * 空值缓存的身份语义也收口在这里：不存在文章对所有身份都是空；隐藏 / 草稿等不可见文章
     * 会缓存作者与状态，只有"隐藏文章作者本人预览"才绕过空值回源，避免普通登录用户绕开空值缓存。
     */
    private DetailCacheRead readArticleDetailCache(Long id) {
        String key = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id;
        String json;

        try {
            json = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            // Redis 读取失败 → 当作未命中，交给 DB 路径（fail-open）
            return DetailCacheRead.miss();
        }

        if (json == null || json.isBlank()) {
            return DetailCacheRead.miss();
        }

        if (isArticleDetailNullCache(json)) {
            return readArticleDetailNullCache(id, json);
        }

        try {
            return DetailCacheRead.hit(objectMapper.readValue(json, ArticleDetailVO.class));
        } catch (JsonProcessingException e) {
            // 脏数据自愈：留着每次都会解析失败，删掉让下次走 DB 重建
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception ignored) {
                // 删除失败不影响本次返回
            }
            return DetailCacheRead.miss();
        }
    }

    private boolean isArticleDetailNullCache(String json) {
        return RedisConstants.CACHE_NULL_VALUE.equals(json)
                || DETAIL_NULL_NOT_FOUND_VALUE.equals(json)
                || json.startsWith(DETAIL_NULL_INVISIBLE_PREFIX);
    }

    private DetailCacheRead readArticleDetailNullCache(Long id, String value) {
        if (DETAIL_NULL_NOT_FOUND_VALUE.equals(value)) {
            return DetailCacheRead.nullCached();
        }

        if (value.startsWith(DETAIL_NULL_INVISIBLE_PREFIX)) {
            String[] parts = value.substring(DETAIL_NULL_INVISIBLE_PREFIX.length()).split(":", 2);
            if (parts.length == 2) {
                try {
                    Long authorId = Long.valueOf(parts[0]);
                    Integer status = Integer.valueOf(parts[1]);
                    Long viewerId = UserContext.get();
                    if (Objects.equals(status, BlogConstants.ArticlesStatus.HIDDEN)
                            && viewerId != null
                            && viewerId.equals(authorId)) {
                        deleteArticleDetailCache(id);
                        return DetailCacheRead.miss();
                    }
                    return DetailCacheRead.nullCached();
                } catch (NumberFormatException ignored) {
                    // 脏空值缓存，删掉后回源自愈
                    deleteArticleDetailCache(id);
                    return DetailCacheRead.miss();
                }
            }
        }

        if (UserContext.get() == null) {
            return DetailCacheRead.nullCached();
        }
        // 兼容旧版 "__NULL__"：无法判断是否隐藏文章作者，只能回源一次并由新格式重写。
        deleteArticleDetailCache(id);
        return DetailCacheRead.miss();
    }

    /**
     * 互斥回源（防击穿）：同一篇文章只让一个请求查库重建，其余请求短轮询等待缓存被填好；
     * 轮询超时则 fail-open 自己查库——宁可退化，不让人等死。
     *
     * 注意：作者看自己的隐藏文章不会写共享缓存，因此该场景下轮询必然等到超时再回源——
     * 属于预期行为（低频场景），不是缺陷。
     */
    private DetailCacheRead loadArticleDetailWithMutex(Long id) {
        String lockKey = RedisConstants.CACHE_LOCK_ARTICLE_DETAIL_KEY_PREFIX + id;
        String token = UUID.randomUUID().toString();

        boolean locked;
        try {
            locked = Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                    .setIfAbsent(lockKey, token, DETAIL_LOCK_TTL_SECONDS, TimeUnit.SECONDS));
        } catch (Exception e) {
            // 锁不可用不能挡住用户看文章：直接回源
            return loadDetailFromDb(id);
        }

        if (!locked) {
            DetailCacheRead polled = pollArticleDetailCache(id);
            if (polled.state() != CacheReadState.MISS) {
                return polled;
            }
            // 轮询超时：持锁请求可能失败了，自己回源（fail-open）
            return loadDetailFromDb(id);
        }

        try {
            return loadDetailFromDb(id);
        } finally {
            releaseLock(lockKey, token);
        }
    }

    /**
     * 未抢到锁时的短轮询（50ms × 10 ≈ 500ms 上限）。
     * 必须走统一读入口——轮询路径若自己反序列化，会把空值缓存当脏数据删掉。
     */
    private DetailCacheRead pollArticleDetailCache(Long id) {
        for (int i = 0; i < DETAIL_LOCK_POLL_MAX_ATTEMPTS; i++) {
            try {
                Thread.sleep(DETAIL_LOCK_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return DetailCacheRead.miss();
            }

            DetailCacheRead read = readArticleDetailCache(id);
            if (read.state() != CacheReadState.MISS) {
                // HIT = 别人填好了详情；NULL_CACHED = 持锁者确认了不可见（登录用户已在读入口转成 MISS）
                return read;
            }
        }
        return DetailCacheRead.miss();
    }

    /** 查 DB 并把结果转成读结果：共享缓存与空值缓存的写入已内聚在 getArticleDetailFromDb */
    private DetailCacheRead loadDetailFromDb(Long id) {
        ArticleDetailVO vo = getArticleDetailFromDb(id);
        return vo == null ? DetailCacheRead.nullCached() : DetailCacheRead.hit(vo);
    }

    /** 释放锁：Lua 校验 token，防止慢请求删掉别人的锁 */
    private void releaseLock(String lockKey, String token) {
        try {
            stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        } catch (Exception e) {
            // 释放失败由锁 TTL 兜底，不影响本次返回
        }
    }

    /** 浏览量 + 独立访客：一次浏览记两项统计。
     *  两项都是**可接受丢失的近似数据**，任何一项失败都不影响详情返回（外层统一兜底） */
    private void recordArticleView(Long id, String clientIp) {
        try {
            stringRedisTemplate.execute(
                    VIEW_INCR_SCRIPT,
                    Collections.singletonList(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + id),
                    String.valueOf(TimeUnit.MINUTES.toSeconds(RedisConstants.ARTICLE_VIEW_TTL_MINUTES))
            );

            stringRedisTemplate.opsForZSet()
                    .incrementScore(RedisConstants.ARTICLE_HOT_KEY,
                            String.valueOf(id),
                            RedisConstants.ARTICLE_VIEW_HOT_SCORE);

            recordUniqueVisitor(id, clientIp);
        } catch (Exception e) {
            // Redis统计失败不影响文章详情返回
        }
    }

    /**
     * 记录独立访客（HyperLogLog）。
     *
     * **身份取法**：登录用户按 userId、游客按 IP，加前缀区分（`u:123` / `ip:1.2.3.4`）——
     * 不加前缀的话，"userId = 1 的用户"和"IP 恰好是 1"会被 HLL 当成同一个人。
     *
     * **口径本身是近似的**，两层误差叠加：
     * - 身份层：NAT 后面一群人算一个（偏低）、动态 IP 一个人算多个（偏高）
     * - 算法层：HyperLogLog 自身 **0.81% 标准误差**
     *
     * **UV 是趋势指标，不是精确值**。要精确就得存全量集合，而那个内存代价
     * （1 亿 UV 的 Set ≈ 800MB）正是这里用 HLL（固定 12KB）的原因。
     */
    private void recordUniqueVisitor(Long articleId, String clientIp) {
        Long userId = UserContext.get();
        String identity = userId != null
                ? "u:" + userId
                : (clientIp == null || clientIp.isBlank() ? null : "ip:" + clientIp);
        if (identity == null) {
            return;   // 两个身份都拿不到（理论上只在非 Web 线程发生），这次不计
        }

        stringRedisTemplate.execute(
                UV_ADD_SCRIPT,
                Collections.singletonList(buildUvKey(articleId)),
                identity,
                String.valueOf(TimeUnit.DAYS.toSeconds(RedisConstants.ARTICLE_UV_TTL_DAYS))
        );
    }

    /** UV key 按天分：article:uv:{articleId}:{yyyyMMdd}（与活跃统计的日期格式保持一致） */
    private String buildUvKey(Long articleId) {
        return RedisConstants.ARTICLE_UV_KEY_PREFIX + articleId + ":"
                + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** 读今日 UV。失败返回 0——UV 只是展示项，不能因为它让整个详情页失败 */
    private int readTodayUv(Long articleId) {
        try {
            Long size = stringRedisTemplate.opsForHyperLogLog().size(buildUvKey(articleId));
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            return 0;
        }
    }


    @Override  //3.获取我自己的文章详情
    public ArticleDetailVO getArticlesById(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }


        ArticleDetailVO articlesVO = new ArticleDetailVO();

        BeanUtil.copyProperties(articles,articlesVO);

        return articlesVO;
    }

    private void fillArticleMeta(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleMeta(List.of(article));
    }

    private void fillArticleMeta(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        List<Long> authorIds = articles.stream()
                .map(ArticleDetailVO::getAuthorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Long> categoryIds = articles.stream()
                .map(ArticleDetailVO::getCategoryId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        Map<Long, Users> usersById = authorIds.isEmpty()
                ? Map.of()
                : usersMapper.selectBatchIds(authorIds)
                .stream()
                .collect(Collectors.toMap(Users::getId, Function.identity()));
        Map<Long, Category> categoriesById = categoryIds.isEmpty()
                ? Map.of()
                : categoryMapper.selectBatchIds(categoryIds)
                .stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));

        for (ArticleDetailVO article : articles) {
            Users author = usersById.get(article.getAuthorId());
            if (author != null) {
                article.setAuthorName(author.getNickname());
                // 头像与昵称同源（同一个 author 对象），不需要额外查库
                article.setAvatarUrl(author.getAvatarUrl());
            }

            Category category = categoriesById.get(article.getCategoryId());
            if (category != null) {
                article.setCategoryName(category.getName());
            }
        }
    }

    //redis化
    private void fillArticleLiked(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        Long currentUserId = UserContext.get();
        if (currentUserId == null) {
            return;
        }

        String key = RedisConstants.ARTICLE_LIKED_USER_KEY_PREFIX + currentUserId;
        // 三态：null = 未加载或状态不可信（下面回源）；空 Set = 确认没点过赞；非空 = 命中
        Set<String> likedIdStrings = userSetCache.readIfLoaded(key);

        if(likedIdStrings == null){
            List<ArticleLikes> likes = articleLikesMapper.selectList(
                    new LambdaQueryWrapper<ArticleLikes>()
                            .eq(ArticleLikes::getUserId, currentUserId));

            likedIdStrings = likes.stream()
                    .map(like -> String.valueOf(like.getArticleId()))
                    .collect(Collectors.toSet());

            userSetCache.markLoaded(key, likedIdStrings);
        }

        Set<Long> likedArticleIds = likedIdStrings.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        for (ArticleDetailVO article : articles) {
            article.setLiked(likedArticleIds.contains(article.getId()) ? 1 : 0);
        }
    }

    private void fillArticleLiked(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleLiked(List.of(article));
    }

    private void fillArticleFavorited(List<ArticleDetailVO> articles) {
        if (articles == null || articles.isEmpty()) {
            return;
        }

        Long currentUserId = UserContext.get();
        if (currentUserId == null) {
            return;
        }

        List<Long> articleIds = articles.stream()
                .map(ArticleDetailVO::getId)
                .filter(Objects::nonNull)
                .toList();

        if (articleIds.isEmpty()) {
            return;
        }

        String key = RedisConstants.ARTICLE_FAVORITED_USER_KEY_PREFIX + currentUserId;
        // 三态：null = 未加载或状态不可信（下面回源）；空 Set = 确认没收藏过；非空 = 命中
        Set<String> favoritedIdStrings = userSetCache.readIfLoaded(key);

        if(favoritedIdStrings == null){
            List<ArticleFavorites> favorites = articleFavoritesMapper.selectList(
                    new LambdaQueryWrapper<ArticleFavorites>()
                            .eq(ArticleFavorites::getUserId, currentUserId)
            );

            favoritedIdStrings = favorites.stream()
                    .map(favorite -> String.valueOf(favorite.getArticleId()))
                    .collect(Collectors.toSet());

            userSetCache.markLoaded(key, favoritedIdStrings);
        }

        Set<Long> favoritedArticleIds = favoritedIdStrings.stream()
                .map(Long::valueOf)
                .collect(Collectors.toSet());

        for (ArticleDetailVO article : articles) {
            article.setFavorited(favoritedArticleIds.contains(article.getId()) ? 1 : 0);
        }
    }

    private void fillArticleFavorited(ArticleDetailVO article) {
        if (article == null) {
            return;
        }
        fillArticleFavorited(List.of(article));
    }


    @Override  //4.创建文章
    public Long writeArticle(ArticlesDTO articlesDTO) {
        Articles articles = new Articles();
        BeanUtil.copyProperties(articlesDTO,articles);

        articles.setAuthorId(UserContext.get());
        articles.setCreatedAt(LocalDateTime.now());
        articles.setUpdatedAt(LocalDateTime.now());
        articles.setPublishedAt(null);
        syncPublishedAt(articles);

        Long articleId = transactionTemplate.execute(status -> {
            save(articles);
            // 索引事件与文章写同事务：草稿也发事件，消费端回查发现未发布 → 删索引，天然正确
            articleIndexMutationPublisher.articleChanged(articles.getId());
            return articles.getId();
        });

        if(Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED)){
            deleteArticleListCache();
        }
        return articleId;
    }


    @Override  //5.更新自己的文章
    public void updateArticle(Long id, ArticlesDTO articlesDTO) {

        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        LocalDateTime previousPublishedAt = articles.getPublishedAt();
        BeanUtil.copyProperties(articlesDTO,articles);

        articles.setUpdatedAt(LocalDateTime.now());
        articles.setAuthorId(UserContext.get());
        articles.setPublishedAt(previousPublishedAt);
        syncPublishedAt(articles);

        transactionTemplate.executeWithoutResult(status -> {
            updateById(articles);
            articleIndexMutationPublisher.articleChanged(id);
        });

        evictArticleCaches(id);
    }


    /**
     * V3.4 Agent 受控写（UPDATE_ARTICLE_TITLE）专用：只改自己文章的标题。
     * 不走 updateArticle（全量 BeanUtil.copyProperties 会把 DTO 未传字段置 null）。
     * 归属校验用显式 userId（对齐学习域 service 模式，Agent 确认链路可测）。
     * 旧值条件更新 = 轻量 CAS：WHERE title = expectedOldTitle 影响 0 行 → 标题在提案后被并发修改
     * （如用户编辑器另存），本次改名基于的旧标题已失效 → 拒绝不覆盖，用户需重新发起提案。
     * （Articles 无 @Version；本方法标题条件更新是并发防护的最后一层，主窗口由 confirm 端 stale 校验拦截）
     */
    @Override
    @Transactional
    public void updateArticleTitle(Long id, String expectedOldTitle, String newTitle, Long userId) {
        Articles articles = getById(id);
        if (articles == null) {
            throw new IllegalArgumentException("未找到该博文");
        }
        if (!articles.getAuthorId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该文章");
        }

        boolean updated = lambdaUpdate()
                .eq(Articles::getId, id)
                .eq(Articles::getAuthorId, userId)
                .eq(Articles::getTitle, expectedOldTitle)
                .set(Articles::getTitle, newTitle)
                .set(Articles::getUpdatedAt, LocalDateTime.now())
                .update();
        if (!updated) {
            throw new IllegalArgumentException("文章标题已变化，请重新发起修改");
        }

        evictArticleCaches(id);
        articleIndexMutationPublisher.articleChanged(id);  // 状态取自更新前行：已发布→重刷（含标题）
    }

    /**
     * V3.7 Agent 受控写（HIDE_ARTICLE / PUBLISH_ARTICLE）专用：原子条件状态更新。
     * 不直接复用 hideArticle/publishArticle（无条件 set status，confirm 前置校验到执行之间有 TOCTOU——
     * run 状态 CAS 只挡同 run 并发，挡不住文章被其他请求改状态）。
     * expectedStatus 前置进 WHERE（动作前置可从方向推导：HIDE 前置 PUBLISHED / PUBLISH 前置 HIDDEN），
     * 0 行 = 提案后状态已被并发修改 → 拒绝不覆盖。成功后按 targetStatus 对齐 hideArticle/publishArticle 副作用。
     */
    @Override
    @Transactional
    public void updateArticleVisibility(Long id, Integer expectedStatus, Integer targetStatus, Long userId) {
        Articles articles = getById(id);
        if (articles == null) {
            throw new IllegalArgumentException("未找到该博文");
        }
        if (!articles.getAuthorId().equals(userId)) {
            throw new IllegalArgumentException("无权操作该文章");
        }

        boolean updated;
        if (Objects.equals(targetStatus, BlogConstants.ArticlesStatus.PUBLISHED)) {
            // 公开：publishedAt=now（与编辑器「重新发布」一致——首次发布的语义时间被重置，属现有行为）
            updated = lambdaUpdate()
                    .eq(Articles::getId, id)
                    .eq(Articles::getAuthorId, userId)
                    .eq(Articles::getStatus, expectedStatus)
                    .set(Articles::getStatus, targetStatus)
                    .set(Articles::getPublishedAt, LocalDateTime.now())
                    .set(Articles::getUpdatedAt, LocalDateTime.now())
                    .update();
        } else {
            updated = lambdaUpdate()
                    .eq(Articles::getId, id)
                    .eq(Articles::getAuthorId, userId)
                    .eq(Articles::getStatus, expectedStatus)
                    .set(Articles::getStatus, targetStatus)
                    .set(Articles::getUpdatedAt, LocalDateTime.now())
                    .update();
        }
        if (!updated) {
            throw new IllegalArgumentException("文章状态已变化，请重新发起修改");
        }

        evictArticleCaches(id);
        articleIndexMutationPublisher.articleChanged(id);  // 消费端/旧链路统一按回查后的状态决定 index/delete
    }


    @Override  //6.删除自己的文章
    public void deleteArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw new IllegalArgumentException("未找到该博文");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        transactionTemplate.executeWithoutResult(status -> {
            removeById(id);
            articleIndexMutationPublisher.articleChanged(id);
        });

        evictArticleCaches(id);
    }


    @Override  //7.隐藏自己的文章
    public void hideArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw  new IllegalArgumentException("该文章不存在!");
        }

        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }

        articles.setStatus(BlogConstants.ArticlesStatus.HIDDEN);
        articles.setUpdatedAt(LocalDateTime.now());

        transactionTemplate.executeWithoutResult(status -> {
            updateById(articles);
            articleIndexMutationPublisher.articleChanged(id);
        });

        evictArticleCaches(id);
    }


    @Override  //8.发布自己的文章
    public void publishArticle(Long id) {
        Articles articles = getById(id);
        if(articles == null){
            throw  new IllegalArgumentException("该文章不存在!");
        }
        Long userId = UserContext.get();
        if(!articles.getAuthorId().equals(userId)){
            throw new IllegalArgumentException("无权操作该文章");
        }


        articles.setStatus(BlogConstants.ArticlesStatus.PUBLISHED);
        articles.setUpdatedAt(LocalDateTime.now());
        articles.setPublishedAt(LocalDateTime.now());

        transactionTemplate.executeWithoutResult(status -> {
            updateById(articles);
            articleIndexMutationPublisher.articleChanged(id);
        });

        evictArticleCaches(id);

        // 发布是强活跃信号（比浏览更能说明"这个人在用这个站"），与浏览、评论一起构成活跃口径
        userActivityTracker.markActiveToday();
    }


    @Override  //9.获取热度前十的文章
    public PageVO<ArticleDetailVO> getHotArticles(Long page, Long pageSize) {
        // 1. 计算 Redis ZSET 的起止下标
        long start = (page - 1) * pageSize;
        long end = start + pageSize - 1;
        // 2. 从Redis取出热门文章的id（用reverseRange，分数从高到低）
        Set<String> hotArticleIdsString = null;

        try{
            hotArticleIdsString = stringRedisTemplate.opsForZSet()
                    .reverseRange(RedisConstants.ARTICLE_HOT_KEY, start, end);
        }
        catch(Exception e){
            // Redis读取失败，下面走 DB 兜底
        }

        // 3. Redis没数据就兜底查数据库viewCount
        if(hotArticleIdsString == null || hotArticleIdsString.isEmpty()){
            Page<Articles> pageResult = lambdaQuery()
                    .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
                    .orderByDesc(Articles::getViewCount)
                    .page(new Page<>(page,pageSize));

            List<ArticleDetailVO> list = pageResult.getRecords().stream()
                    .map(ArticleDetailVO::from)
                    .toList();

            fillArticleMeta(list);
            fillArticleLiked(list);
            fillArticleFavorited(list);
            fillArticleViewCount(list);

            return new PageVO<>(list, pageResult.getTotal(), page, pageSize);
        }

        // 4. Redis 有数据就按 id 查数据库
        List<Long> hotArticleIds = hotArticleIdsString.stream()
                .map(Long::valueOf)
                .toList();  //String id 转 Long id
        //5. 按 Redis 顺序组装 VO
        List<Articles> articles = lambdaQuery().in(Articles::getId, hotArticleIds)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();
        //6. 转 Map，再按 Redis 顺序组装  in查出来的数据顺序不可靠
        Map<Long, Articles> articlesMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));
        List<ArticleDetailVO> list = hotArticleIds.stream()
                .map(articlesMap::get)
                .filter(Objects::nonNull)
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);
        fillArticleViewCount(list);

        Long total = null;

        try{
            total = stringRedisTemplate.opsForZSet()
                    .zCard(RedisConstants.ARTICLE_HOT_KEY);
        }
        catch (Exception e){
            total = (long) list.size();
        }

        return new PageVO<>(
                list,
                total == null ? 0 : total,
                page,
                pageSize
        );
    }


    @Override  //10.默认加载：他发布的文章
    public PageVO<ArticleDetailVO> getPublicUserArticles(Long id,Long page,Long pageSize) {
        Page<Articles> pageResult = lambdaQuery()
                .eq(Articles::getAuthorId,id)
                .eq(Articles::getStatus,BlogConstants.ArticlesStatus.PUBLISHED)
                .page(new Page<>(page,pageSize));

        List<ArticleDetailVO> list = pageResult.getRecords()
                .stream()
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);
        fillArticleViewCount(list);

        return new PageVO<>(
                list,
                pageResult.getTotal(),
                page,
                pageSize
        );
    }

    @Override  //11.查看他喜欢的文章
    public PageVO<ArticleDetailVO> getPublicUserLiked(Long id, Long page, Long pageSize) {

        Page<ArticleLikes> articleLikesPage = articleLikesMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ArticleLikes>()
                        .eq(ArticleLikes::getUserId, id)
                        .orderByDesc(ArticleLikes::getCreateTime));
        //拿到这一页的文章id
        List<Long> articleIds = articleLikesPage.getRecords().stream()
                .map(ArticleLikes::getArticleId)
                .toList();
        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }
        //再查文章
        List<Articles> articles = lambdaQuery().in(Articles::getId, articleIds)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();
        //in 查出来的顺序不一定等于 articleIds 的顺序，所以要转 Map，再按 articleIds 顺序组装
        Map<Long,Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = articleIds.stream()
                .map(articleMap::get)
                .map(ArticleDetailVO::from)
                .filter(Objects::nonNull)
                .toList();
        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                articleLikesPage.getTotal(),
                page,
                pageSize
        );
    }

    @Override  //12.查看他收藏的文章
    public PageVO<ArticleDetailVO> getPublicUserFavorited(Long id, Long page, Long pageSize) {

        Page<ArticleFavorites> articleFavoritessPage = articleFavoritesMapper.selectPage(new Page<>(page, pageSize),
                new LambdaQueryWrapper<ArticleFavorites>()
                        .eq(ArticleFavorites::getUserId, id)
                        .orderByDesc(ArticleFavorites::getCreateTime));
        //获取该页文章的id
        List<Long> articleIds = articleFavoritessPage.getRecords().stream()
                .map(ArticleFavorites::getArticleId)
                .toList();

        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }
        //再查文章
        List<Articles> articles = lambdaQuery().in(Articles::getId, articleIds)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();

        Map<Long, Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = articleIds.stream()
                .map(articleMap::get)
                .filter(Objects::nonNull)
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                articleFavoritessPage.getTotal(),
                page,
                pageSize
        );

    }

    @Override  //13.查看他评论过的文章
    public PageVO<ArticleDetailVO> getPublicCommented(Long id, Long page, Long pageSize) {
        List<ArticleComments> comments = commentsMapper.selectList(
                new LambdaQueryWrapper<ArticleComments>()
                        .eq(ArticleComments::getUserId,id)
                        .orderByDesc(ArticleComments::getCreatedAt)
        );

        List<Long> articleIds = comments.stream()
                .map(ArticleComments::getArticleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (articleIds.isEmpty()) {
            return new PageVO<>(List.of(), 0L, page, pageSize);
        }

        int from = (int) ((page - 1) * pageSize);
        int to = Math.min(from + pageSize.intValue(), articleIds.size());

        if (from >= articleIds.size()) {
            return new PageVO<>(List.of(), (long) articleIds.size(), page, pageSize);
        }

        List<Long> pageArticleIds = articleIds.subList(from, to);

        List<Articles> articles = lambdaQuery()
                .in(Articles::getId, pageArticleIds)
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();

        Map<Long, Articles> articleMap = articles.stream()
                .collect(Collectors.toMap(Articles::getId, Function.identity()));

        List<ArticleDetailVO> list = pageArticleIds.stream()
                .map(articleMap::get)
                .filter(Objects::nonNull)
                .map(ArticleDetailVO::from)
                .toList();

        fillArticleMeta(list);
        fillArticleLiked(list);
        fillArticleFavorited(list);

        return new PageVO<>(
                list,
                (long) articleIds.size(),
                page,
                pageSize
        );
    }

    @Override  //14.转发文章
    public void shareArticle(Long id) {
        lambdaUpdate()
                .eq(Articles::getId,id)
                .setSql("share_count = share_count + 1")
                .update();

        deleteArticleDetailCache(id);
    }


    private void syncPublishedAt(Articles articles) {
        if (Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED)
                && articles.getPublishedAt() == null) {
            articles.setPublishedAt(LocalDateTime.now());
        }
    }

    //关于redis存储浏览量
    private void fillArticleViewCount(List<ArticleDetailVO> articles){
        if(articles == null || articles.isEmpty()){
            return;
        }

        String key;
        for(ArticleDetailVO article : articles){
            key = RedisConstants.ARTICLE_VIEW_KEY_PREFIX + article.getId();
            String redisViewCount = null;

            try{
                redisViewCount = stringRedisTemplate.opsForValue().get(key);
            }catch(Exception e){
                // Redis读取失败时只显示数据库里的浏览量
                continue;
            }

            if(redisViewCount == null){
                continue;
            }

            int baseViewCount = article.getViewCount() == null ? 0 : article.getViewCount();
            article.setViewCount((baseViewCount + Integer.parseInt(redisViewCount)));
        }
    }

    @Override  //将存储在redis的浏览量加入到数据库，改数据库
    public void syncViewCountToDb(){
        // SCAN 游标迭代。原 KEYS 每 30 秒全库阻塞一次 Redis 服务端单线程
        List<String> keys = redisKeyScanner.scan(RedisConstants.ARTICLE_VIEW_KEY_PREFIX + "*");

        if(keys.isEmpty())
            return;

        int dropped = 0;
        for(String key : keys){
            // 先原子取走再更库：中途失败的方向是"丢一次增量"，而不是"key 没删掉→下次重复累加"。
            // SCAN 可能重复返回同一 key——重复时这里返回 null，天然幂等
            String incrementStr = takeViewIncrement(key);
            if(incrementStr == null)
                continue;

            try{
                int increment = Integer.valueOf(incrementStr);
                if(increment <= 0){
                    continue;
                }

                Long articleId = Long.valueOf(key.substring(RedisConstants.ARTICLE_VIEW_KEY_PREFIX.length()));

                lambdaUpdate()
                        .eq(Articles::getId,articleId)
                        .setSql("view_count = view_count + " + increment)
                        .update();
            }catch(Exception e){
                // 单个 key 失败不中断整轮（原先循环体内无容错，一次异常会让后面的 key 全部不同步）
                dropped++;
                log.warn("浏览量同步失败，本次增量丢弃: key={} value={}", key, incrementStr, e);
            }
        }

        if(dropped > 0){
            log.warn("浏览量同步完成，本轮丢弃 {} 个 key 的增量（浏览量是可接受丢失的近似统计）", dropped);
        }
    }

    /** 原子取走浏览量增量：GET + DEL 一次 Lua（不依赖 Redis 6.2+ 的 GETDEL 命令） */
    private String takeViewIncrement(String key){
        try{
            return stringRedisTemplate.execute(VIEW_TAKE_SCRIPT, Collections.singletonList(key));
        }catch(Exception e){
            log.warn("浏览量增量取走失败，跳过该 key: {}", key, e);
            return null;
        }
    }

    @Override  //重建热度榜
    public void rebuildArticleHotRank() {
        List<Articles> articles = lambdaQuery()
                .eq(Articles::getStatus, BlogConstants.ArticlesStatus.PUBLISHED)
                .list();

        //先建临时榜，建完原子替换。原实现是「先 delete 旧榜 → 再逐篇写入」，
        //中间窗口读热度榜会读到空榜
        String tmpKey = RedisConstants.ARTICLE_HOT_KEY + ":rebuilding";

        //清掉上一轮可能残留的临时榜（重建中途失败会留下）
        stringRedisTemplate.delete(tmpKey);

        if(articles.isEmpty()){
            //空榜边界：没有已发布文章时临时 key 不会被创建，不能 RENAME 一个不存在的 key
            stringRedisTemplate.delete(RedisConstants.ARTICLE_HOT_KEY);
            return;
        }

        // 批量取浏览量增量：原实现逐篇 GET，N 篇文章 N 次网络往返。
        // MGET 一次拿回全部，不存在的 key 在结果里对应 null（顺序与入参一一对应）
        List<String> viewKeys = articles.stream()
                .map(article -> RedisConstants.ARTICLE_VIEW_KEY_PREFIX + article.getId())
                .toList();
        List<String> viewCounts = stringRedisTemplate.opsForValue().multiGet(viewKeys);

        // 批量写临时榜：原实现逐篇 ZADD，又是 N 次往返。pipeline 把 N 条命令打包成一次往返。
        // 注意 pipeline **不保证原子性**（原子性是 Lua 的职责）——这里也不需要：
        // 临时榜建完才 RENAME，中途状态没有任何读者能看到。
        // 边界：文章数到万级时，MGET 结果与 pipeline 缓冲会一起堆在内存里，届时才需要分批
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (int i = 0; i < articles.size(); i++) {
                Articles article = articles.get(i);
                String redisViewCount = viewCounts == null ? null : viewCounts.get(i);
                int redisViewIncrement = redisViewCount == null ? 0 : Integer.parseInt(redisViewCount);

                double score =
                        safe(article.getViewCount()) * RedisConstants.ARTICLE_VIEW_HOT_SCORE
                                + redisViewIncrement * RedisConstants.ARTICLE_VIEW_HOT_SCORE
                                + safe(article.getLikeCount()) * RedisConstants.ARTICLE_LIKE_HOT_SCORE
                                + safe(article.getFavoriteCount()) * RedisConstants.ARTICLE_FAVORITE_HOT_SCORE
                                + safe(article.getCommentCount()) * RedisConstants.ARTICLE_COMMENT_HOT_SCORE;

                // pipeline 里拿到的是底层连接，不走模板的序列化器——key/value 需自行转字节。
                // StringRedisTemplate 用的 StringRedisSerializer 就是 UTF-8，编码与模板其余部分一致
                connection.zAdd(tmpKey.getBytes(StandardCharsets.UTF_8), score,
                        String.valueOf(article.getId()).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        //检查 + RENAME + 补 TTL 打包成一次原子操作：
        //读方要么看到旧榜、要么看到新榜，不会看到空榜；并发重建也不会撞 RENAME 报错。
        //细节见 RENAME_HOT_RANK_SCRIPT 的注释
        Long replaced = stringRedisTemplate.execute(RENAME_HOT_RANK_SCRIPT,
                List.of(tmpKey, RedisConstants.ARTICLE_HOT_KEY),
                String.valueOf(TimeUnit.HOURS.toSeconds(RedisConstants.ARTICLE_HOT_TTL_HOURS)));

        if(replaced == null || replaced == 0){
            //临时 key 不在 = 已有另一个执行者完成了本轮替换。它写进去的数据和本轮同源，跳过即可
            log.info("热度榜临时 key 不存在，本轮替换跳过（可能已有并发重建完成）");
        }
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }


    //拆一个“查文章基础详情”的方法
    //可见性规则（V3.7 修复：隐藏文章作者本人可看）：PUBLISHED → 任何人可见；
    //HIDDEN → 仅作者可见（共享缓存不写隐藏文章，防游客从缓存读到）；
    //DRAFT → 维持不可见（草稿预览走编辑器）；不存在/不可见 → 写空值缓存防穿透
    private ArticleDetailVO getArticleDetailFromDb(Long id){
        Articles articles = lambdaQuery()
                .eq(Articles::getId,id)
                .one();

        if(articles == null){
            writeNullDetailCache(id, DETAIL_NULL_NOT_FOUND_VALUE);
            return null;
        }

        Long viewerId = UserContext.get();
        boolean published = Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.PUBLISHED);
        boolean hiddenOwner = Objects.equals(articles.getStatus(), BlogConstants.ArticlesStatus.HIDDEN)
                && viewerId != null && viewerId.equals(articles.getAuthorId());
        if(!published && !hiddenOwner){
            writeNullDetailCache(id, buildInvisibleNullCacheValue(articles));
            return null;
        }

        // 详情路径必须用 fromDetail：缓存里的 VO 要带正文，否则读缓存时详情页是空的
        ArticleDetailVO vo = ArticleDetailVO.fromDetail(articles);

        fillArticleMeta(vo);

        if(published){
            saveArticleDetailToCache(id,vo);
        }

        return vo;
    }

    private String buildInvisibleNullCacheValue(Articles articles) {
        return DETAIL_NULL_INVISIBLE_PREFIX + articles.getAuthorId() + ":" + articles.getStatus();
    }

    /** 文章详情空值缓存（防穿透；游客/非作者访问隐藏文章、草稿、不存在时写） */
    private void writeNullDetailCache(Long id, String value){
        try{
            stringRedisTemplate.opsForValue().set(
                    RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id,
                    value,
                    cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.CACHE_NULL_TTL_MINUTES))
            );
        }catch(Exception e){
            // 空值缓存写入失败不影响查询结果
        }
    }

    //写入缓存的方法
    private void saveArticleDetailToCache(Long id,ArticleDetailVO articleDetailVO){
        if(articleDetailVO == null){
            return;
        }

        String key = RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id;

        try{  //注意这里不要抛异常。Redis 缓存失败，最多就是慢一点，不能影响用户看文章
            String json = objectMapper.writeValueAsString(articleDetailVO);
            stringRedisTemplate.opsForValue()
                    .set(
                            key,
                            json,
                            cacheTtlSupport.jitter(Duration.ofMinutes(RedisConstants.ARTICLE_DETAIL_TTL_MINUTES))
                    );
        } catch(Exception e){
            //缓存失败不影响，后面可直接查询数据
        }
    }

    /**
     * 文章元数据变更的统一失效出口：详情 + 列表。
     *
     * **为什么单独收一个方法**：这两行必须成对出现（详情页和列表页都展示文章元数据），
     * 此前分开写重复了 6 处，新增写路径时漏掉一个就是**静默的脏缓存**——直到 TTL 过期都没人发现。
     */
    private void evictArticleCaches(Long id) {
        deleteArticleDetailCache(id);
        deleteArticleListCache();
    }

    /**
     * 文章变更时删除详情缓存。走 {@link DelayedCacheEvictor}：调用方里
     * `updateArticleTitle` / `updateArticleVisibility` 带 `@Transactional`，
     * 事务内删会留下"删完到提交之间读到旧值并回填"的窗口（详见该组件类注释），
     * 它先把删除推到提交后，再延迟重删一次堵住"读请求跨提交写回旧值"的迟到写回。
     */
    private void deleteArticleDetailCache(Long id) {
        delayedCacheEvictor.evict(RedisConstants.ARTICLE_DETAIL_KEY_PREFIX + id);
    }

    private void deleteArticleListCache() {
        // SCAN 游标迭代 + UNLINK 异步回收。原 KEYS 会阻塞 Redis 服务端单线程，
        // 连带卡住同实例上的限流、分布式锁与其它缓存。
        // SCAN 的失败日志在 RedisKeyScanner 内部
        delayedCacheEvictor.evictPattern(RedisConstants.ARTICLE_LIST_KEY_PREFIX + "*");
    }
}
