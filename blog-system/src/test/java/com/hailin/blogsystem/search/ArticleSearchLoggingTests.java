package com.hailin.blogsystem.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.transport.ElasticsearchTransport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class ArticleSearchLoggingTests {

    @Test
    void successfulSearchLogsAtDebugInsteadOfInfo() throws Exception {
        ElasticsearchTransport transport = mock(ElasticsearchTransport.class);
        SearchResponse<Object> response = SearchResponse.of(builder -> builder
                .took(3)
                .timedOut(false)
                .shards(shards -> shards.total(1).successful(1).failed(0))
                .hits(hits -> hits.hits(List.of())));
        doReturn(response).when(transport).performRequest(any(), any(), any());

        Logger logger = (Logger) LoggerFactory.getLogger(ArticleSearchService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);

        try {
            ArticleSearchService service = new ArticleSearchService(new ElasticsearchClient(transport));

            service.search("Redis", null, "latest", null, 10);

            List<ILoggingEvent> searchEvents = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().startsWith("[SEARCH-ES] keyword="))
                    .toList();
            assertThat(searchEvents).singleElement()
                    .extracting(ILoggingEvent::getLevel)
                    .isEqualTo(Level.DEBUG);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }
}
