package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.graph.persistence.GraphAdminCommandService;
import com.kwiki.graph.persistence.GraphAdminQueryService;
import com.kwiki.graph.persistence.GraphBuildBatchRequest;
import com.kwiki.graph.persistence.GraphBuildBatchService;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图构建管理入口；沿用现有后台认证和 ROLE_ADMIN，写操作要求幂等键并审计，
 * 长操作返回 batchId/runId；响应不包含凭据、正文或隐私实体名。
 */
@RestController
@RequestMapping("/api/v1/admin/knowledge-graphs")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphBuildAdminController {

    private final GraphBuildBatchService batches;
    private final GraphAdminQueryService queries;
    private final GraphAdminCommandService commands;
    private final GraphBuildRepository repository;
    private final GraphProperties graphProperties;

    public GraphBuildAdminController(GraphBuildBatchService batches,
                                     GraphAdminQueryService queries,
                                     GraphAdminCommandService commands,
                                     GraphBuildRepository repository,
                                     GraphProperties graphProperties) {
        this.batches = batches;
        this.queries = queries;
        this.commands = commands;
        this.repository = repository;
        this.graphProperties = graphProperties;
    }

    @PostMapping("/batches")
    public TransDTO<Map<String, Object>> submit(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitRequest request) {
        var result = batches.submit(new GraphBuildBatchRequest(
                idempotencyKey, request.scopeKind(), request.knowledgeBaseIds(),
                request.chunkIndexVersion(), request.chunkPhysicalIndex(),
                request.mappingSchemaVersion(), request.configRevision(),
                request.entityLinkingVersion(), graphProperties.autoPublish(),
                user.username(), request.scheduleDate()));
        return TransDTO.success(Map.of(
                "batchId", result.batchId(),
                "communityIndexVersion", result.communityIndexVersion(),
                "runIds", result.runIds(),
                "replayed", result.replayed()));
    }

    // ---- 查询视图（13.1/13.2/13.5） ----

    @GetMapping("/batches")
    public TransDTO<List<Map<String, Object>>> batches() {
        return TransDTO.success(queries.batches());
    }

    @GetMapping("/batches/{batchId}/runs")
    public TransDTO<List<Map<String, Object>>> runs(@PathVariable long batchId) {
        return TransDTO.success(queries.runsOfBatch(batchId));
    }

    @GetMapping("/schedule")
    public TransDTO<List<Map<String, Object>>> schedule() {
        return TransDTO.success(queries.scheduleTriggers());
    }

    @GetMapping("/publications")
    public TransDTO<List<Map<String, Object>>> publications() {
        return TransDTO.success(queries.publications());
    }

    @GetMapping("/snapshots")
    public TransDTO<List<Map<String, Object>>> snapshots() {
        return TransDTO.success(queries.snapshots());
    }

    @GetMapping("/snapshots/{snapshotId}/validation")
    public TransDTO<Map<String, Object>> validation(@PathVariable long snapshotId) {
        return TransDTO.success(queries.validationReport(snapshotId));
    }

    @GetMapping("/community-versions")
    public TransDTO<List<Map<String, Object>>> communityVersions() {
        return TransDTO.success(queries.communityVersions());
    }

    @GetMapping("/audits")
    public TransDTO<List<Map<String, Object>>> audits() {
        return TransDTO.success(queries.audits());
    }

    /** 服务与调度状态：开关、cron/时区、容量阈值与当前活动批次。 */
    @GetMapping("/service-status")
    public TransDTO<Map<String, Object>> serviceStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", graphProperties.isEnabled());
        status.put("algorithmMode", graphProperties.algorithmMode().name());
        status.put("scheduleCron", graphProperties.scheduleCron());
        status.put("scheduleZone", graphProperties.scheduleZone());
        status.put("autoPublish", graphProperties.autoPublish());
        status.put("capacity", Map.of(
                "maxEntities", graphProperties.capacity().maxEntities(),
                "maxRelations", graphProperties.capacity().maxRelations(),
                "maxRelationSources", graphProperties.capacity().maxRelationSources(),
                "maxCommunities", graphProperties.capacity().maxCommunities(),
                "warnEntities", graphProperties.capacity().warnEntities(),
                "warnRelations", graphProperties.capacity().warnRelations()));
        status.put("activeBatchId", repository.findActiveBatchId().orElse(-1L));
        return TransDTO.success(status);
    }

    // ---- 幂等命令（重试/取消/发布/回滚/清理） ----

    @PostMapping("/runs/{runId}/retry")
    public TransDTO<Map<String, Object>> retry(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable long runId) {
        return TransDTO.success(commands.execute(idempotencyKey, "RUN_RETRY",
                user.username(), null, runId, () -> commands.retry(runId)));
    }

    @PostMapping("/runs/{runId}/cancel")
    public TransDTO<Map<String, Object>> cancel(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable long runId) {
        return TransDTO.success(commands.execute(idempotencyKey, "RUN_CANCEL",
                user.username(), null, runId, () -> commands.cancel(runId)));
    }

    @PostMapping("/snapshots/{snapshotId}/publish")
    public TransDTO<Map<String, Object>> publish(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable long snapshotId,
            @Valid @RequestBody PublishRequest request) {
        return TransDTO.success(commands.execute(idempotencyKey, "SNAPSHOT_PUBLISH",
                user.username(), null, null,
                () -> commands.publish(request.kbId(), request.chunkIndexVersion(),
                        snapshotId, request.expectedSnapshotId())));
    }

    @PostMapping("/publications/rollback")
    public TransDTO<Map<String, Object>> rollback(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RollbackRequest request) {
        return TransDTO.success(commands.execute(idempotencyKey, "PUBLICATION_ROLLBACK",
                user.username(), null, null,
                () -> commands.rollback(request.kbId(), request.chunkIndexVersion(),
                        request.targetSnapshotId(), request.expectedSnapshotId())));
    }

    @PostMapping("/snapshots/{snapshotId}/retire")
    public TransDTO<Map<String, Object>> retire(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable long snapshotId) {
        return TransDTO.success(commands.execute(idempotencyKey, "SNAPSHOT_RETIRE",
                user.username(), null, null, () -> commands.retire(snapshotId)));
    }

    @PostMapping("/snapshots/{snapshotId}/cleanup")
    public TransDTO<Map<String, Object>> cleanup(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable long snapshotId) {
        return TransDTO.success(commands.execute(idempotencyKey, "SNAPSHOT_CLEANUP",
                user.username(), null, null, () -> commands.cleanup(snapshotId)));
    }

    public record SubmitRequest(
            @NotBlank String scopeKind,
            @NotEmpty List<@Min(1) Long> knowledgeBaseIds,
            @Min(1) int chunkIndexVersion,
            @NotBlank String chunkPhysicalIndex,
            @Min(1) int mappingSchemaVersion,
            @Min(1) long configRevision,
            @NotBlank String entityLinkingVersion,
            LocalDate scheduleDate) {
    }

    public record PublishRequest(@NotNull @Min(1) Long kbId,
                                 @NotNull @Min(1) Integer chunkIndexVersion,
                                 Long expectedSnapshotId) {
    }

    public record RollbackRequest(@NotNull @Min(1) Long kbId,
                                  @NotNull @Min(1) Integer chunkIndexVersion,
                                  @NotNull @Min(1) Long targetSnapshotId,
                                  Long expectedSnapshotId) {
    }
}
