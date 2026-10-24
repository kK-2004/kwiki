package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.graph.config.GraphRuntimeSettings;
import com.kwiki.graph.persistence.GraphAdminCommandService;
import com.kwiki.graph.persistence.GraphAdminQueryService;
import com.kwiki.graph.persistence.GraphBuildBatchRequest;
import com.kwiki.graph.persistence.GraphBuildBatchService;
import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final com.kwiki.graph.persistence.GraphFreshnessService freshness;
    /** 热更新的发布/重建设置；提交与服务状态每次读取当前快照。 */
    private final GraphRuntimeSettings runtimeSettings;
    /** 批次目标解析；为 null（仅用于不涉及提交的测试装配）时提交接口不可用。 */
    private final com.kwiki.graph.persistence.GraphBuildTargets targets;

    public GraphBuildAdminController(GraphBuildBatchService batches,
                                     GraphAdminQueryService queries,
                                     GraphAdminCommandService commands,
                                     GraphBuildRepository repository,
                                     GraphProperties graphProperties,
                                     GraphRuntimeSettings runtimeSettings) {
        this(batches, queries, commands, repository, graphProperties, runtimeSettings, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GraphBuildAdminController(GraphBuildBatchService batches,
                                     GraphAdminQueryService queries,
                                     GraphAdminCommandService commands,
                                     GraphBuildRepository repository,
                                     GraphProperties graphProperties,
                                     GraphRuntimeSettings runtimeSettings,
                                     com.kwiki.graph.persistence.GraphBuildTargets targets,
                                     com.kwiki.graph.persistence.GraphFreshnessService freshness) {
        this.batches = batches;
        this.queries = queries;
        this.commands = commands;
        this.repository = repository;
        this.graphProperties = graphProperties;
        this.runtimeSettings = runtimeSettings;
        this.targets = targets;
        this.freshness = freshness;
    }

    /** 兼容旧测试装配：不注入新鲜度服务。 */
    public GraphBuildAdminController(GraphBuildBatchService batches,
                                     GraphAdminQueryService queries,
                                     GraphAdminCommandService commands,
                                     GraphBuildRepository repository,
                                     GraphProperties graphProperties,
                                     GraphRuntimeSettings runtimeSettings,
                                     com.kwiki.graph.persistence.GraphBuildTargets targets) {
        this(batches, queries, commands, repository, graphProperties, runtimeSettings, targets, null);
    }

    @PostMapping("/batches")
    public TransDTO<Map<String, Object>> submit(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody SubmitRequest request) {
        if (targets == null) {
            throw new IllegalStateException("graph build targets are unavailable");
        }
        // 只采信范围与 Chunk 版本号；物理索引名、结构版本、配置修订号与实体链接版本由服务端推导
        GraphBuildBatchRequest batch;
        try {
            batch = targets.request(idempotencyKey, request.scopeKind(), request.knowledgeBaseIds(),
                    request.chunkIndexVersion(), request.entityLinkingVersion(),
                    runtimeSettings.snapshot().autoPublish(), user.username(), request.scheduleDate(),
                    request.extractor(), request.communityDetector(), request.summarizer());
        } catch (IllegalArgumentException invalid) {
            throw new ConflictException(invalid.getMessage());
        }
        var result = batches.submit(batch);
        return TransDTO.success(Map.of(
                "batchId", result.batchId(),
                "chunkIndexSchema", batch.chunkIndexSchema(),
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

    /** 每库图新鲜度：门禁档位（管理员视角）、待重建起止时间、最近一次自动重建批次与结果。 */
    @GetMapping("/freshness")
    public TransDTO<List<com.kwiki.graph.persistence.GraphFreshnessService.KbFreshness>> freshness() {
        return TransDTO.success(freshness.freshness());
    }

    /** 服务与调度状态：开关、cron/时区、容量阈值、热更新的发布/重建设置与当前活动批次。 */
    @GetMapping("/service-status")
    public TransDTO<Map<String, Object>> serviceStatus() {
        GraphRuntimeSettings.Snapshot settings = runtimeSettings.snapshot();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", graphProperties.isEnabled());
        status.put("algorithmMode", graphProperties.algorithmMode().name());
        status.put("scheduleCron", graphProperties.scheduleCron());
        status.put("scheduleZone", graphProperties.scheduleZone());
        status.put("autoPublish", settings.autoPublish());
        status.put("rebuild", Map.of(
                "enabled", settings.rebuildEnabled(),
                "quietPeriod", settings.rebuildQuietPeriod().toString(),
                "maxDelay", settings.rebuildMaxDelay().toString(),
                "autoPublish", settings.rebuildAutoPublish()));
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

    /** 删除批次（子任务须已结束、快照不在发布中）；外部数据清理失败时可再次调用重试。 */
    @DeleteMapping("/batches/{batchId}")
    public TransDTO<Map<String, Object>> deleteBatch(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable long batchId) {
        try {
            return TransDTO.success(commands.execute(idempotencyKey, "BATCH_DELETE",
                    user.username(), batchId, null, () -> commands.deleteBatch(batchId, user.username())));
        } catch (IllegalStateException conflict) {
            throw new ConflictException(conflict.getMessage());
        }
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

    /**
     * 提交构建批次。ALL 范围忽略 knowledgeBaseIds，由服务端展开为全部活跃知识库；
     * chunkPhysicalIndex / configRevision 为兼容旧客户端保留，服务端不再采信；
     * entityLinkingVersion 可省略，若提供必须与流水线当前版本一致。
     */
    public record SubmitRequest(
            @NotBlank String scopeKind,
            List<@Min(1) Long> knowledgeBaseIds,
            @Min(1) int chunkIndexVersion,
            String chunkPhysicalIndex,
            Long configRevision,
            String entityLinkingVersion,
            LocalDate scheduleDate,
            String extractor,
            String communityDetector,
            String summarizer) {
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
