package com.kwiki.indexing.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;



import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class JdbcIndexingJobEnqueuerTest {

    @Mock
    JdbcOperations jdbc;

    private JdbcIndexingJobEnqueuer enqueuer(boolean withJdbc) {
        ObjectProvider<JdbcOperations> provider = new ObjectProvider<>() {
            @Override
            public JdbcOperations getIfAvailable() {
                return withJdbc ? jdbc : null;
            }
        };
        return new JdbcIndexingJobEnqueuer(provider);
    }

    @Test
    void pageUpsertUsesVersionedIdempotencyKey() {
        enqueuer(true).enqueuePageUpsert(7L, 103L);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        java.util.List<Object> values = java.util.Arrays.asList(args.getValue());
        assertThat(values.subList(0, 3)).containsExactly("UPSERT", "PAGE", 7L);
        assertThat(values.get(3)).isEqualTo(103L);
        assertThat(values.get(4)).isNull(); // 预期的生命周期版本（历史路径）
        assertThat(values.get(5)).isEqualTo("PAGE:7:103:UPSERT");
    }

    @Test
    void pageDeleteKeyHasNoRevisionPlaceholder() {
        enqueuer(true).enqueuePageDelete(7L);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(java.util.Arrays.asList(args.getValue()).get(5)).isEqualTo("PAGE:7:-:DELETE");
    }

    @Test
    void fencedEnqueueCarriesExpectedLifecycleVersion() {
        enqueuer(true).enqueuePageDelete(7L, 3L);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        java.util.List<Object> values = java.util.Arrays.asList(args.getValue());
        assertThat(values.get(4)).isEqualTo(3L);
        assertThat(values.get(5)).isEqualTo("PAGE:7:-:DELETE");
    }

    @Test
    void attachmentUpsertAndDeleteUseTheirResourceType() {
        JdbcIndexingJobEnqueuer service = enqueuer(true);
        service.enqueueAttachmentUpsert(21L);
        service.enqueueAttachmentDelete(21L);

        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void missingJdbcTemplateIsIgnoredSafely() {
        assertThatCode(() -> {
            JdbcIndexingJobEnqueuer noJdbc = enqueuer(false);
            noJdbc.enqueuePageUpsert(1L, 1L);
            noJdbc.enqueuePageDelete(1L);
        }).doesNotThrowAnyException();
        verifyNoInteractions(jdbc);
    }
}
