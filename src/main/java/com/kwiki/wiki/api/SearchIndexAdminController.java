package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.indexing.gray.GrayRelease;
import com.kwiki.indexing.gray.GrayReleaseStatus;
import com.kwiki.indexing.gray.GrayReleaseStore;
import com.kwiki.indexing.gray.IndexVersionKbScope;
import com.kwiki.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import com.kwiki.indexing.version.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 搜索索引管理 API；类级角色校验是所有查询和命令的服务端边界。 */
@RestController
@RequestMapping("/api/v1/admin/search-indexes")
@PreAuthorize("hasRole('ADMIN')")
public class SearchIndexAdminController {
    private final SearchIndexAdminQueryService queries;
    private final SearchIndexAdminService admin;
    private final ManualIndexRebuildService rebuilds;
    private final RebuildRunControlService controls;
    private final SwitchPreparationService preparations;
    private final AliasSwitchService switches;
    private final SearchIndexValidationService validations;
    private final IndexVersionEnablementService enablement;
    private final SearchIndexDeletionService deletion;
    private final AdminCommandIdempotency commands;
    private final SearchIndexObservability observability;

    public SearchIndexAdminController(SearchIndexAdminQueryService queries,
            SearchIndexAdminService admin, ManualIndexRebuildService rebuilds,
            RebuildRunControlService controls, SwitchPreparationService preparations,
            AliasSwitchService switches, SearchIndexValidationService validations,
            IndexVersionEnablementService enablement, SearchIndexDeletionService deletion,
            AdminCommandIdempotency commands, SearchIndexObservability observability) {
        this.queries=queries;this.admin=admin;this.rebuilds=rebuilds;this.controls=controls;
        this.preparations=preparations;this.switches=switches;this.validations=validations;
        this.enablement=enablement;this.deletion=deletion;this.commands=commands;
        this.observability=observability;
    }

    static final String GRAY_VERSION_MESSAGE="该版本属于灰度发布，请在「灰度发布」页操作";

    /** 灰度相关依赖；未注入（离线测试）时视为不存在灰度。 */
    private IndexVersionKbScope kbScope;
    private GrayReleaseStore grayReleases;
    private SearchIndexVersionRepository versionRepository;

    @Autowired(required=false) public void setKbScope(IndexVersionKbScope kbScope){this.kbScope=kbScope;}
    @Autowired(required=false) public void setGrayReleases(GrayReleaseStore grayReleases){this.grayReleases=grayReleases;}
    @Autowired(required=false) public void setVersionRepository(SearchIndexVersionRepository versionRepository){this.versionRepository=versionRepository;}

    @GetMapping("/versions") public TransDTO<List<SearchIndexAdminQueryService.VersionView>> versions(){return TransDTO.success(queries.versions());}
    @GetMapping("/multimodal-readiness") public TransDTO<SearchIndexAdminQueryService.MultimodalReadinessView> multimodalReadiness(){return TransDTO.success(queries.multimodalReadiness());}
    @GetMapping("/alias") public TransDTO<SearchIndexAdminQueryService.AliasTruth> alias(){return TransDTO.success(queries.aliasTruth());}
    @GetMapping("/writable-targets") public TransDTO<List<SearchIndexAdminQueryService.VersionView>> writable(){return TransDTO.success(queries.writableTargets());}
    @GetMapping("/runs") public TransDTO<List<SearchIndexAdminQueryService.RunView>> runs(){return TransDTO.success(queries.runs());}
    @GetMapping("/validations") public TransDTO<List<SearchIndexAdminQueryService.ValidationView>> validationReports(){return TransDTO.success(queries.validations());}
    @GetMapping("/audits") public TransDTO<List<SearchIndexAdminQueryService.AuditView>> audits(){return TransDTO.success(queries.audits());}
    @GetMapping("/write-statistics") public TransDTO<List<Map<String,Object>>> statistics(){return TransDTO.success(queries.writeStatistics());}

    @PostMapping("/versions")
    public TransDTO<Map<String,Object>> create(@AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody EditableIndexConfig request){
        return TransDTO.success(command(key,"CREATE",null,user,()->version(admin.createVersion(request))));
    }

    @PutMapping("/versions/{version}")
    public TransDTO<Map<String,Object>> edit(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody EditableIndexConfig request){
        requireGlobalVersion(version);
        return TransDTO.success(command(key,"EDIT",version,user,()->version(admin.editVersion(version,request))));
    }

    @PostMapping("/versions/{version}/rebuild")
    public ResponseEntity<TransDTO<Map<String,Object>>> rebuild(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
        requireGlobalVersion(version);
        Map<String,Object> body=command(key,"REBUILD",version,user,()->{
            var result=rebuilds.rebuild(version,user.username());
            return map("accepted",result.accepted(),"runId",result.runId(),"resumed",result.resumed(),"code",result.code());});
        HttpStatus status=Boolean.FALSE.equals(body.get("accepted"))?HttpStatus.CONFLICT:HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(TransDTO.success(body));
    }

    @PostMapping("/runs/{runId}/{action:pause|resume|cancel}")
    public TransDTO<Map<String,Object>> control(@AuthenticationPrincipal CurrentUser user,
            @PathVariable long runId,@PathVariable String action,
            @RequestHeader("Idempotency-Key") String key){
        return TransDTO.success(command(key,"RUN_"+action.toUpperCase(),null,user,()->{
            switch(action){case "pause"->controls.pause(runId);case "resume"->controls.resume(runId);case "cancel"->controls.cancel(runId);default->throw new IllegalArgumentException("unsupported action");}
            return map("runId",runId,"state",action.toUpperCase()+"_REQUESTED");}));
    }

    @PostMapping("/versions/{version}/prepare")
    public TransDTO<Map<String,Object>> prepare(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
        requireGlobalVersion(version);
        return TransDTO.success(command(key,"PREPARE",version,user,()->{
            var value=preparations.prepare(version);
            return map("runId",value.runId(),"dualWriteStartEventId",value.dualWriteStartEventId(),"ranges",value.ranges());}));
    }

    @PostMapping("/versions/{version}/select")
    public ResponseEntity<TransDTO<Map<String,Object>>> select(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
        requireEmbeddingCompatibleWithGrayReleases(version);
        Map<String,Object> body=command(key,"SELECT",version,user,()->{
            var value=switches.select(version,user.username());
            return map("switched",value.switched(),"auditId",value.auditId(),"code",value.code());});
        HttpStatus status=Boolean.FALSE.equals(body.get("switched"))?HttpStatus.CONFLICT:HttpStatus.OK;
        return ResponseEntity.status(status).body(TransDTO.success(body));
    }

    @PostMapping("/versions/{version}/validate")
    public TransDTO<Map<String,Object>> validate(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
        return TransDTO.success(command(key,"VALIDATE",version,user,()->{
            var report=validations.validate(version);
            return map("reportId",report.getId(),"status",report.getStatus(),"summary",report.getSummary());}));
    }

    @PostMapping("/versions/{version}/disable")
    public TransDTO<Map<String,Object>> disable(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
        requireGlobalVersion(version);
        return TransDTO.success(command(key,"DISABLE",version,user,()->version(enablement.disable(version))));
    }

    @PostMapping("/versions/{version}/reenable")
    public TransDTO<Map<String,Object>> reenable(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
        requireGlobalVersion(version);
        return TransDTO.success(command(key,"REENABLE",version,user,()->{
            var value=enablement.reenable(version);
            return map("runId",value.runId(),"state","CATCHUP_PREPARING");}));
    }

    @DeleteMapping("/versions/{version}")
    public TransDTO<SearchIndexDeletionService.DeletionResult> delete(
            @AuthenticationPrincipal CurrentUser user,@PathVariable int version,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeleteRequest request){
        requireDeletableGrayVersion(version);
        return TransDTO.success(deletion.delete(version,request.confirmPhysicalName(),key,user.username()));
    }

    /**
     * 灰度版本只能由灰度发布页驱动：全局接口的编辑、重建、切换准备、停用与恢复一律拒绝。
     * 守卫放在控制器层，灰度服务内部直接调用下层服务不受影响。
     */
    private void requireGlobalVersion(int version){
        if(kbScope!=null&&kbScope.isScoped(version))throw new ConflictException(GRAY_VERSION_MESSAGE);
    }

    /** 灰度版本的索引只能在灰度结束后删除。 */
    private void requireDeletableGrayVersion(int version){
        if(kbScope==null||!kbScope.isScoped(version))return;
        boolean ended=grayReleases!=null&&grayReleases.findByIndexVersion(version)
                .map(release->release.status()==GrayReleaseStatus.ENDED).orElse(false);
        if(!ended)throw new ConflictException("该版本属于未结束的灰度发布，请先在「灰度发布」页结束灰度后再删除");
    }

    /**
     * 已切换的灰度与全局索引共用查询向量；全局切到 embedding 配置不同的版本会使灰度的 kNN 查询失配，
     * 故存在配置不同的未结束灰度时拒绝全局选择。
     */
    private void requireEmbeddingCompatibleWithGrayReleases(int version){
        if(grayReleases==null||versionRepository==null)return;
        SearchIndexVersion target=versionRepository.findByVersionNumber(version).orElse(null);
        if(target==null)return;
        EditableIndexConfig wanted=target.editableConfig();
        for(GrayRelease release:grayReleases.findAll()){
            if(release.status()==GrayReleaseStatus.ENDED)continue;
            boolean mismatch=versionRepository.findByVersionNumber(release.indexVersionNumber())
                    .map(gray->!sameEmbedding(gray.editableConfig(),wanted)).orElse(false);
            if(mismatch)throw new ConflictException("灰度「"+release.name()
                    +"」的向量模型配置与目标版本不同，请先结束该灰度再切换全局版本");
        }
    }

    private static boolean sameEmbedding(EditableIndexConfig a,EditableIndexConfig b){
        return java.util.Objects.equals(a.embeddingProvider(),b.embeddingProvider())
                &&java.util.Objects.equals(a.embeddingModel(),b.embeddingModel())
                &&java.util.Objects.equals(a.embeddingDimensions(),b.embeddingDimensions());
    }

    private Map<String,Object> command(String key,String action,Integer target,CurrentUser user,
                                       java.util.function.Supplier<Map<String,Object>> operation){
        long started=System.nanoTime();
        try{Map<String,Object> result=commands.execute(key,action,target,user.username(),operation);
            observability.command(action,target,longValue(result.get("runId")),"SUCCESS",started);return result;
        }catch(ConflictException busy){observability.command(action,target,null,"BUSY",started);throw busy;
        }catch(RuntimeException failure){observability.command(action,target,null,"FAILURE",started);throw failure;}
    }

    private static Map<String,Object> version(SearchIndexVersion value){return map(
            "versionNumber",value.getVersionNumber(),"physicalName",value.getPhysicalName(),
            "configRevision",value.getConfigRevision(),"builtConfigRevision",value.getBuiltConfigRevision(),
            "writeEnabled",value.isWriteEnabled(),"adminDisabled",value.isAdminDisabled());}
    private static Long longValue(Object value){return value instanceof Number number?number.longValue():null;}
    private static Map<String,Object> map(Object... values){Map<String,Object> result=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return result;}
    public record DeleteRequest(@NotBlank String confirmPhysicalName){}
}
