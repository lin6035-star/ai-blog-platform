package com.hailin.blogsystem.ai.mq;

public interface MemoryExtractionPublisher {

    void publish(MemoryExtractionEvent event);
}
