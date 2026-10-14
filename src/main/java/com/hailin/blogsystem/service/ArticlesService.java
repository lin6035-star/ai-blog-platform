package com.hailin.blogsystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hailin.blogsystem.entity.Articles;
import com.hailin.blogsystem.entity.dto.ArticlesDTO;
import com.hailin.blogsystem.entity.vo.ArticleDetailVO;
import com.hailin.blogsystem.entity.vo.PageVO;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

public interface ArticlesService extends IService<Articles>{
    /**
     * 获取公开文章详情。
     *
     * @param clientIp 客户端 IP，仅用于游客的独立访客（UV）统计。由 Controller 层传入，
     *                 不在这里读 request——Service 层不该依赖 Web 上下文
     */
    ArticleDetailVO getPublicArticleById(Long id, String clientIp);

    /**
     * 公开文章列表（**游标分页 / keyset**）。
     *
     * <p><b>为什么不是 page/pageSize</b>：首页是无限滚动。offset 分页在深页要跳过前 N 行，
     * 而"滚动"这个交互恰恰会让用户一直往下滑、offset 一直涨——两者天生冲突。
     * 游标分页的代价是 {@code O(log N + size)}，与深度无关；它换来的是"不能跳页"，
     * 而无限滚动本来就不跳页，所以这个代价在这里等于零。
     *
     * <p>代价的另一面：{@code total} 不再计算（游标不需要它），返回里为 {@code null}。
     * 调用方判断"还有没有下一页"要看 {@link PageVO#getNextCursor()}，不要看 total。
     *
     * @param cursor   {@code null} 或空 = 第一页；否则把上一页返回的 {@code nextCursor} 原样带回。
     *                 它是**不透明 token**，调用方不要解析其内容；格式非法会抛
     *                 {@link IllegalArgumentException}（全局 handler 转 40001）
     * @param pageSize 单页条数
     */
    PageVO<ArticleDetailVO> getArticles(String cursor, Long pageSize, String keyword, Long categoryId, String sort);

    ArticleDetailVO getArticlesById(Long id);

    Long writeArticle(ArticlesDTO articlesDTO);

    void updateArticle(Long id, ArticlesDTO articlesDTO);

    /**
     * V3.4 Agent 受控写（UPDATE_ARTICLE_TITLE）专用：只改自己文章的标题（字段级，绝不走全量 updateArticle）。
     * expectedOldTitle = 提案锚定的旧标题（proposal.articleTitle），条件更新 WHERE title = expectedOldTitle，
     * 影响 0 行 = 标题已被并发修改 → 拒绝不覆盖（用户需重新发起）；newTitle 非空/trim 由调用方负责。
     * 副作用：清详情缓存 + 列表缓存；已发布文章刷新 RAG 索引（doc 含标题）。
     */
    void updateArticleTitle(Long id, String expectedOldTitle, String newTitle, Long userId);

    /**
     * V3.7 Agent 受控写（HIDE_ARTICLE / PUBLISH_ARTICLE）专用：原子条件状态更新（不直接走 hideArticle/publishArticle——
     * 它们是无条件 set status，confirm 前置校验到执行之间有 TOCTOU 窗口）。
     * expectedStatus = 动作前置状态（HIDE 前置 PUBLISHED / PUBLISH 前置 HIDDEN，从动作方向推导），
     * 归属 + 前置状态进 WHERE：0 行 = 提案后状态已被并发修改 → 拒绝不覆盖。
     * 成功副作用按 targetStatus 对齐 hideArticle/publishArticle：清详情/列表缓存 + RAG 删/建；
     * target=PUBLISHED 额外 publishedAt=now（与编辑器「重新发布」语义一致，不发明新语义）。
     */
    void updateArticleVisibility(Long id, Integer expectedStatus, Integer targetStatus, Long userId);

    void deleteArticle(Long id);

    void hideArticle(Long id);

    void publishArticle(Long id);

    void syncViewCountToDb();

    PageVO<ArticleDetailVO> getHotArticles(Long page, Long pageSize);

    void rebuildArticleHotRank();

    PageVO<ArticleDetailVO> getPublicUserArticles(Long id,Long page,Long pageSize);

    PageVO<ArticleDetailVO> getPublicUserLiked(Long id, Long page, Long pageSize);

    PageVO<ArticleDetailVO> getPublicUserFavorited(Long id, Long page, Long pageSize);

    PageVO<ArticleDetailVO> getPublicCommented(Long id, Long page, Long pageSize);

    void shareArticle(Long id);
}
