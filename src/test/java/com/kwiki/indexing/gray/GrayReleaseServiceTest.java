package com.kwiki.indexing.gray;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.version.*;
import com.kwiki.wiki.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GrayReleaseServiceTest {

    private final InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
    private final SearchIndexAdminService admin = mock(SearchIndexAdminService.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final IndexVersionKbScope scope = mock(IndexVersionKbScope.class);
    private final ManualIndexRebuildService rebuilds = mock(ManualIndexRebuildService.class);
    private final SwitchPreparationService preparations = mock(SwitchPreparationService.class);
    private final SearchIndexValidationService validations = mock(SearchIndexValidationService.class);
    private final IndexVersionEnablementService enablement = mock(IndexVersionEnablementService.class);
    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final ParserCatalog parsers = mock(ParserCatalog.class);
    private GrayReleaseService service;

    private final EditableIndexConfig globalConfig =
            new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model", 1024, 3);

    @BeforeEach
    void setUp() {
        service = new GrayReleaseService(store, admin, versions, scope, rebuilds, preparations,
                validations, enablement, runs, parsers);
        String hash = new ChunkMappingBuilder().mappingHash(1024, 3);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", globalConfig, hash)));
        when(admin.createVersion(any())).thenAnswer(invocation -> SearchIndexVersion.bootstrapped(
                2, "kwiki-chunks-v2", invocation.getArgument(0), hash));
    }

    @Test
    void 创建灰度_以全局配置为基础替换解析器并登记知识库范围() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L, 9L), "admin");

        verify(parsers).requireAvailable("kwiki-parse-2");
        verify(admin).createVersion(new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3));
        verify(scope).register(2, List.of(7L, 9L));
        assertThat(created.status()).isEqualTo(GrayReleaseStatus.CREATED);
        assertThat(created.indexVersionNumber()).isEqualTo(2);
        assertThat(created.name()).isEqualTo("pdfbox-v2 灰度 #1");
        assertThat(created.kbIds()).containsExactly(7L, 9L);
    }

    @Test
    void 知识库已在其他未结束灰度中时拒绝创建且不建版本() {
        store.insert("old", "kwiki-parse-2", 5, "admin");
        store.insertKbs(1, List.of(9L));
        assertThatThrownBy(() -> service.create(null, "kwiki-parse-2", List.of(7L, 9L), "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("9");
        verify(admin, never()).createVersion(any());
    }

    @Test
    void 空知识库列表拒绝创建() {
        assertThatThrownBy(() -> service.create("x", "kwiki-parse-2", List.of(), "admin"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 开始同步_发起重建并进入同步中() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        SearchIndexVersion version = versionWrites(false);
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        GrayRelease syncing = service.sync(created.id(), "admin");
        verify(rebuilds).rebuild(2, "admin");
        assertThat(syncing.status()).isEqualTo(GrayReleaseStatus.SYNCING);
    }

    @Test
    void 推进_重建完成后补齐_补齐就绪后校验_通过即已同步() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SYNCING, null);

        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(runs.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(run));
        when(run.state()).thenReturn(RebuildRunState.COMPLETED);
        when(run.switchState()).thenReturn(IndexSwitchState.NONE);
        service.advance(created.id());
        verify(preparations).prepare(2);

        when(run.switchState()).thenReturn(IndexSwitchState.READY);
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        SearchIndexValidationReport passed = mock(SearchIndexValidationReport.class);
        when(passed.getStatus()).thenReturn("PASS");
        when(validations.validate(2)).thenReturn(passed);
        assertThat(service.advance(created.id()).status()).isEqualTo(GrayReleaseStatus.SYNCED);
    }

    @Test
    void 推进_重建失败时停留在同步中并记录原因() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(runs.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(run));
        when(run.state()).thenReturn(RebuildRunState.FAILED);
        when(run.getErrorSummary()).thenReturn("embedding timeout");
        GrayRelease after = service.advance(created.id());
        assertThat(after.status()).isEqualTo(GrayReleaseStatus.SYNCING);
        assertThat(after.lastError()).contains("embedding timeout");
    }

    @Test
    void 推进_校验未通过时记录摘要() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(runs.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(run));
        when(run.state()).thenReturn(RebuildRunState.COMPLETED);
        when(run.switchState()).thenReturn(IndexSwitchState.READY);
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        SearchIndexValidationReport failed = mock(SearchIndexValidationReport.class);
        when(failed.getStatus()).thenReturn("FAIL");
        when(failed.getSummary()).thenReturn("missingResources=3");
        when(validations.validate(2)).thenReturn(failed);
        GrayRelease after = service.advance(created.id());
        assertThat(after.status()).isEqualTo(GrayReleaseStatus.SYNCING);
        assertThat(after.lastError()).contains("missingResources=3");
    }

    @Test
    void 切换与切回_只允许在对应状态下进行() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        assertThatThrownBy(() -> service.switchTo(created.id())).isInstanceOf(ConflictException.class);

        store.updateStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenReturn(Optional.of(mock(SearchIndexValidationReport.class)));
        assertThat(service.switchTo(created.id()).status()).isEqualTo(GrayReleaseStatus.SWITCHED);
        assertThat(service.switchBack(created.id()).status()).isEqualTo(GrayReleaseStatus.SYNCED);
        assertThatThrownBy(() -> service.switchBack(created.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void 切换前校验报告已失效则拒绝() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.switchTo(created.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void 结束灰度_停用写入并释放知识库_索引保留() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SWITCHED, null);
        SearchIndexVersion version = versionWrites(true);
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        GrayRelease ended = service.end(created.id());
        verify(enablement).disable(2);
        assertThat(ended.status()).isEqualTo(GrayReleaseStatus.ENDED);
        assertThat(store.activeReleaseByKb(List.of(7L))).isEmpty();
        assertThatThrownBy(() -> service.end(created.id())).isInstanceOf(ConflictException.class);
    }

    private SearchIndexVersion versionWrites(boolean writeEnabled) {
        SearchIndexVersion version = mock(SearchIndexVersion.class);
        when(version.isWriteEnabled()).thenReturn(writeEnabled);
        when(version.getVersionNumber()).thenReturn(2);
        return version;
    }
}
