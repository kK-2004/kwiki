package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.security.CurrentUser;
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

    @GetMapping("/versions") public TransDTO<List<SearchIndexAdminQueryService.VersionView>> versions(){return TransDTO.success(queries.versions());}
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
        return TransDTO.success(command(key,"EDIT",version,user,()->version(admin.editVersion(version,request))));
    }

    @PostMapping("/versions/{version}/rebuild")
    public ResponseEntity<TransDTO<Map<String,Object>>> rebuild(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
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
        return TransDTO.success(command(key,"PREPARE",version,user,()->{
            var value=preparations.prepare(version);
            return map("runId",value.runId(),"dualWriteStartEventId",value.dualWriteStartEventId(),"ranges",value.ranges());}));
    }

    @PostMapping("/versions/{version}/select")
    public ResponseEntity<TransDTO<Map<String,Object>>> select(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
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
        return TransDTO.success(command(key,"DISABLE",version,user,()->version(enablement.disable(version))));
    }

    @PostMapping("/versions/{version}/reenable")
    public TransDTO<Map<String,Object>> reenable(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
        return TransDTO.success(command(key,"REENABLE",version,user,()->{
            var value=enablement.reenable(version);
            return map("runId",value.runId(),"state","CATCHUP_PREPARING");}));
    }

    @DeleteMapping("/versions/{version}")
    public TransDTO<SearchIndexDeletionService.DeletionResult> delete(
            @AuthenticationPrincipal CurrentUser user,@PathVariable int version,
            @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody DeleteRequest request){
        return TransDTO.success(deletion.delete(version,request.confirmPhysicalName(),key,user.username()));
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
