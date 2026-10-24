package com.hailin.blogsystem.utils;

/**
 * 分页参数归一化（2026-10-17 上线审计补）。
 *
 * <p><b>为什么必须在入口做</b>：MyBatis-Plus 3.5.16 对 {@code size < 0} 会直接跳过所有分页
 * （返回全部行），也就是 {@code GET /api/articles/hot?pageSize=-1} 会变成一次全表查询——
 * 列表查询已剥离 LONGTEXT 正文，但热度榜 / 用户评论等入口仍可能把大字段读进内存。
 * 而这些都是**未登录可触发的公开接口**，一个参数就能把数据库读穿。
 *
 * <p>{@code PaginationInnerInterceptor.maxLimit} 只能压住「过大」的 size，压不住负数
 * （负数在 maxLimit 判定之前就决定「不分页」了），所以两处必须同时有：
 * 全局 maxLimit 兜住大值，入口归一化兜住负数/0。
 */
public final class PageParamUtils {

    /** 单页硬上限：超过按上限算，够前端所有列表使用 */
    public static final int MAX_PAGE_SIZE = 100;

    private PageParamUtils() {
    }

    public static long clampPage(Long page) {
        return page == null || page < 1 ? 1L : page;
    }

    /**
     * @param defaultSize 调用方原本的默认页大小（各接口不同，如 10 / 20 / 6）
     */
    public static long clampPageSize(Long pageSize, long defaultSize) {
        if (pageSize == null || pageSize < 1) {
            return defaultSize;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
