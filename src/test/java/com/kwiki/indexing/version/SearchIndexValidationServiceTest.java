package com.kwiki.indexing.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;

class SearchIndexValidationServiceTest {
    @Test
    void matchingRevisionManifestMappingDimensionsAndFieldsArePersistedAsPass() throws Exception {
        var versions=mock(SearchIndexVersionRepository.class);
        var runs=mock(SearchIndexRebuildRunRepository.class);
        var reports=mock(SearchIndexValidationReportRepository.class);
        var ranges=mock(SearchIndexRebuildRangeRepository.class);
        var indexes=mock(ElasticsearchIndexManager.class);
        var mappings=new ChunkMappingBuilder();
        var jdbc=mock(JdbcTemplate.class);
        var config=new EditableIndexConfig("parser","chunker","default","model",1024,1);
        String hash=mappings.mappingHash(1024);
        SearchIndexVersion version=SearchIndexVersion.bootstrapped(2,"kwiki-chunks-v2",config,hash);
        BuildManifestSnapshot manifest=version.buildManifestSnapshot();
        SearchIndexRebuildRun run=SearchIndexRebuildRun.create(2,1,RebuildRunKind.INITIAL,1,
                new ObjectMapper().writeValueAsString(manifest),"admin","owner",
                Duration.ofMinutes(1),0,Instant.EPOCH);
        run.complete(Instant.EPOCH);
        run.startSwitchPreparation(0,Instant.EPOCH);
        run.captureCatchupBarrier(0,Instant.EPOCH);
        run.markSwitchReady(Instant.EPOCH);
        ReflectionTestUtils.setField(run,"id",91L);
        SearchIndexRebuildRange range=new SearchIndexRebuildRange(91,"PAGE",0,0);
        range.captureTailUpperBound(0);
        when(versions.findByVersionNumberForUpdate(2)).thenReturn(Optional.of(version));
        when(runs.findFirstByVersionNumberAndConfigRevisionAndStateOrderByIdDesc(2,1,"COMPLETED"))
                .thenReturn(Optional.of(run));
        when(indexes.validateIndex(eq("kwiki-chunks-v2"), eq(1024), anyInt())).thenReturn(null);
        when(indexes.hasCurrentResource("kwiki-chunks-v2","PAGE",7L,103L,2L))
                .thenReturn(true);
        when(indexes.validationSnapshot("kwiki-chunks-v2",5000))
                .thenReturn(new ElasticsearchIndexManager.ValidationSnapshot(
                        2,List.of(validDocument("PARENT",null),
                                validDocument("CHILD","parent")),false));
        when(reports.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(ranges.findByRunIdOrderByResourceType(91L)).thenReturn(List.of(range));
        when(jdbc.queryForObject(any(String.class),org.mockito.ArgumentMatchers.eq(Long.class),
                any(Object[].class))).thenReturn(0L);
        when(jdbc.query(contains("FROM wiki_page"),
                any(org.springframework.jdbc.core.RowMapper.class))).thenAnswer(invocation->{
            @SuppressWarnings("unchecked")
            org.springframework.jdbc.core.RowMapper<Object> mapper=invocation.getArgument(1);
            java.sql.ResultSet rs=mock(java.sql.ResultSet.class);
            when(rs.getLong(1)).thenReturn(7L); when(rs.getLong(2)).thenReturn(103L);
            when(rs.getLong(3)).thenReturn(2L);
            return List.of(mapper.mapRow(rs,0));
        });
        when(jdbc.query(contains("FROM attachment"),
                any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(List.of());

        SearchIndexValidationService service=new SearchIndexValidationService(
                versions,runs,ranges,reports,indexes,mappings,new ObjectMapper(),jdbc);
        SearchIndexValidationReport report=service.validate(2);

        assertThat(report.getStatus()).isEqualTo("PASS");
        assertThat(report.isRevisionValid()).isTrue();
        assertThat(report.isManifestValid()).isTrue();
        assertThat(report.isVectorDimensionValid()).isTrue();
        assertThat(report.isRequiredFieldsValid()).isTrue();
        assertThat(report.getSourceResources()).isEqualTo(1);
        assertThat(report.getMissingResources()).isZero();
        verify(indexes,never()).activateAlias(anyString());
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        when(reports.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(report));
        when(runs.findById(91L)).thenReturn(Optional.of(run));
        assertThat(service.currentReadyReport(2)).contains(report);

        when(indexes.aliasTargets()).thenReturn(List.of("kwiki-chunks-v9"));
        assertThat(service.currentReadyReport(2)).isEmpty();
    }

    @Test
    void dimensionMismatchIsPersistedAsHardFailure() throws Exception {
        var versions=mock(SearchIndexVersionRepository.class);
        var runs=mock(SearchIndexRebuildRunRepository.class);
        var reports=mock(SearchIndexValidationReportRepository.class);
        var ranges=mock(SearchIndexRebuildRangeRepository.class);
        var indexes=mock(ElasticsearchIndexManager.class);
        var mappings=new ChunkMappingBuilder();
        var jdbc=mock(JdbcTemplate.class);
        var config=new EditableIndexConfig("parser","chunker","default","model",1024,1);
        SearchIndexVersion version=SearchIndexVersion.bootstrapped(
                2,"kwiki-chunks-v2",config,mappings.mappingHash(1024));
        when(versions.findByVersionNumberForUpdate(2)).thenReturn(Optional.of(version));
        when(runs.findFirstByVersionNumberAndConfigRevisionAndStateOrderByIdDesc(2,1,"COMPLETED"))
                .thenReturn(Optional.empty());
        when(indexes.validateIndex(eq("kwiki-chunks-v2"), eq(1024), anyInt()))
                .thenReturn("vector dimension mismatch: expected 1024");
        when(indexes.validationSnapshot("kwiki-chunks-v2",5000))
                .thenReturn(new ElasticsearchIndexManager.ValidationSnapshot(
                        0,java.util.List.of(),false));
        when(reports.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(jdbc.query(any(String.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(java.util.List.of());

        SearchIndexValidationReport report=new SearchIndexValidationService(
                versions,runs,ranges,reports,indexes,mappings,new ObjectMapper(),jdbc).validate(2);

        assertThat(report.getStatus()).isEqualTo("FAIL");
        assertThat(report.isVectorDimensionValid()).isFalse();
        assertThat(report.isManifestValid()).isFalse();
    }

    @Test
    void staleResourceOrphanChildMalformedVectorAndMixedManifestAreHardFailures() throws Exception {
        var versions=mock(SearchIndexVersionRepository.class);
        var runs=mock(SearchIndexRebuildRunRepository.class);
        var reports=mock(SearchIndexValidationReportRepository.class);
        var ranges=mock(SearchIndexRebuildRangeRepository.class);
        var indexes=mock(ElasticsearchIndexManager.class);
        var jdbc=mock(JdbcTemplate.class);
        var mappings=new ChunkMappingBuilder();
        var config=new EditableIndexConfig("parser","chunker","default","model",1024,1);
        SearchIndexVersion version=SearchIndexVersion.bootstrapped(
                2,"kwiki-chunks-v2",config,mappings.mappingHash(1024));
        when(versions.findByVersionNumberForUpdate(2)).thenReturn(Optional.of(version));
        when(runs.findFirstByVersionNumberAndConfigRevisionAndStateOrderByIdDesc(2,1,"COMPLETED"))
                .thenReturn(Optional.empty());
        when(indexes.validateIndex(eq("kwiki-chunks-v2"), eq(1024), anyInt())).thenReturn(null);
        java.util.Map<String,Object> child=new java.util.HashMap<>();
        child.put("chunkLevel","CHILD"); child.put("chunkKey","stale:C0");
        child.put("parentChunkKey","missing-parent"); child.put("resourceType","PAGE");
        child.put("resourceId",99L); child.put("revisionId",7L);
        child.put("lifecycleVersion",3L); child.put("parserVersion","old-parser");
        child.put("chunkerVersion","chunker"); child.put("embeddingModel","model");
        child.put("indexVersion",2); child.put("vector",java.util.List.of(0.1));
        when(indexes.validationSnapshot("kwiki-chunks-v2",5000))
                .thenReturn(new ElasticsearchIndexManager.ValidationSnapshot(1,List.of(child),false));
        when(jdbc.query(any(String.class),any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());
        when(reports.save(any())).thenAnswer(invocation->invocation.getArgument(0));

        SearchIndexValidationReport report=new SearchIndexValidationService(
                versions,runs,ranges,reports,indexes,mappings,new ObjectMapper(),jdbc).validate(2);

        assertThat(report.isIntegrityValid()).isFalse();
        assertThat(report.getStaleDocuments()).isEqualTo(1);
        assertThat(report.getOrphanChildren()).isEqualTo(1);
        assertThat(report.getMalformedVectors()).isEqualTo(1);
        assertThat(report.getMixedManifestDocuments()).isEqualTo(1);
        verify(indexes,never()).activateAlias(anyString());
    }

    private static java.util.Map<String,Object> validDocument(String level,String parentKey){
        java.util.Map<String,Object> document=new java.util.HashMap<>();
        document.put("chunkLevel",level); document.put("chunkKey",
                "PARENT".equals(level)?"parent":"child");
        document.put("parentChunkKey",parentKey); document.put("resourceType","PAGE");
        document.put("resourceId",7L); document.put("revisionId",103L);
        document.put("lifecycleVersion",2L); document.put("parserVersion","parser");
        document.put("chunkerVersion","chunker"); document.put("embeddingModel","model");
        document.put("indexVersion",2);
        if("CHILD".equals(level)) document.put("vector",
                java.util.Collections.nCopies(1024,0.1));
        return document;
    }
}
