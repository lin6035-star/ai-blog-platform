package com.hailin.blogsystem.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 全局单页上限（2026-10-17 上线审计）：任何分页查询 size 超过 100 自动按 100 处理。
        // 注意它**压不住 size<0**——MP 对负数会跳过所有分页直接全表查，
        // 那一半由入口的 PageParamUtils 归一化兜住，两处必须同时存在。
        pagination.setMaxLimit(100L);
        interceptor.addInnerInterceptor(pagination);
        //乐观锁：@Version 实体更新时自动附加 version 条件 + version+1（learning_stages 读改写防丢失更新）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
