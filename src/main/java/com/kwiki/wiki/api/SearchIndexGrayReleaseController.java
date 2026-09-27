package com.kwiki.wiki.api;

import com.kk2004.common.response.TransDTO;
import com.kwiki.indexing.gray.GrayRelease;
import com.kwiki.indexing.gray.GrayReleaseService;
import com.kwiki.indexing.gray.GrayReleaseStatus;
import com.kwiki.indexing.gray.ParserCatalog;
import com.kwiki.indexing.version.AdminCommandIdempotency;
import com.kwiki.indexing.version.IndexSwitchState;
import com.kwiki.indexing.version.SearchIndexRebuildRun;
import com.kwiki.indexing.version.SearchIndexRebuildRunRepository;
import com.kwiki.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** 索引灰度发布管理 API；类级角色校验是所有查询和命令的服务端边界。 */
@RestController
@RequestMapping("/api/v1/admin/search-indexes")
@PreAuthorize("hasRole('ADMIN')")
public class SearchIndexGrayReleaseController {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexGrayReleaseController.class);

    private final GrayReleaseService service;
    private final ParserCatalog parsers;
    private final AdminCommandIdempotency commands;
    private final SearchIndexRebuildRunRepository runs;
    private final JdbcTemplate jdbc;

    public SearchIndexGrayReleaseController(GrayReleaseService service, ParserCatalog parsers,
                                            AdminCommandIdempotency commands,
                                            SearchIndexRebuildRunRepository runs, JdbcTemplate jdbc) {
        this.service = service;
        this.parsers = parsers;
        this.commands = commands;
        this.runs = runs;
        this.jdbc = jdbc;
    }

    public record CreateRequest(String name, @NotBlank String parserVersion, @NotEmpty List<Long> kbIds) { }

    public record Progress(Long runId, String runState, String switchState, long scanned, long succeeded,
                           long failed, long pendingTargets) { }

    public record GrayReleaseView(long id, String name, String parserVersion, String parserLabel,
                                  int indexVersionNumber, String physicalName, String status, String lastError,
                                  String createdBy, Instant createdAt, Instant switchedAt, Instant endedAt,
                                  List<GrayRelease.Kb> kbs, Progress progress, Map<String, Boolean> allowedActions) { }

    @GetMapping("/parsers")
    public TransDTO<List<ParserCatalog.ParserOption>> parsers() {
        return TransDTO.success(parsers.options());
    }

    @GetMapping("/gray-releases")
    public TransDTO<List<GrayReleaseView>> list() {
        return TransDTO.success(service.list().stream().map(this::view).toList());
    }

    @PostMapping("/gray-releases")
    public TransDTO<Map<String, Object>> create(@AuthenticationPrincipal CurrentUser user,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody CreateRequest request) {
        return TransDTO.success(commands.execute(key, "GRAY_CREATE", null, user.username(), () ->
                summary(translated(() -> service.create(request.name(), request.parserVersion(),
                        request.kbIds(), user.username())))));
    }

    @PostMapping("/gray-releases/{id}/sync")
    public TransDTO<Map<String, Object>> sync(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                              @RequestHeader("Idempotency-Key") String key) {
        return command(user, id, key, "GRAY_SYNC", releaseId -> service.sync(releaseId, user.username()));
    }

    @PostMapping("/gray-releases/{id}/switch")
    public TransDTO<Map<String, Object>> switchTo(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                                  @RequestHeader("Idempotency-Key") String key) {
        return command(user, id, key, "GRAY_SWITCH", service::switchTo);
    }

    @PostMapping("/gray-releases/{id}/switch-back")
    public TransDTO<Map<String, Object>> switchBack(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                                    @RequestHeader("Idempotency-Key") String key) {
        return command(user, id, key, "GRAY_SWITCH_BACK", service::switchBack);
    }

    @PostMapping("/gray-releases/{id}/end")
    public TransDTO<Map<String, Object>> end(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                             @RequestHeader("Idempotency-Key") String key) {
        return command(user, id, key, "GRAY_END", service::end);
    }

    /**
     * 服务端按状态计算可执行操作，前端只按此显示按钮；与 GrayReleaseService 的守卫保持一致：
     * 同步中只有出错后才允许重新同步，重建运行活跃或切换准备中不允许结束。
     */
    static Map<String, Boolean> actions(GrayRelease release, SearchIndexRebuildRun run) {
        GrayReleaseStatus status = release.status();
        boolean runBusy = run != null
                && (run.state().active() || run.switchState() == IndexSwitchState.PREPARING);
        return Map.of(
                "sync", status == GrayReleaseStatus.CREATED
                        || (status == GrayReleaseStatus.SYNCING && release.lastError() != null),
                "switch", status == GrayReleaseStatus.SYNCED,
                "switchBack", status == GrayReleaseStatus.SWITCHED,
                "end", status != GrayReleaseStatus.ENDED && !runBusy);
    }

    private TransDTO<Map<String, Object>> command(CurrentUser user, long id, String key, String action,
                                                  Function<Long, GrayRelease> operation) {
        int version = service.find(id).indexVersionNumber();
        return TransDTO.success(commands.execute(key, action, version, user.username(),
                () -> summary(translated(() -> operation.apply(id)))));
    }

    /**
     * 下层索引服务（重建、建版本等）以英文状态/参数异常表达冲突；灰度接口统一转为中文冲突，
     * 英文原文只写服务端日志，避免在管理端展示无法理解的原始信息。
     */
    static GrayRelease translated(Supplier<GrayRelease> operation) {
        try {
            return operation.get();
        } catch (IllegalStateException | IllegalArgumentException failure) {
            log.warn("gray release command rejected by lower layer type={} message={}",
                    failure.getClass().getSimpleName(), failure.getMessage(), failure);
            throw new ConflictException(chineseMessage(failure.getMessage()));
        }
    }

    private static String chineseMessage(String message) {
        String text = message == null ? "" : message;
        if (text.contains("mutations")) {
            return "索引管理写操作已被关闭（kwiki.indexing.management.mutations-enabled）";
        }
        if (text.contains("capacity")) {
            return "索引容量不足，请先清理不再使用的索引版本";
        }
        return "操作未能完成：索引状态不满足条件，请刷新后重试";
    }

    private static Map<String, Object> summary(GrayRelease release) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", release.id());
        result.put("status", release.status().name());
        result.put("indexVersionNumber", release.indexVersionNumber());
        return result;
    }

    private GrayReleaseView view(GrayRelease release) {
        int version = release.indexVersionNumber();
        SearchIndexRebuildRun run = runs.findFirstByVersionNumberOrderByIdDesc(version).orElse(null);
        // 双写积压：该灰度版本尚未完成的写入目标数
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM indexing_job_target WHERE target_version = ?"
                + " AND state IN ('PENDING','LEASED','RETRY_WAIT')", Long.class, version);
        Progress progress = new Progress(run == null ? null : run.getId(),
                run == null ? null : run.state().name(), run == null ? null : run.switchState().name(),
                run == null ? 0 : run.getResourcesScanned(), run == null ? 0 : run.getResourcesSucceeded(),
                run == null ? 0 : run.getResourcesFailed(), pending == null ? 0 : pending);
        return new GrayReleaseView(release.id(), release.name(), release.parserVersion(),
                ParserCatalog.label(release.parserVersion()), version, "kwiki-chunks-v" + version,
                release.status().name(), release.lastError(), release.createdBy(), release.createdAt(),
                release.switchedAt(), release.endedAt(), release.kbs(), progress, actions(release, run));
    }
}
