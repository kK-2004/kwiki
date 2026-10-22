package com.kwiki.infrastructure.elasticsearch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EsSearchTimingTest {
    final Logger logger = (Logger) LoggerFactory.getLogger(EsSearchTiming.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void slowSearchReportsClientWaitAgainstEsTook() {
        long now = System.nanoTime();
        long started = now - TimeUnit.MILLISECONDS.toNanos(4_800);
        long requested = now - TimeUnit.MILLISECONDS.toNanos(4_700);

        EsSearchTiming.logIfSlow("BM25", 50, started, requested, 6L, 50, null);

        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("branch=BM25").contains("topK=50").contains("esTookMs=6").contains("hits=50")
                .containsPattern("prepareMs=(9\\d|1\\d\\d)").containsPattern("searchMs=47\\d\\d"));
    }

    @Test
    void fastSearchIsNotLogged() {
        long now = System.nanoTime();
        EsSearchTiming.logIfSlow("VECTOR", 20, now, now, 3L, 20, null);
        assertThat(appender.list).isEmpty();
    }
}
