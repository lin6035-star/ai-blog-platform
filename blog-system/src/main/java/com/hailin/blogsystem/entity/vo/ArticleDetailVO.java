package com.hailin.blogsystem.entity.vo;

import com.hailin.blogsystem.entity.Articles;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ArticleDetailVO {

    private Long id;
    private Long categoryId;
    private Long authorId;
    private String authorName;
    /**
     * 作者头像。
     *
     * <p><b>不能放进 {@link #from} 里填</b>：那一步手上只有 {@code Articles} 实体，
     * 而头像在 {@code users} 表上。由 service 层批量取完 users 后回填——
     * 那一步本来就是 {@code selectBatchIds} 一次查完（不是 N+1），所以只是多拷一个字段。
     */
    private String avatarUrl;
    private String categoryName;
    private String title;
    private String summary;
    private String content;
    private String coverUrl;
    private Integer status;
    private Integer viewCount;
    private LocalDateTime publishedAt;
    private Integer commentCount = 0;
    private Integer favoriteCount = 0;
    private Integer likeCount = 0;
    private Integer liked = 0;
    private Integer favorited = 0;
    private Integer shareCount = 0;

    /**
     * 搜索命中的高亮片段（含 {@code <em>} 标记），**只在走 ES 检索时有值**，
     * 普通列表接口为 null。
     *
     * <p>⚠️ 前端渲染前必须经过 DOMPurify（项目统一清洗出口）——
     * 虽然这个字段是 ES 生成的、来源可信，但「带 HTML 标签的字符串」一律按不可信处理，
     * 免得以后有人把用户输入拼进来。
     */
    private String highlight;

    /**
     * 搜索命中的**标题**高亮（含 {@code <em>} 标记），与 {@link #highlight} 配套：
     * 这个给标题用，{@link #highlight} 给摘要行用。同样只在走 ES 检索时有值。
     *
     * <p><b>为什么分两个字段</b>：合成一个字符串就必须挑一个优先级，而「只命中标题」
     * 的场景下无论挑哪边都会丢掉另一边的信息——上一版就是这样，标题命中时摘要行里
     * 一个 {@code <em>} 都没有。详见 {@code ArticleSearchService.Highlight}。
     *
     * <p>清洗要求与 {@link #highlight} 完全相同：**前端渲染前必须过 DOMPurify**。
     */
    private String highlightTitle;

    /**
     * 今日独立访客数（HyperLogLog 近似值，非累计）。
     *
     * 注意与 {@link #viewCount} 的语义差别：viewCount 是**累计**浏览量，
     * 这个是**当天**的独立访客——前端必须标清楚"今日访客"，不能让用户当成累计值。
     * 实时计算、不进详情缓存（缓存 10 分钟会让它看起来不动）。
     */
    private Integer uvCount = 0;

    /**
     * 列表语义：**不带正文**，只带卡片需要的字段。
     *
     * <p>正文是 LONGTEXT，实测一页 10 篇的 JSON 里它占 92%（53.6 KB → 4.1 KB）。
     * 列表页一个字都用不到它（{@code ArticleCard} 只渲染 title / summary / coverUrl / 计数），
     * 但这份数据会跟着响应体走一遍网络、再以「每页一个 key」的粒度进 Redis 缓存。
     *
     * <p><b>为什么默认不带、而不是列表侧显式剥离</b>：漏加的失败模式不同——
     * 默认不带时漏改的是详情（页面空白，一眼可见）；默认带时漏改的是列表（静默多传 50 KB，
     * 测试不会红、日志看不出来）。要让前者发生，需要显式用 {@link #fromDetail}。
     */
    public static ArticleDetailVO from(Articles article) {
        if (article == null) {
            return null;
        }

        ArticleDetailVO vo = new ArticleDetailVO();
        vo.setId(article.getId());
        vo.setCategoryId(article.getCategoryId());
        vo.setAuthorId(article.getAuthorId());
        vo.setTitle(article.getTitle());
        vo.setSummary(article.getSummary());
        vo.setCoverUrl(article.getCoverUrl());
        vo.setStatus(article.getStatus());
        vo.setViewCount(article.getViewCount());
        vo.setPublishedAt(article.getPublishedAt());
        vo.setCommentCount(article.getCommentCount());
        vo.setLikeCount(article.getLikeCount());
        vo.setFavoriteCount(article.getFavoriteCount());
        vo.setShareCount(article.getShareCount());
        return vo;
    }

    /**
     * 详情语义：在 {@link #from} 之上补正文。**只给详情路径用**（公开文章详情、编辑器回填）。
     *
     * <p>它同时是详情缓存的写入来源——缓存里的 VO 必须带正文，否则读缓存时详情页没有内容。
     */
    public static ArticleDetailVO fromDetail(Articles article) {
        ArticleDetailVO vo = from(article);
        if (vo != null) {
            vo.setContent(article.getContent());
        }
        return vo;
    }
}
