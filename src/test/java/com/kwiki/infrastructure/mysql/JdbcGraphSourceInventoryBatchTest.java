package com.kwiki.infrastructure.mysql;

import com.kwiki.graph.GraphResourceId;
import com.kwiki.graph.GraphSourceInventoryPort.LifecycleState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用真实 JdbcTemplate（底层 JDBC 对象为 mock）驱动行回调，保证回调语义与生产一致：
 * 首行不丢、批量查询按类型分组分批。
 */
class JdbcGraphSourceInventoryBatchTest {

    /** 一条执行过的语句及其绑定参数。 */
    private record Executed(String sql, List<Object> params) {
    }

    /**
     * 每条语句的结果行由 (sql, 参数) 决定；每行是一组列值（按列序号取）。
     */
    private static JdbcTemplate jdbc(List<Executed> executed,
                                     BiFunction<String, List<Object>, List<Object[]>> rows) throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenAnswer(prepare -> {
            String sql = prepare.getArgument(0);
            List<Object> params = new ArrayList<>();
            PreparedStatement statement = mock(PreparedStatement.class);
            doAnswer(set -> {
                int index = set.getArgument(0);
                while (params.size() < index) {
                    params.add(null);
                }
                params.set(index - 1, set.getArgument(1));
                return null;
            }).when(statement).setObject(anyInt(), org.mockito.ArgumentMatchers.any());
            doAnswer(set -> {
                int index = set.getArgument(0);
                while (params.size() < index) {
                    params.add(null);
                }
                params.set(index - 1, (Object) set.getArgument(1));
                return null;
            }).when(statement).setLong(anyInt(), org.mockito.ArgumentMatchers.anyLong());
            when(statement.executeQuery()).thenAnswer(execute -> {
                executed.add(new Executed(sql, List.copyOf(params)));
                List<Object[]> result = rows.apply(sql, params);
                ResultSet rs = mock(ResultSet.class);
                int[] cursor = {-1};
                when(rs.next()).thenAnswer(next -> ++cursor[0] < result.size());
                when(rs.getLong(anyInt())).thenAnswer(get ->
                        ((Number) result.get(cursor[0])[(int) get.getArgument(0) - 1]).longValue());
                when(rs.getString(anyInt())).thenAnswer(get ->
                        (String) result.get(cursor[0])[(int) get.getArgument(0) - 1]);
                return rs;
            });
            return statement;
        });
        return new JdbcTemplate(dataSource);
    }

    /** 只保留偶数 id 的行：奇数视为已物理删除或不在有效状态。 */
    private static List<Object[]> evenIds(List<Object> params) {
        List<Object[]> rows = new ArrayList<>();
        for (Object param : params) {
            long id = ((Number) param).longValue();
            if (id % 2 == 0) {
                rows.add(new Object[]{id});
            }
        }
        return rows;
    }

    @Test
    void currentSourcesKeepTheFirstRow() throws Exception {
        List<Executed> executed = new ArrayList<>();
        JdbcTemplate jdbc = jdbc(executed, (sql, params) -> sql.contains("FROM wiki_page")
                ? List.of(new Object[]{11L}, new Object[]{12L}, new Object[]{13L})
                : List.of(new Object[]{21L, "image/png"}, new Object[]{22L, "image/png"}));
        JdbcGraphSourceInventory inventory = new JdbcGraphSourceInventory(jdbc, null, null, null);

        assertThat(inventory.listCurrentSources(7)).containsExactly(
                GraphResourceId.attachment(21), GraphResourceId.attachment(22),
                GraphResourceId.page(11), GraphResourceId.page(12), GraphResourceId.page(13));
    }

    @Test
    void lifecycleStatesMarkEveryReturnedRowLive() throws Exception {
        List<Executed> executed = new ArrayList<>();
        JdbcTemplate jdbc = jdbc(executed, (sql, params) -> evenIds(params));
        JdbcGraphSourceInventory inventory = new JdbcGraphSourceInventory(jdbc, null, null, null);

        Map<GraphResourceId, LifecycleState> states = inventory.lifecycleStates(List.of(
                GraphResourceId.page(2), GraphResourceId.page(3), GraphResourceId.page(4),
                GraphResourceId.attachment(6)));

        assertThat(states).containsEntry(GraphResourceId.page(2), LifecycleState.LIVE)
                .containsEntry(GraphResourceId.page(3), LifecycleState.RETIRED)
                .containsEntry(GraphResourceId.page(4), LifecycleState.LIVE)
                .containsEntry(GraphResourceId.attachment(6), LifecycleState.LIVE);
    }

    @Test
    void existingResourcesQueriesInBatchesPerResourceType() throws Exception {
        List<Executed> executed = new ArrayList<>();
        JdbcTemplate jdbc = jdbc(executed, (sql, params) -> evenIds(params));
        JdbcGraphSourceInventory inventory = new JdbcGraphSourceInventory(jdbc, null, null, null);

        List<GraphResourceId> manifest = new ArrayList<>();
        for (long id = 1; id <= 1200; id++) {
            manifest.add(GraphResourceId.page(id));
        }
        manifest.add(GraphResourceId.attachment(4));
        manifest.add(GraphResourceId.attachment(5));

        Set<GraphResourceId> existing = inventory.existingResources(manifest);

        // 1200 个页面分 3 批（500/500/200），附件 1 批
        assertThat(executed).extracting(run -> run.params().size()).containsExactly(500, 500, 200, 2);
        assertThat(executed.subList(0, 3)).allMatch(run -> run.sql().contains("FROM wiki_page WHERE id IN"));
        assertThat(executed.get(3).sql()).contains("FROM attachment WHERE id IN");
        assertThat(existing).hasSize(601)
                .contains(GraphResourceId.page(2), GraphResourceId.page(1200),
                        GraphResourceId.attachment(4))
                .doesNotContain(GraphResourceId.page(1), GraphResourceId.attachment(5));
    }
}
