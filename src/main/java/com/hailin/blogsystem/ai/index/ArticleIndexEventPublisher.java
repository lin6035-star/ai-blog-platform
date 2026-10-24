package com.hailin.blogsystem.ai.index;

/** 索引事件投递口子；实现方是 RocketMQ（测试可直接 mock）。 */
public interface ArticleIndexEventPublisher {

    void publish(ArticleIndexEvent event);
}
