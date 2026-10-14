package com.hailin.blogsystem.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.hailin.blogsystem.constants.BlogConstants;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.utils.CursorCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内搜索：ES 全文检索。
 *
 * <p><b>与 RAG 检索的分工</b>（设计稿 §2）：站内搜索服务**人**（要相关性排序 + 高亮 + 分页），
 * RAG 服务**模型**（要召回率，topK 小、不要高亮）。所以**索引是两套**：
 * RAG 那个是 chunk 级且 mapping 由 Spring AI 自动创建，这里是文章级、自己管 mapping。
 *
 * <p><b>ES 是加速器，不是唯一真相</b>：检索失败一律返回 {@code null}，
 * 由调用方降级回原来的 LIKE 查询。**不要把这个降级分支当死代码删掉**——
 * ES 容器挂了、索引没建好、网络抖一下，都靠它兜住。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleSearchService {

    public static final String INDEX_NAME = "article_search_index";

    /** 高亮片段的长度上限（字符），避免把整段正文塞回前端。 */
    private static final int HIGHLIGHT_FRAGMENT_SIZE = 120;

    private final ElasticsearchClient esClient;

    /**
     * 检索结果：命中的文章 id（**已按相关性排序**）+ 每篇的两处高亮。
     *
     * @param total      ES 命中总数。⚠️ 默认 {@code track_total_hits} 上限是 10000，
     *                   超过之后它会**恒等于 10000**（ES 回的 {@code relation=gte} 被这里丢弃了）。
     *                   分页判断已改用 {@code nextCursor}，所以它不准不影响翻页正确性，
     *                   只影响"共 N 篇"这类展示——真要精确得在请求里放开 track_total_hits。
     * @param highlights articleId → 该篇的标题高亮与片段高亮，见 {@link Highlight}
     * @param nextCursor 下一页游标；{@code null} = 到底
     */
    public record SearchHits(List<Long> articleIds, long total, Map<Long, Highlight> highlights,
                             String nextCursor) {
    }

    /**
     * 一篇文章的两处高亮（含 {@code <em>} 标记），**分开返回**：
     * {@code title} 给标题用，{@code fragment} 给摘要行用。
     *
     * <p><b>为什么不是一个字符串</b>：上一版只挑「第一个非空片段」，优先级是
     * 摘要 → 正文 → 标题。于是关键词**只命中标题**时，返回的片段取自不含关键词的摘要——
     * 前端一个 {@code <em>} 都看不到，而真正该亮的标题反倒没有数据可用。
     *
     * <p>ES 那边三个字段的高亮本来就都返回了（见 {@code search} 里的 highlight 配置），
     * 丢的是 Java 侧这一层：**这里只是把已经拿到手的数据别丢**，检索行为零变化。
     *
     * @param title    标题内的高亮；该字段没命中时为 {@code null}
     * @param fragment 摘要/正文里命中的片段；都没命中时为 {@code null}
     */
    public record Highlight(String title, String fragment) {
    }

    // ---------- 索引管理 ----------

    /**
     * 建索引（幂等）。应用启动时调一次。
     *
     * <p>mapping 里中文分词用 **ik_max_word**（插件提供）—— 对比默认 analyzer 的差别：
     * {@code ik_max_word: 秒杀|缓存|穿透} vs {@code standard: 秒|杀|缓|存|穿|透}。
     * 单字切分下「秒杀」搜不出自己，这就是要装 IK 的原因。
     */
    public void ensureIndex() {
        try {
            boolean exists = esClient.indices().exists(e -> e.index(INDEX_NAME)).value();
            if (exists) {
                return;
            }
            esClient.indices().create(c -> c
                    .index(INDEX_NAME)
                    .mappings(m -> m
                            .properties("title", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("summary", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("content", p -> p.text(t -> t.analyzer("ik_max_word")))
                            .properties("categoryId", p -> p.keyword(k -> k))
                            .properties("authorId", p -> p.keyword(k -> k))
                            // status 用 keyword（不是 text）：检索期过滤靠 term 精确匹配，分词字段查不准
                            .properties("status", p -> p.keyword(k -> k))
                            .properties("publishedAt", p -> p.date(d -> d))
                            .properties("viewCount", p -> p.long_(l -> l))
                            // 游标分页的排序 tiebreaker。**不能拿 _id 顶替**：ES 8 默认禁止访问
                            // _id 的 fielddata（indices.id_field_data.enabled=false），按它排序直接
                            // all shards failed——而调用方会把这当成"ES 不可用"静默降级回 LIKE，
                            // 表面功能还在，实际搜索一直由 LIKE 在扛（本地就是这么踩的）
                            .properties("articleId", p -> p.long_(l -> l))
                    )
            );
            log.info("[SEARCH-INDEX] 索引已创建 index={}", INDEX_NAME);
        } catch (Exception e) {
            // 建索引失败不阻断启动：搜索会走降级，其余功能不受影响
            log.error("[SEARCH-INDEX] 创建索引失败（搜索将走降级），index={}", INDEX_NAME, e);
        }
    }

    /** 写入/更新一篇文章的搜索文档。 */
    public void indexArticle(Articles article) {
        if (article == null || article.getId() == null) {
            return;
        }
        try {
            esClient.index(i -> i
                    .index(INDEX_NAME)
                    .id(String.valueOf(article.getId()))
                    .document(toDocument(article))
            );
        } catch (Exception e) {
            log.error("[SEARCH-INDEX] 写入失败 articleId={}（该文章暂时搜不到，重建索引可恢复）",
                    article.getId(), e);
        }
    }

    /** 删除一篇文章的搜索文档。 */
    public void deleteArticle(Long articleId) {
        if (articleId == null) {
            return;
        }
        try {
            esClient.delete(d -> d.index(INDEX_NAME).id(String.valueOf(articleId)));
        } catch (Exception e) {
            log.error("[SEARCH-INDEX] 删除失败 articleId={}（应从搜索结果中消失但仍在，"
                    + "靠检索期的 status 过滤兜住）", articleId, e);
        }
    }

    private Map<String, Object> toDocument(Articles article) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("title", article.getTitle());
        doc.put("summary", article.getSummary());
        doc.put("content", article.getContent());
        doc.put("categoryId", article.getCategoryId() == null ? null : String.valueOf(article.getCategoryId()));
        doc.put("authorId", article.getAuthorId() == null ? null : String.valueOf(article.getAuthorId()));
        doc.put("status", String.valueOf(article.getStatus()));
        doc.put("publishedAt", article.getPublishedAt() == null
                ? null
                : article.getPublishedAt().atZone(ZoneId.systemDefault()).toInstant().toString());
        doc.put("viewCount", article.getViewCount());
        // 排序 tiebreaker（见 search 里的说明）。之前用 _id 排序被 ES 8 拒了，
        // 所以这里显式写一份：它是 article id 的数值副本，供 search_after 排序用
        doc.put("articleId", article.getId());
        return doc;
    }

    // ---------- 检索 ----------

    /**
     * 全文检索。**返回 null 表示 ES 不可用，调用方应降级回 LIKE。**
     *
     * <p><b>分页用 {@code search_after} 而不是 {@code from/size}</b>：ES 的
     * {@code index.max_result_window} 默认 10000，{@code from + size} 一旦越过就**直接抛错**
     * （不是变慢，是 {@code Result window is too large}）。{@code search_after} 没有这个上限，
     * 代价是必须**显式排序**、且排序键要能保证全序——所以下面按 {@code _score → _id} 两级排。
     *
     * <p>⚠️ 用 {@code _id} 做 tiebreaker 是**简化选择**：按 {@code _id} 排序会让 ES 加载
     * fielddata（内存开销随文档数增长）。官方完整的深分页方案是 **PIT + {@code _shard_doc}**
     * （PIT 给一个时点快照，{@code _shard_doc} 做稳定 tiebreaker），代价是要管 PIT 的
     * open / close / keep_alive 生命周期。当前文档量远没到那个规模，先用 {@code _id}；
     * 要升级时二选一：给索引加一个带 doc_values 的 {@code articleId} 字段，或者引入 PIT。
     *
     * <p>⚠️ 顺手记一笔既有缺口：本方法**没有把 {@code sort} 接进排序数组**
     * （改造前它同样没被使用过），所以搜索页切"推荐 / 最新"不会改变结果顺序。
     * 这次只做分页改造、不动排序行为——要接线就在这里加一组 field sort，
     * 注意游标段数会跟着变（现在编码的是 {@code score|_id} 两段）。
     *
     * @param sort   当前未生效，见上
     * @param cursor {@code null} = 第一页；否则是上一页返回的 {@code nextCursor}
     */
    public SearchHits search(String keyword, Long categoryId, String sort,
                             String cursor, long pageSize) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }

        // search_after 要求排序键**全序**：_score 会大量并列，所以必须再挂一个唯一的 tiebreaker，
        // 否则并列名次的那几条会在翻页时被跳过或重复。
        //
        // ⚠️ tiebreaker 用 articleId，**不是 _id**：ES 8 默认禁止访问 _id 的 fielddata
        // （indices.id_field_data.enabled=false），按 _id 排序会直接 all shards failed，
        // 而调用方把它当"ES 不可用"静默降级——搜索看起来还在，实际查的是 LIKE。
        // 所以索引里专门进了一个带 doc_values 的 articleId（见 ensureIndex / toDocument）。
        List<SortOptions> sorts = List.of(
                SortOptions.of(o -> o.score(sc -> sc.order(SortOrder.Desc))),
                SortOptions.of(o -> o.field(f -> f.field("articleId").order(SortOrder.Desc))));

        List<FieldValue> searchAfter = null;
        if (cursor != null && !cursor.isBlank()) {
            List<String> parts = CursorCodec.decode(cursor);
            if (parts.size() < 2) {
                throw new IllegalArgumentException("分页游标格式非法");
            }
            searchAfter = List.of(
                    FieldValue.of(CursorCodec.toDouble(parts.get(0))),
                    // 第二段必须给成**数值**：排序字段 articleId 是 long，
                    // 用字符串会让 ES 拿字符串去比 long（类型不匹配时索引定位会退化甚至报错）
                    FieldValue.of(CursorCodec.toLong(parts.get(1))));
        }
        final List<FieldValue> after = searchAfter;

        try {
            SearchResponse<Map> response = esClient.search(s -> {
                s.index(INDEX_NAME)
                        .size((int) pageSize)
                        .sort(sorts);
                if (after != null) {
                    s.searchAfter(after);
                }
                s
                        // 多字段权重：标题 > 摘要 > 正文
                        .query(q -> q.bool(b -> {
                            b.must(m -> m.multiMatch(mm -> mm
                                    .fields("title^3", "summary^2", "content")
                                    .query(keyword.trim())
                                    /*
                                     * ⚠️ 必须显式 AND。multi_match 默认是 **OR** 语义——
                                     * 「建造者」分词后是「建造 | 者」，只要命中任一个词就算匹配，
                                     * 于是「面向进阶开发**者**」这种完全无关的文章全被捞进来
                                     * （实测：搜「建造者」返回 17 条，高亮显示大部分只命中了「者」）。
                                     * 站内搜索要的是「用户搜的词都出现在文章里」，不是「沾一个边就算」。
                                     */
                                    .operator(Operator.And)));
                            // ⚠️ 检索期过滤：索引期只收 PUBLISHED，但索引同步是异步的，
                            //    「刚设为隐藏、索引还没删」的窗口里只靠索引期就会搜出不该看的文章。
                            b.filter(f -> f.term(t -> t
                                    .field("status")
                                    .value(String.valueOf(BlogConstants.ArticlesStatus.PUBLISHED))));
                            if (categoryId != null) {
                                b.filter(f -> f.term(t -> t
                                        .field("categoryId")
                                        .value(String.valueOf(categoryId))));
                            }
                            return b;
                        }))
                        .highlight(h -> h
                                .fields("title", HighlightField.of(f -> f))
                                .fields("summary", HighlightField.of(f -> f))
                                .fields("content", HighlightField.of(f -> f
                                        .fragmentSize(HIGHLIGHT_FRAGMENT_SIZE)))
                                .preTags("<em>")
                                .postTags("</em>"));
                return s;
            }, Map.class);

            List<Long> ids = new ArrayList<>();
            Map<Long, Highlight> highlights = new HashMap<>();
            for (Hit<Map> hit : response.hits().hits()) {
                if (hit.id() == null) {
                    continue;
                }
                Long id = Long.valueOf(hit.id());
                ids.add(id);
                Highlight highlight = pickHighlight(hit.highlight());
                if (highlight != null) {
                    highlights.put(id, highlight);
                }
            }

            long total = response.hits().total() == null ? ids.size() : response.hits().total().value();
            String nextCursor = buildNextCursor(response, (int) pageSize);
            log.info("[SEARCH-ES] keyword={} categoryId={} 命中={} total={} 耗时={}ms 有下一页={}",
                    keyword, categoryId, ids.size(), total, response.took(), nextCursor != null);
            return new SearchHits(ids, total, highlights, nextCursor);
        } catch (Exception e) {
            // 走降级：调用方会回落到 LIKE。这里是 warn 不是 error——ES 抖一下不该刷满 error 日志
            log.warn("[SEARCH-ES] 检索失败，将降级到 LIKE：keyword={} error={}", keyword, e.getMessage());
            return null;
        }
    }

    /**
     * 生成下一页游标：只在**本页取满**时才给（取不满说明后面没有了）。
     *
     * <p>游标直接复用 ES 回的 {@code hit.sort()} 值——那是排序字段的真实投影，
     * 比例子从 {@code _source} 里自己拼一遍可靠（浮点精度、id 格式由 ES 保证前后一致）。
     */
    private String buildNextCursor(SearchResponse<Map> response, int pageSize) {
        List<Hit<Map>> hits = response.hits().hits();
        if (hits.size() < pageSize) {
            return null;
        }
        List<FieldValue> sortValues = hits.get(hits.size() - 1).sort();
        if (sortValues == null || sortValues.size() < 2) {
            // 拿不到 sort 值 = 这轮请求没带排序。宁可停在"到底"，
            // 也不要造一个会让下一页从头的游标（那会变成永远翻不到底）
            return null;
        }
        return CursorCodec.encode(
                String.valueOf(sortValues.get(0).doubleValue()),
                // articleId 是 long，取 longValue 再转字符串；用 stringValue() 在这里碰巧也对，
                // 但它对数值型的语义依赖版本，写死 longValue 更稳
                String.valueOf(sortValues.get(1).longValue()));
    }

    /**
     * 把 ES 的高亮响应拆成「标题」+「片段」两处。
     *
     * <p>片段**只从 {@code summary} / {@code content} 取**：标题的命中由 {@code title} 自己承载。
     * 让片段退回标题（上一版的行为）会让摘要行去显示一段标题，属于同一条信息的两个位置。
     *
     * <p>每个字段对应的是**片段列表**（同一字段可能命中多处），这里只取第一处——
     * 卡片位置有限，多片段是搜索页 / 详情页才需要的形态。
     *
     * <p>包级可见（不是 private）是为了让单测直接喂高亮 map：它是纯函数，
     * 不起 ES 客户端就能覆盖「只命中标题」「只命中正文」这些分支。
     */
    static Highlight pickHighlight(Map<String, List<String>> rawHighlights) {
        if (rawHighlights == null || rawHighlights.isEmpty()) {
            return null;
        }
        String title = firstFragment(rawHighlights.get("title"));
        String fragment = null;
        for (String field : List.of("summary", "content")) {
            fragment = firstFragment(rawHighlights.get(field));
            if (fragment != null) {
                break;
            }
        }
        if (title == null && fragment == null) {
            return null;
        }
        return new Highlight(title, fragment);
    }

    private static String firstFragment(List<String> fragments) {
        return fragments == null || fragments.isEmpty() ? null : fragments.get(0);
    }

    /** 重建全量索引（运维/修复用）。 */
    public int rebuild(List<Articles> publishedArticles) {
        ensureIndex();
        int count = 0;
        for (Articles article : publishedArticles) {
            indexArticle(article);
            count++;
        }
        log.info("[SEARCH-INDEX] 全量重建完成，共 {} 篇", count);
        return count;
    }

    /**
     * 索引里的文档数。**ES 不可用时返回 -1**（调用方据此跳过「首次灌数据」，
     * 而不是把 -1 当成"索引是空的"去灌一遍）。
     */
    public long count() {
        try {
            return esClient.count(c -> c.index(INDEX_NAME)).count();
        } catch (Exception e) {
            log.warn("[SEARCH-INDEX] 读取索引文档数失败（ES 不可用？）：{}", e.getMessage());
            return -1L;
        }
    }

    /** 供健康检查用：ES 是否可用。 */
    public boolean isAvailable() {
        try {
            return esClient.ping().value();
        } catch (IOException e) {
            return false;
        }
    }
}
