package com.kwiki.indexing.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;

/** 持久化结构门禁；摘要不包含凭据、原文或堆栈。 */
@Service
@ConditionalOnBean(SearchIndexVersionRepository.class)
public class SearchIndexValidationService {
    private final SearchIndexVersionRepository versions;
    private final SearchIndexRebuildRunRepository runs;
    private final SearchIndexRebuildRangeRepository ranges;
    private final SearchIndexValidationReportRepository reports;
    private final ElasticsearchIndexManager indexes;
    private final ChunkMappingBuilder mappings;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public SearchIndexValidationService(SearchIndexVersionRepository versions,
            SearchIndexRebuildRunRepository runs, SearchIndexRebuildRangeRepository ranges,
            SearchIndexValidationReportRepository reports,
            ElasticsearchIndexManager indexes, ChunkMappingBuilder mappings, ObjectMapper json,
            JdbcTemplate jdbc) {
        this.versions=versions; this.runs=runs; this.ranges=ranges; this.reports=reports;
        this.indexes=indexes; this.mappings=mappings; this.json=json; this.jdbc=jdbc;
    }

    @Transactional
    public SearchIndexValidationReport validate(int versionNumber) {
        SearchIndexVersion version=versions.findByVersionNumberForUpdate(versionNumber)
                .orElseThrow(() -> new IllegalArgumentException("unknown index version: "+versionNumber));
        SearchIndexRebuildRun run=runs.findFirstByVersionNumberAndConfigRevisionAndStateOrderByIdDesc(
                versionNumber,version.getConfigRevision(),RebuildRunState.COMPLETED.name()).orElse(null);
        boolean revision=version.getBuiltConfigRevision()!=null
                && version.getBuiltConfigRevision()==version.getConfigRevision();
        boolean manifest=false;
        String mappingError;
        try {
            if(run!=null){
                BuildManifestSnapshot frozen=json.readValue(run.getBuildManifest(),BuildManifestSnapshot.class);
                manifest=frozen.versionNumber()==versionNumber
                        && frozen.configRevision()==version.getConfigRevision()
                        && frozen.physicalName().equals(version.getPhysicalName())
                        && frozen.embeddingDimensions()==version.editableConfig().embeddingDimensions()
                        && frozen.mappingHash().equals(version.getMappingHash())
                        && frozen.mappingHash().equals(mappings.mappingHash(frozen.embeddingDimensions()));
            }
            mappingError=indexes.validateIndex(version.getPhysicalName(),
                    version.editableConfig().embeddingDimensions(),
                    version.editableConfig().mappingSchemaVersion());
        } catch(Exception failure){ mappingError=failure.getClass().getSimpleName(); }
        boolean mapping=mappingError==null;
        boolean vectorDimension=mapping || !mappingError.contains("dimension");
        boolean requiredFields=mapping || !mappingError.contains("required field")
                && !mappingError.contains("mappings") && !mappingError.contains("properties");
        List<ResourceIdentity> resources=effectiveResources();
        long missing=resources.stream().filter(resource -> !present(version,resource)).count();
        boolean coverage=missing==0;
        Diagnostics diagnostics=diagnostics(version,resources);
        boolean synchronizedState=synchronizedState(run);
        boolean smoke=smoke(version);
        String cursors=cursorFingerprint(run);
        String alias=aliasFingerprint();
        String summary=revision&&manifest&&mapping&&coverage&&diagnostics.valid
                &&synchronizedState&&smoke ? "validation passed"
                : "structure validation failed: revision="+revision+", manifest="+manifest
                +", mapping="+(mappingError==null?"valid":mappingError)
                +", missingResources="+missing+", staleDocuments="+diagnostics.stale
                +", orphanChildren="+diagnostics.orphans+", malformedVectors="
                +diagnostics.vectors+", mixedManifest="+diagnostics.mixed
                +", synchronized="+synchronizedState+", smoke="+smoke;
        Instant now=Instant.now();
        SearchIndexValidationReport report=reports.save(new SearchIndexValidationReport(
                versionNumber,run==null?null:run.getId(),version.getConfigRevision(),
                revision,manifest,mapping,vectorDimension,requiredFields,
                coverage,resources.size(),missing,diagnostics.valid,diagnostics.stale,
                diagnostics.orphans,diagnostics.vectors,diagnostics.mixed,
                synchronizedState,smoke,run==null?null:run.getCatchupBarrierEventId(),
                cursors,alias,summary,now));
        version.recordValidation(summary,now);
        return report;
    }

    /** 仅返回仍与当前数据库、积压、流水线和别名事实完全一致的 PASS 报告。 */
    @Transactional(readOnly=true)
    public Optional<SearchIndexValidationReport> currentReadyReport(int versionNumber){
        SearchIndexVersion version=versions.findByVersionNumber(versionNumber).orElse(null);
        SearchIndexValidationReport report=reports
                .findFirstByVersionNumberOrderByIdDesc(versionNumber).orElse(null);
        if(version==null||report==null||!"PASS".equals(report.getStatus())
                ||version.getBuiltConfigRevision()==null
                ||version.getBuiltConfigRevision()!=version.getConfigRevision()
                ||report.getConfigRevision()!=version.getConfigRevision()
                ||!version.isPipelineSupported()||version.getNeedsAttentionReason()!=null)return Optional.empty();
        SearchIndexRebuildRun run=report.getRunId()==null?null:runs.findById(report.getRunId()).orElse(null);
        if(run==null||run.switchState()!=IndexSwitchState.READY
                ||!Objects.equals(report.getBarrierEventId(),run.getCatchupBarrierEventId())
                ||!Objects.equals(report.getCursorFingerprint(),cursorFingerprint(run))
                ||!Objects.equals(report.getAliasFingerprint(),aliasFingerprint())
                ||!synchronizedState(run))return Optional.empty();
        return Optional.of(report);
    }

    private List<ResourceIdentity> effectiveResources(){
        List<ResourceIdentity> result=new ArrayList<>();
        result.addAll(jdbc.query("""
                SELECT p.id,p.current_published_revision_id,p.lifecycle_version
                FROM wiki_page p JOIN knowledge_base k ON k.id=p.kb_id
                WHERE p.node_type='PAGE' AND p.status='ACTIVE'
                  AND p.current_published_revision_id IS NOT NULL AND k.status='ACTIVE'
                """,(rs,row)->new ResourceIdentity("PAGE",rs.getLong(1),rs.getLong(2),rs.getLong(3))));
        result.addAll(jdbc.query("""
                SELECT a.id FROM attachment a JOIN knowledge_base k ON k.id=a.kb_id
                WHERE a.status='STORED' AND k.status='ACTIVE'
                  AND LOWER(a.content_type) IN ('image/png','image/jpeg','image/gif','image/webp')
                """,(rs,row)->new ResourceIdentity("ATTACHMENT",rs.getLong(1),null,0)));
        return result;
    }

    private boolean present(SearchIndexVersion version,ResourceIdentity resource){
        try{return indexes.hasCurrentResource(version.getPhysicalName(),resource.type,
                resource.id,resource.revisionId,resource.lifecycleVersion);}
        catch(Exception failure){return false;}
    }

    private Diagnostics diagnostics(SearchIndexVersion version,List<ResourceIdentity> resources){
        try{
            var snapshot=indexes.validationSnapshot(version.getPhysicalName(),5000);
            Map<String,ResourceIdentity> expected=new HashMap<>();
            for(var resource:resources) expected.put(resource.type+":"+resource.id,resource);
            Set<String> parents=new HashSet<>();
            for(var document:snapshot.documents()) if("PARENT".equals(document.get("chunkLevel")))
                parents.add(String.valueOf(document.get("chunkKey")));
            long stale=0,orphans=0,vectors=0,mixed=0;
            var config=version.editableConfig();
            for(Map<String,Object> document:snapshot.documents()){
                String type=String.valueOf(document.get("resourceType"));
                long id=number(document.get("resourceId"));
                ResourceIdentity current=expected.get(type+":"+id);
                Long revision=nullableNumber(document.get("revisionId"));
                long lifecycle=number(document.get("lifecycleVersion"));
                if(current==null||!Objects.equals(current.revisionId,revision)
                        ||current.lifecycleVersion!=lifecycle) stale++;
                if(!Objects.equals(document.get("parserVersion"),config.parserVersion())
                        ||!Objects.equals(document.get("chunkerVersion"),config.chunkerVersion())
                        ||!Objects.equals(document.get("embeddingModel"),config.embeddingModel())
                        ||number(document.get("indexVersion"))!=version.getVersionNumber()) mixed++;
                if("CHILD".equals(document.get("chunkLevel"))){
                    if(!parents.contains(String.valueOf(document.get("parentChunkKey")))) orphans++;
                    Object vector=document.get("vector");
                    if(!(vector instanceof List<?> values)
                            ||values.size()!=config.embeddingDimensions()) vectors++;
                }
            }
            boolean valid=!snapshot.truncated()&&stale==0&&orphans==0&&vectors==0&&mixed==0;
            return new Diagnostics(valid,stale,orphans,vectors,mixed);
        }catch(Exception failure){return new Diagnostics(false,0,0,0,0);}
    }

    private boolean synchronizedState(SearchIndexRebuildRun run){
        if(run==null||run.switchState()!=IndexSwitchState.READY||!run.replayComplete()
                ||run.getCatchupBarrierEventId()==null)return false;
        if(ranges.findByRunIdOrderByResourceType(run.getId()).stream()
                .anyMatch(range->!range.tailComplete()))return false;
        Long unresolved=jdbc.queryForObject("""
                SELECT COUNT(*) FROM indexing_job_target t JOIN indexing_job j ON j.id=t.job_id
                WHERE t.target_version=? AND t.state<>'COMPLETED'
                  AND (t.event_id<=? OR j.idempotency_key LIKE ?)
                """,Long.class,run.getVersionNumber(),run.getCatchupBarrierEventId(),
                "CATCHUP:"+run.getId()+":TAIL:%");
        return unresolved!=null&&unresolved==0;
    }

    private boolean smoke(SearchIndexVersion version){
        try{indexes.runValidationSmokeQueries(version.getPhysicalName(),
                version.editableConfig().embeddingDimensions());return true;}
        catch(Exception failure){return false;}
    }

    private String cursorFingerprint(SearchIndexRebuildRun run){
        if(run==null)return null;
        return ranges.findByRunIdOrderByResourceType(run.getId()).stream()
                .map(range->range.getResourceType()+":"+range.getTailLastSeenId()+":"+range.getTailMaxId())
                .sorted().collect(java.util.stream.Collectors.joining("|"));
    }

    private String aliasFingerprint(){
        try{return indexes.aliasTargets().stream().sorted()
                .collect(java.util.stream.Collectors.joining(","));}
        catch(Exception failure){return "<unavailable>";}
    }

    private static long number(Object value){return value instanceof Number n?n.longValue():-1;}
    private static Long nullableNumber(Object value){return value instanceof Number n?n.longValue():null;}

    private record ResourceIdentity(String type,long id,Long revisionId,long lifecycleVersion){}
    private record Diagnostics(boolean valid,long stale,long orphans,long vectors,long mixed){}
}
