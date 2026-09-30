package com.kwiki.wiki.api;

import com.kwiki.indexing.gray.GrayRelease;
import com.kwiki.indexing.gray.GrayReleaseStatus;
import com.kwiki.indexing.gray.GrayReleaseStore;
import com.kwiki.indexing.gray.IndexVersionKbScope;
import com.kwiki.indexing.version.*;
import com.kwiki.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** 全局索引管理接口对灰度版本的服务端守卫。 */
class SearchIndexAdminControllerGrayGuardTest {

    private final SearchIndexAdminService admin = mock(SearchIndexAdminService.class);
    private final IndexVersionWriteService writes = mock(IndexVersionWriteService.class);
    private final IndexMigrationService migrations = mock(IndexMigrationService.class);
    private final AliasSwitchService switches = mock(AliasSwitchService.class);
    private final SearchIndexDeletionService deletion = mock(SearchIndexDeletionService.class);
    private final AdminCommandIdempotency commands = mock(AdminCommandIdempotency.class);
    private final IndexVersionKbScope scope = mock(IndexVersionKbScope.class);
    private final GrayReleaseStore grays = mock(GrayReleaseStore.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final CurrentUser user = new CurrentUser(1L, "admin", true);
    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model", 1024, 3);
    private SearchIndexAdminController controller;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        controller = new SearchIndexAdminController(mock(SearchIndexAdminQueryService.class), admin, writes,
                mock(RebuildRunControlService.class), migrations, switches,
                mock(SearchIndexValidationService.class), deletion, commands,
                mock(SearchIndexObservability.class));
        controller.setKbScope(scope);
        controller.setGrayReleases(grays);
        controller.setVersionRepository(versions);
        when(scope.isScoped(9)).thenReturn(true);
        when(commands.execute(any(), any(), any(), any(), any())).thenAnswer(invocation ->
                ((Supplier<Map<String, Object>>) invocation.getArgument(4)).get());
    }

    @Test
    void 灰度版本的编辑_写入开关_存量迁移一律拒绝且不调用服务() {
        assertGrayRejected(() -> controller.edit(user, 9, "k1", config));
        assertGrayRejected(() -> controller.migrate(user, 9, "k2"));
        assertGrayRejected(() -> controller.write(user, 9, "k3", new SearchIndexAdminController.WriteRequest(false)));
        assertGrayRejected(() -> controller.write(user, 9, "k4", new SearchIndexAdminController.WriteRequest(true)));
        verifyNoInteractions(admin, writes, migrations, commands);
    }

    @Test
    void 全局版本不受灰度守卫影响() {
        when(writes.disable(3)).thenReturn(SearchIndexVersion.bootstrapped(3, "kwiki-chunks-v3", config, "h"));
        controller.write(user, 3, "k1", new SearchIndexAdminController.WriteRequest(false));
        verify(writes).disable(3);
    }

    @Test
    void 未结束灰度的版本不能删除_灰度结束后可以删除() {
        SearchIndexAdminController.DeleteRequest request = new SearchIndexAdminController.DeleteRequest("kwiki-chunks-v9");
        when(grays.findByIndexVersion(9)).thenReturn(Optional.of(release(GrayReleaseStatus.SYNCED, 9)));
        assertThatThrownBy(() -> controller.delete(user, 9, "k1", request))
                .isInstanceOf(ConflictException.class).hasMessageContaining("结束灰度");
        when(grays.findByIndexVersion(9)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.delete(user, 9, "k1", request)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(deletion);

        when(grays.findByIndexVersion(9)).thenReturn(Optional.of(release(GrayReleaseStatus.ENDED, 9)));
        controller.delete(user, 9, "k1", request);
        verify(deletion).delete(9, "kwiki-chunks-v9", "k1", "admin");
    }

    @Test
    void 存在向量配置不同的未结束灰度时拒绝全局选择() {
        EditableIndexConfig otherEmbedding =
                new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model-2", 768, 3);
        when(versions.findByVersionNumber(3)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(3, "kwiki-chunks-v3", otherEmbedding, "h")));
        when(versions.findByVersionNumber(9)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(9, "kwiki-chunks-v9",
                        new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3), "h")));
        when(grays.findAll()).thenReturn(List.of(release(GrayReleaseStatus.SWITCHED, 9)));

        assertThatThrownBy(() -> controller.select(user, 3, "k1"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("向量模型配置");
        verifyNoInteractions(switches);
    }

    @Test
    void 向量配置相同或灰度已结束时允许全局选择() {
        when(versions.findByVersionNumber(3)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(3, "kwiki-chunks-v3", config, "h")));
        when(versions.findByVersionNumber(9)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(9, "kwiki-chunks-v9",
                        new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3), "h")));
        when(versions.findByVersionNumber(10)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(10, "kwiki-chunks-v10",
                        new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "other", 768, 3), "h")));
        when(grays.findAll()).thenReturn(List.of(release(GrayReleaseStatus.SWITCHED, 9),
                release(GrayReleaseStatus.ENDED, 10)));
        when(switches.select(anyInt(), any())).thenReturn(new AliasSwitchService.Result(true, 1L, "SUCCESS"));

        controller.select(user, 3, "k1");
        verify(switches).select(3, "admin");
    }

    private static void assertGrayRejected(Executable call) {
        assertThatThrownBy(call::execute).isInstanceOf(ConflictException.class)
                .hasMessage(SearchIndexAdminController.GRAY_VERSION_MESSAGE);
    }

    private static GrayRelease release(GrayReleaseStatus status, int version) {
        return new GrayRelease(1, "pdfbox-v2 灰度 #1", "kwiki-parse-2", version, status, null, "admin",
                Instant.EPOCH, null, null, List.of());
    }
}
