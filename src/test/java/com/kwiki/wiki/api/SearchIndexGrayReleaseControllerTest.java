package com.kwiki.wiki.api;

import com.kwiki.indexing.gray.GrayRelease;
import com.kwiki.indexing.gray.GrayReleaseService;
import com.kwiki.indexing.gray.GrayReleaseStatus;
import com.kwiki.indexing.gray.ParserCatalog;
import com.kwiki.indexing.version.AdminCommandIdempotency;
import com.kwiki.indexing.version.IndexSwitchState;
import com.kwiki.indexing.version.RebuildRunState;
import com.kwiki.indexing.version.SearchIndexRebuildRun;
import com.kwiki.indexing.version.SearchIndexRebuildRunRepository;
import com.kwiki.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchIndexGrayReleaseControllerTest {

    private final GrayReleaseService service = mock(GrayReleaseService.class);
    private final ParserCatalog parsers = mock(ParserCatalog.class);
    private final AdminCommandIdempotency commands = mock(AdminCommandIdempotency.class);
    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SearchIndexGrayReleaseController controller =
            new SearchIndexGrayReleaseController(service, parsers, commands, runs, jdbc);
    private final CurrentUser admin = new CurrentUser(1L, "admin", true);

    private GrayRelease release(GrayReleaseStatus status) {
        return release(status, null);
    }

    /** 让幂等执行器直接运行命令体，以便验证命令内部的异常映射。 */
    @SuppressWarnings("unchecked")
    private void executeDirectly() {
        when(commands.execute(any(), any(), any(), any(), any())).thenAnswer(invocation ->
                ((Supplier<Map<String, Object>>) invocation.getArgument(4)).get());
    }

    @Test
    void 列表视图带显示名_物理名与按状态计算的操作() {
        when(service.list()).thenReturn(List.of(release(GrayReleaseStatus.SYNCED)));
        when(runs.findFirstByVersionNumberOrderByIdDesc(4)).thenReturn(Optional.empty());
        var views = controller.list().getData();
        assertThat(views).hasSize(1);
        var view = views.get(0);
        assertThat(view.parserLabel()).isEqualTo("pdfbox-v2");
        assertThat(view.physicalName()).isEqualTo("kwiki-chunks-v4");
        assertThat(view.allowedActions()).containsEntry("switch", true).containsEntry("switchBack", false)
                .containsEntry("sync", false).containsEntry("end", true);
    }

    @Test
    void 列表视图的物理名取自版本行() {
        var versions = mock(com.kwiki.indexing.version.SearchIndexVersionRepository.class);
        var row = mock(com.kwiki.indexing.version.SearchIndexVersion.class);
        when(row.getPhysicalName()).thenReturn("kwiki-chunks-v4-r2");
        when(versions.findByVersionNumber(4)).thenReturn(Optional.of(row));
        controller.setVersions(versions);
        when(service.list()).thenReturn(List.of(release(GrayReleaseStatus.SYNCED)));
        when(runs.findFirstByVersionNumberOrderByIdDesc(4)).thenReturn(Optional.empty());
        assertThat(controller.list().getData().get(0).physicalName()).isEqualTo("kwiki-chunks-v4-r2");

        when(versions.findByVersionNumber(4)).thenReturn(Optional.empty());
        assertThat(controller.list().getData().get(0).physicalName()).isEqualTo("kwiki-chunks-v4");
    }

    private GrayRelease release(GrayReleaseStatus status, String lastError) {
        return new GrayRelease(1, "pdfbox-v2 灰度 #1", "kwiki-parse-2", 4, status, lastError, "admin",
                Instant.EPOCH, null, null, List.of(new GrayRelease.Kb(7, "产品文档")));
    }

    private SearchIndexRebuildRun run(RebuildRunState state, IndexSwitchState switchState) {
        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(run.state()).thenReturn(state);
        when(run.switchState()).thenReturn(switchState);
        return run;
    }

    @Test
    void 已切换时只能切回或结束() {
        var actions = SearchIndexGrayReleaseController.actions(release(GrayReleaseStatus.SWITCHED), null);
        assertThat(actions).isEqualTo(Map.of("sync", false, "switch", false, "switchBack", true, "end", true));
        assertThat(SearchIndexGrayReleaseController.actions(release(GrayReleaseStatus.ENDED), null))
                .isEqualTo(Map.of("sync", false, "switch", false, "switchBack", false, "end", false));
        assertThat(SearchIndexGrayReleaseController.actions(release(GrayReleaseStatus.CREATED), null))
                .containsEntry("sync", true).containsEntry("end", true);
    }

    @Test
    void 同步中仅在出错后允许重新同步() {
        assertThat(SearchIndexGrayReleaseController.actions(release(GrayReleaseStatus.SYNCING), null))
                .containsEntry("sync", false);
        assertThat(SearchIndexGrayReleaseController.actions(release(GrayReleaseStatus.SYNCING, "重建失败"), null))
                .containsEntry("sync", true);
    }

    @Test
    void 重建运行活跃或切换准备中不允许结束() {
        var syncing = release(GrayReleaseStatus.SYNCING);
        assertThat(SearchIndexGrayReleaseController.actions(syncing,
                run(RebuildRunState.RUNNING, IndexSwitchState.NONE))).containsEntry("end", false);
        assertThat(SearchIndexGrayReleaseController.actions(syncing,
                run(RebuildRunState.COMPLETED, IndexSwitchState.PREPARING))).containsEntry("end", false);
        assertThat(SearchIndexGrayReleaseController.actions(syncing,
                run(RebuildRunState.COMPLETED, IndexSwitchState.READY))).containsEntry("end", true);
    }

    @Test
    void 列表视图按最近运行计算结束操作() {
        when(service.list()).thenReturn(List.of(release(GrayReleaseStatus.SYNCING)));
        var active = run(RebuildRunState.RUNNING, IndexSwitchState.NONE);
        when(runs.findFirstByVersionNumberOrderByIdDesc(4)).thenReturn(Optional.of(active));
        var view = controller.list().getData().get(0);
        assertThat(view.progress().runState()).isEqualTo("RUNNING");
        assertThat(view.allowedActions()).containsEntry("end", false).containsEntry("sync", false);
    }

    @Test
    void 命令经幂等执行器并以灰度版本号审计() {
        when(service.find(1)).thenReturn(release(GrayReleaseStatus.SYNCED));
        when(commands.execute(eq("k1"), eq("GRAY_SWITCH"), eq(4), eq("admin"), any()))
                .thenReturn(Map.of("id", 1L, "status", "SWITCHED"));
        var body = controller.switchTo(admin, 1, "k1").getData();
        assertThat(body).containsEntry("status", "SWITCHED");
    }

    @Test
    void 下层写操作关闭异常映射为中文冲突且不含英文原文() {
        executeDirectly();
        when(service.find(1)).thenReturn(release(GrayReleaseStatus.CREATED));
        when(service.sync(1, "admin")).thenThrow(new IllegalStateException("index management mutations are disabled"));
        assertThatThrownBy(() -> controller.sync(admin, 1, "k2"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("索引管理写操作已被关闭（kwiki.indexing.management.mutations-enabled）");
    }

    @Test
    void 下层容量异常映射为中文冲突() {
        executeDirectly();
        when(service.create(any(), eq("kwiki-parse-2"), eq(List.of(7L)), eq("admin")))
                .thenThrow(new IllegalStateException("index capacity exceeded"));
        var request = new SearchIndexGrayReleaseController.CreateRequest(null, "kwiki-parse-2", List.of(7L));
        assertThatThrownBy(() -> controller.create(admin, "k3", request))
                .isInstanceOf(ConflictException.class)
                .hasMessage("索引容量不足，请先清理不再使用的索引版本");
    }

    @Test
    void 其他下层状态或参数异常映射为通用中文冲突() {
        executeDirectly();
        when(service.find(1)).thenReturn(release(GrayReleaseStatus.SYNCED));
        when(service.switchTo(1)).thenThrow(new IllegalArgumentException("unknown index version 4"));
        when(service.end(1)).thenThrow(new IllegalStateException("version is not eligible"));
        assertThatThrownBy(() -> controller.switchTo(admin, 1, "k4"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("操作未能完成：索引状态不满足条件，请刷新后重试")
                .message().doesNotContain("unknown");
        assertThatThrownBy(() -> controller.end(admin, 1, "k5"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("操作未能完成：索引状态不满足条件，请刷新后重试");
    }

    @Test
    void 服务层中文冲突原样透传() {
        executeDirectly();
        when(service.find(1)).thenReturn(release(GrayReleaseStatus.SWITCHED));
        when(service.switchBack(1)).thenThrow(new ConflictException("灰度未处于已切换状态"));
        assertThatThrownBy(() -> controller.switchBack(admin, 1, "k6"))
                .isInstanceOf(ConflictException.class).hasMessage("灰度未处于已切换状态");
    }
}
