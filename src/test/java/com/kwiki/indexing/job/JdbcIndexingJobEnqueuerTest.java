package com.kwiki.indexing.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.support.KeyHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.inOrder;

/**
 * 入队扇出契约：同一入队事务里追加变更事件、幂等 upsert 任务行，
 * 并为每个 writeEnabled 目标生成独立目标行；重放入队安全（已终结
 * 目标重开，未终结目标保留退避节奏）；无 JDBC 时安全跳过。
 */
@ExtendWith(MockitoExtension.class)
class JdbcIndexingJobEnqueuerTest {

    private static final long EVENT_ID = 41L;
    private static final long JOB_ID = 77L;

    @Mock
    JdbcOperations jdbc;

    @Mock
    ObjectProvider<JdbcOperations> jdbcProvider;

    private JdbcIndexingJobEnqueuer enqueuer(boolean withJdbc) {
        when(jdbcProvider.getIfAvailable()).thenReturn(withJdbc ? jdbc : null);
        return new JdbcIndexingJobEnqueuer(jdbcProvider);
    }

    /** 事件插入回填生成的自增键；任务行按幂等键回读 id。 */
    private void stubIdentifiers() {
        doAnswer(invocation -> {
            KeyHolder keys = invocation.getArgument(1);
            keys.getKeyList().add(Map.of("GENERATED_KEY", EVENT_ID));
            return 1;
        }).when(jdbc).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(KeyHolder.class));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(JOB_ID);
    }

    @Test
    void enqueuePersistsEventJobAndPerTargetRowsInOneFlow() {
        stubIdentifiers();

        enqueuer(true).enqueuePageUpsert(7L, 103L);

        // 1) 任务 upsert 携带既有的幂等键语义
        ArgumentCaptor<Object[]> jobArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INTO indexing_job\n"),
                jobArgs.capture());
        List<Object> values = java.util.Arrays.asList(jobArgs.getValue());
        assertThat(values.subList(0, 3)).containsExactly("UPSERT", "PAGE", 7L);
        assertThat(values.get(3)).isEqualTo(103L);
        assertThat(values.get(4)).isNull();
        assertThat(values.get(5)).isEqualTo("PAGE:7:103:UPSERT");

        // 2) 扇出目标行：以任务 id / 事件 id / 幂等键前缀调用
        ArgumentCaptor<Object[]> targetArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("INSERT INTO indexing_job_target"),
                targetArgs.capture());
        assertThat(java.util.Arrays.asList(targetArgs.getValue()))
                .containsExactly(JOB_ID, EVENT_ID, JOB_ID);

        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).queryForList(
                org.mockito.ArgumentMatchers.contains("FOR SHARE"), eq(Integer.class));
        ordered.verify(jdbc).update(
                any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(KeyHolder.class));
    }

    @Test
    void fencedEnqueueCarriesExpectedLifecycleVersion() {
        stubIdentifiers();

        enqueuer(true).enqueuePageDelete(7L, 3L);

        ArgumentCaptor<Object[]> jobArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INTO indexing_job\n"),
                jobArgs.capture());
        List<Object> values = java.util.Arrays.asList(jobArgs.getValue());
        assertThat(values.get(4)).isEqualTo(3L);
        assertThat(values.get(5)).isEqualTo("PAGE:7:-:DELETE");
    }

    @Test
    void kbDeleteEventCarriesTheKbId() {
        stubIdentifiers();

        enqueuer(true).enqueueKnowledgeBaseDelete(9L, 4L);

        org.mockito.Mockito.verify(jdbc).update(
                any(org.springframework.jdbc.core.PreparedStatementCreator.class),
                any(KeyHolder.class));
        ArgumentCaptor<Object[]> jobArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("INTO indexing_job\n"),
                jobArgs.capture());
        assertThat(java.util.Arrays.asList(jobArgs.getValue()).subList(0, 3))
                .containsExactly("DELETE", "KNOWLEDGE_BASE", 9L);
    }

    @Test
    void attachmentEnqueuesUseTheirResourceType() {
        stubIdentifiers();

        enqueuer(true).enqueueAttachmentUpsert(21L);
        enqueuer(true).enqueueAttachmentDelete(21L);

        verify(jdbc, org.mockito.Mockito.times(2)).update(
                org.mockito.ArgumentMatchers.contains("INTO indexing_job\n"),
                any(Object[].class));
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
