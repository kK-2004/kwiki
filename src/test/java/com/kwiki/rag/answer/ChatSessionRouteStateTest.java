package com.kwiki.rag.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kwiki.rag.routing.Intent;
import com.kwiki.rag.routing.PriorRoute;
import com.kwiki.rag.routing.RouteSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;

class ChatSessionRouteStateTest {

    private final JdbcOperations jdbc = mock(JdbcOperations.class);
    private final ChatSessionService service = new ChatSessionService(new ObjectProvider<>() {
        @Override
        public JdbcOperations getIfAvailable() {
            return jdbc;
        }
    });

    @Test
    void recordRouteWritesTheRouteColumns() {
        service.recordRoute(7L, new PriorRoute(Intent.KNOWLEDGE_QA, RouteSource.RULE, true, 1.0));

        verify(jdbc).update(contains("UPDATE chat_run SET route_intent"),
                eq("KNOWLEDGE_QA"), eq("RULE"), eq(true), eq(1.0), eq(7L));
    }

    @Test
    void savePendingClarificationWritesTheJsonColumn() {
        service.savePendingClarification(7L, "{\"id\":\"c-1\"}");

        verify(jdbc).update(contains("UPDATE chat_run SET pending_clarification"),
                eq("{\"id\":\"c-1\"}"), eq(7L));
    }

    @SuppressWarnings("unchecked")
    @Test
    void priorRouteReadsTheLatestEarlierRunOfTheSameSession() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("PROCEDURAL");
        when(rs.getString(2)).thenReturn("LLM");
        when(rs.getBoolean(3)).thenReturn(true);
        when(rs.getDouble(4)).thenReturn(0.8);
        when(jdbc.query(contains("route_intent IS NOT NULL"), any(RowMapper.class), eq(9L)))
                .thenAnswer(invocation -> List.of(
                        ((RowMapper<PriorRoute>) invocation.getArgument(1)).mapRow(rs, 0)));

        assertThat(service.priorRoute(9L))
                .isEqualTo(new PriorRoute(Intent.PROCEDURAL, RouteSource.LLM, true, 0.8));
    }

    @SuppressWarnings("unchecked")
    @Test
    void missingRouteOrPendingRowsYieldNull() {
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        assertThat(service.priorRoute(9L)).isNull();
        assertThat(service.pendingClarification(9L)).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void pendingClarificationIsOnlyTheImmediatelyPrecedingRun() {
        when(jdbc.query(contains("p.id < c.id ORDER BY p.id DESC LIMIT 1"), any(RowMapper.class), eq(9L)))
                .thenReturn(List.of("{\"id\":\"c-1\"}"));

        assertThat(service.pendingClarification(9L)).isEqualTo("{\"id\":\"c-1\"}");
    }
}
