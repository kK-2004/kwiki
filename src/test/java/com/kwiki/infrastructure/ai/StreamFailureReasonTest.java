package com.kwiki.infrastructure.ai;

import com.kwiki.rag.orchestration.RunFailure;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** SSE 解析器自行回调的读流失败要带上原因码，区分主动取消 / 超时与断流。 */
class StreamFailureReasonTest {

    static ServerSentEventListener capturing(List<Throwable> errors) {
        return errors::add;
    }

    @Test
    void readFailureAfterCancellationCarriesTheCancellationReason() {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Throwable> logged = new CopyOnWriteArrayList<>();
        var reason = new AtomicReference<>("cancelled");

        CancellableModelHttpClient.withFailureReason(capturing(errors), reason, logged::add)
                .onError(new IOException("closed"));

        assertThat(errors).singleElement().isInstanceOfSatisfying(RunFailure.class, failure -> {
            assertThat(failure.getMessage()).isEqualTo("cancelled");
            assertThat(failure.getCause()).isInstanceOf(IOException.class).hasMessage("closed");
        });
        assertThat(logged).hasSize(1);
    }

    @Test
    void readFailureWithoutLocalCancellationIsABrokenStream() {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CancellableModelHttpClient.withFailureReason(capturing(errors), new AtomicReference<>(), e -> { })
                .onError(new IOException("connection reset"));

        assertThat(errors).singleElement().isInstanceOfSatisfying(RunFailure.class,
                failure -> assertThat(failure.getMessage()).isEqualTo("model-stream-failed"));
    }

    @Test
    void alreadyCodedFailuresPassThroughUnchanged() {
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        var timeout = new RunFailure("timeout");
        CancellableModelHttpClient.withFailureReason(capturing(errors), new AtomicReference<>("cancelled"), e -> { })
                .onError(timeout);

        assertThat(errors).containsExactly(timeout);
    }
}
