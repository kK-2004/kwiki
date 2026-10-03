package com.kwiki.graph.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphExtractionResult;
import com.kwiki.graph.GraphPredicate;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.GraphSourceRef;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 抽取缓存的读取、写回与失败记录截断。 */
class JdbcGraphExtractionCacheTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static GraphSourceChunk source() {
        return new GraphSourceChunk(7L, "PAGE", 42L, 3L, 1, 5,
                "parser-1", "chunker-1", "kb7:page:42:chunk:0", "hash-1");
    }

    private static GraphExtractionResult result() {
        GraphEntity entity = new GraphEntity("e1", "Kwiki", com.kwiki.graph.GraphEntityType.PRODUCT,
                List.of("kwiki"));
        GraphRelation relation = new GraphRelation("r1", "e1", GraphPredicate.USES, "e1",
                0.9, List.of(new GraphSourceRef(source().sourceChunkId(), 0, 5)));
        return new GraphExtractionResult(source(), List.of(entity), List.of(relation),
                "kwiki-extractor-1", "graph-extraction-v1", "el-v1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 身份一致的READY行命中并反序列化实体关系() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(String.class), any(ResultSetExtractor.class),
                any(), any(), any(), any())).thenReturn(Optional.empty());
        GraphExtractionResult extraction = result();

        // 无行时缓存未命中
        assertThat(cache(jdbc).findReady(source(), "kwiki-extractor-1",
                "graph-extraction-v1", "el-v1")).isEmpty();

        ArgumentCaptor<ResultSetExtractor<Optional<GraphExtractionResult>>> extractor =
                ArgumentCaptor.forClass((Class) ResultSetExtractor.class);
        verify(jdbc).query(contains("state = 'READY'"), extractor.capture(),
                eq(source().sourceChunkId()), eq("kwiki-extractor-1"),
                eq("graph-extraction-v1"), eq("el-v1"));

        ResultSet row = mock(ResultSet.class);
        when(row.next()).thenReturn(true);
        when(row.getString(1)).thenReturn(MAPPER.writeValueAsString(extraction.entities()));
        when(row.getString(2)).thenReturn(MAPPER.writeValueAsString(extraction.relations()));

        Optional<GraphExtractionResult> hit = extractor.getValue().extractData(row);
        assertThat(hit).isPresent();
        assertThat(hit.get().entities().get(0).entityId()).isEqualTo("e1");
        assertThat(hit.get().entities().get(0).canonicalName()).isEqualTo("Kwiki");
        assertThat(hit.get().relations().get(0).predicate()).isEqualTo(GraphPredicate.USES);
        assertThat(hit.get().extractorVersion()).isEqualTo("kwiki-extractor-1");
        assertThat(hit.get().promptVersion()).isEqualTo("graph-extraction-v1");
        assertThat(hit.get().entityLinkingVersion()).isEqualTo("el-v1");
        assertThat(hit.get().sourceChunk()).isEqualTo(source());
    }

    @Test
    void 查询只匹配READY状态与完整身份() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(any(String.class), any(ResultSetExtractor.class),
                any(), any(), any(), any())).thenReturn(Optional.empty());

        cache(jdbc).findReady(source(), "kwiki-extractor-1", "graph-extraction-v1", "el-v1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(ResultSetExtractor.class),
                any(), any(), any(), any());
        assertThat(sql.getValue()).contains("state = 'READY'");
    }

    @Test
    void 写回结果并置READY() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        cache(jdbc).markReady(11L, result());

        ArgumentCaptor<String> entitiesJson = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> relationsJson = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("state = 'READY'"), entitiesJson.capture(),
                relationsJson.capture(), eq(11L));
        List<?> entities = MAPPER.readValue(entitiesJson.getValue(), List.class);
        List<?> relations = MAPPER.readValue(relationsJson.getValue(), List.class);
        assertThat(entities).hasSize(1);
        assertThat(relations).hasSize(1);
    }

    @Test
    void 失败记录截断到列宽() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        cache(jdbc).markFailed(11L, "E".repeat(300), "S".repeat(2000));

        ArgumentCaptor<String> errorClass = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> errorSummary = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("state = 'FAILED'"), errorClass.capture(),
                errorSummary.capture(), eq(11L));
        assertThat(errorClass.getValue()).hasSize(200);
        assertThat(errorSummary.getValue()).hasSize(1000);
    }

    private static JdbcGraphExtractionCache cache(JdbcTemplate jdbc) {
        return new JdbcGraphExtractionCache(jdbc);
    }
}
