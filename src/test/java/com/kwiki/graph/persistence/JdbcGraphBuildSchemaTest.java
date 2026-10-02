package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.support.KeyHolder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JdbcGraphBuildSchemaTest {
    @Test
    void 创建图任务以字符串绑定Chunk字段格式() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(contains("chunk_index_schema"), eq(Statement.RETURN_GENERATED_KEYS))).thenReturn(statement);
        when(jdbc.update(any(PreparedStatementCreator.class), any(KeyHolder.class))).thenAnswer(invocation -> {
            invocation.<PreparedStatementCreator>getArgument(0).createPreparedStatement(connection);
            invocation.<KeyHolder>getArgument(1).getKeyList().add(Map.of("id", 21L));
            return 1;
        });
        long id = new JdbcGraphBuildRepository(jdbc).createRun(new GraphBuildRunCommand(11, 7, 3, "kwiki-chunks-v3",
                5, "kwiki-communities-v5-kb7", 42, "schema-arbitrary", "entity-linking-v1", 0, 0));
        assertThat(id).isEqualTo(21);
        verify(statement).setString(8, "schema-arbitrary");
        verify(connection).prepareStatement(argThat(sql -> sql.contains("chunk_index_schema") && !sql.contains("mapping_schema_version")), eq(Statement.RETURN_GENERATED_KEYS));
    }
    @Test
    void 恢复图任务读取字符串字段格式() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getString("chunk_index_schema")).thenReturn("schema-arbitrary");
        when(rs.getString("state")).thenReturn("QUEUED");
        when(rs.getString("stage")).thenReturn("QUEUED");
        when(jdbc.query(anyString(), any(ResultSetExtractor.class), any(Object[].class))).thenAnswer(invocation ->
                invocation.<ResultSetExtractor<?>>getArgument(1).extractData(rs));
        var run = new JdbcGraphBuildRepository(jdbc).findActiveRunForUpdate(7);
        assertThat(run).hasValueSatisfying(value -> assertThat(value.chunkIndexSchema()).isEqualTo("schema-arbitrary"));
        verify(rs).getString("chunk_index_schema");
    }
}
