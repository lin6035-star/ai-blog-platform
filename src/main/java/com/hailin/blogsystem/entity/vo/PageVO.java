package com.hailin.blogsystem.entity.vo;

import lombok.Data;

import java.util.List;

@Data
public class PageVO<T> {
    private List<T> list;
    /**
     * 命中总数。**游标分页时为 {@code null}**——游标不需要它，算它要额外一条 {@code COUNT(*)}。
     * 前端判断"还有没有下一页"要看 {@link #nextCursor}，不要看它。
     */
    private Long total;
    private Long page;
    private Long pageSize;

    /**
     * 游标分页的「下一页」凭证：原样带回来就是下一页，{@code null} = 已经到底。
     *
     * <p>它是**不透明 token**——前端不要解析内容，只负责"拿到就带回"。
     * 用 page/pageSize 的传统分页接口（评论 / 钱包 / AI 会话等）该字段恒为 {@code null}，
     * 所以给本类加这个字段对它们零影响。
     */
    private String nextCursor;

    public PageVO(){

    }

    public PageVO(List<T> list,Long total,Long page,Long pageSize){
        this.list = list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }
}
