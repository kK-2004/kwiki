package com.kwiki.rag.retrieval;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 单路检索失败时要能看到原因：WARN 日志带分支、失败类型与根因，双路失败的异常信息带两路原因。 */
class ConcurrentRecallFailureReasonTest {
    final Logger logger = (Logger) LoggerFactory.getLogger(ConcurrentRecallService.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    final ScopeFilter scope = new ScopeFilter(false, List.of(1L));

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    static ChildRecallPort failing(String message) {
        return (query, vector, filter, topK) -> {
            throw new IllegalStateException("bm25 recall failed", new RuntimeException(message));
        };
    }

    static ChildRecallPort slow() {
        return (query, vector, filter, topK) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        };
    }

    @Test
    void bothBranchesFailingReportsEachReason() {
        var service = new ConcurrentRecallService(failing("es_rejected_execution_exception"), slow());

        assertThatThrownBy(() -> service.recall("问题", new float[]{0.1f}, scope, 20, 200, RetrievalStrategy.HYBRID))
                .isInstanceOf(ConcurrentRecallService.RetrievalBranchException.class)
                .hasMessageContaining("both branches failed")
                .hasMessageContaining("bm25=error")
                .hasMessageContaining("es_rejected_execution_exception")
                .hasMessageContaining("vector=timeout");
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anySatisfy(line -> assertThat(line).contains("branch=BM25").contains("es_rejected_execution_exception"))
                .anySatisfy(line -> assertThat(line).contains("branch=VECTOR").contains("timeout"));
    }

    @Test
    void missingQueryEmbeddingIsNamedInTheFailure() {
        var service = new ConcurrentRecallService(failing("connection refused"), slow());

        assertThatThrownBy(() -> service.recall("问题", null, scope, 20, 200, RetrievalStrategy.HYBRID))
                .hasMessageContaining("vector=no-query-embedding")
                .hasMessageContaining("connection refused");
    }

    @Test
    void degradationMarkersStayStableForCallers() {
        var service = new ConcurrentRecallService(failing("boom"),
                (query, vector, filter, topK) -> List.of());

        var outcomes = service.recall("问题", new float[]{0.1f}, scope, 20, 1_000, RetrievalStrategy.HYBRID);

        assertThat(outcomes).extracting(ConcurrentRecallService.BranchOutcome::degradation)
                .contains("bm25-degraded");
    }
}
