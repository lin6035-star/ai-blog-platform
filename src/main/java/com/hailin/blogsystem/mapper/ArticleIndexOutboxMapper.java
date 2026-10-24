package com.hailin.blogsystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hailin.blogsystem.ai.index.ArticleIndexOutbox;
import org.apache.ibatis.annotations.Mapper;

/** 文章索引同步 outbox（放在 mapper 包下，跟随项目 @MapperScan 约定）。 */
@Mapper
public interface ArticleIndexOutboxMapper extends BaseMapper<ArticleIndexOutbox> {
}
