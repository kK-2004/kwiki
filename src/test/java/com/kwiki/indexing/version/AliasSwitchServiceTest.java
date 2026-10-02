package com.kwiki.indexing.version;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class AliasSwitchServiceTest {
    @Test
    void currentReadyReportIsAuditedThenAliasAndDatabaseAreSwitched() throws Exception {
        DistributedLock lock=mock(DistributedLock.class);
        when(lock.tryLock(2000,30000,TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        DistributedLockFactory factory=mock(DistributedLockFactory.class);
        when(factory.getDistributedLock("kwiki:lock:search-index-alias")).thenReturn(lock);
        var versions=mock(SearchIndexVersionRepository.class);
        var validations=mock(SearchIndexValidationService.class);
        var indexes=mock(ElasticsearchIndexManager.class);
        var selection=mock(SearchIndexSelectionRegistry.class);
        var audits=mock(SearchIndexAuditRepository.class);
        var config=new EditableIndexConfig("parser","chunker","default","model",1024, "kwiki-schema-1");
        var source=SearchIndexVersion.bootstrapped(1,"kwiki-chunks-v1",config,"h");
        var target=SearchIndexVersion.bootstrapped(2,"kwiki-chunks-v2",config,"h");
        var report=mock(SearchIndexValidationReport.class);
        when(report.getRunId()).thenReturn(91L);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(source));
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(target));
        when(validations.currentReadyReport(2)).thenReturn(Optional.of(report));
        when(audits.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(audits.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        AliasSwitchService service=new AliasSwitchService(new KwikiDistributedLocks(
                com.kwiki.testutil.StandardTestProperties.providerOf(factory)),versions,
                validations,indexes,selection,audits,properties(), com.kwiki.indexing.parse.plugin.ParserTestFixtures.named("parser"));

        var result=service.select(2,"admin");

        assertThat(result.switched()).isTrue();
        verify(indexes).atomicSwitchAlias("kwiki-chunks-v1","kwiki-chunks-v2");
        verify(selection).select(2);
        verify(lock).unlock();
    }

    @Test
    void unavailableDistributedLockFailsClosed(){
        AliasSwitchService service=new AliasSwitchService(new KwikiDistributedLocks(
                com.kwiki.testutil.StandardTestProperties.nullProvider()),
                mock(SearchIndexVersionRepository.class),mock(SearchIndexValidationService.class),
                mock(ElasticsearchIndexManager.class),mock(SearchIndexSelectionRegistry.class),
                mock(SearchIndexAuditRepository.class),properties(), com.kwiki.indexing.parse.plugin.ParserTestFixtures.named("parser"));
        assertThat(service.select(2,"admin").code()).isEqualTo("BUSY");
    }

    @Test
    void multimodalPrerequisiteFailurePreventsAliasSwitch() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.tryLock(2000, 30000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        DistributedLockFactory factory = mock(DistributedLockFactory.class);
        when(factory.getDistributedLock("kwiki:lock:search-index-alias")).thenReturn(lock);
        var versions = mock(SearchIndexVersionRepository.class);
        var config = new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1",
                "default", "model", 1024, "kwiki-schema-1");
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, "h")));
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(2, "kwiki-chunks-v2", config, "h")));
        var readiness = com.kwiki.indexing.parse.plugin.ParserTestFixtures.unavailableTwo();
        var indexes = mock(ElasticsearchIndexManager.class);
        var audits = mock(SearchIndexAuditRepository.class);
        var validations = mock(SearchIndexValidationService.class);
        AliasSwitchService service = new AliasSwitchService(new KwikiDistributedLocks(
                com.kwiki.testutil.StandardTestProperties.providerOf(factory)), versions,
                validations, indexes, mock(SearchIndexSelectionRegistry.class), audits,
                properties(), readiness);

        assertThatThrownBy(() -> service.select(2, "admin"))
                .hasMessageContaining("测试不可用");
        verify(indexes, never()).atomicSwitchAlias(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(validations, never()).currentReadyReport(2);
        verify(lock).unlock();
    }

    @Test
    void unknownParserPreventsAliasSwitch() throws Exception {
        DistributedLock lock = mock(DistributedLock.class);
        when(lock.tryLock(2000, 30000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        DistributedLockFactory factory = mock(DistributedLockFactory.class);
        when(factory.getDistributedLock("kwiki:lock:search-index-alias")).thenReturn(lock);
        var versions = mock(SearchIndexVersionRepository.class);
        var config = new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1",
                "default", "model", 1024, "kwiki-schema-1");
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, "h")));
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(2, "kwiki-chunks-v2", config, "h")));
        var readiness = com.kwiki.indexing.parse.plugin.ParserTestFixtures.named("other-parser");
        var indexes = mock(ElasticsearchIndexManager.class);
        var audits = mock(SearchIndexAuditRepository.class);
        var validations = mock(SearchIndexValidationService.class);
        AliasSwitchService service = new AliasSwitchService(new KwikiDistributedLocks(
                com.kwiki.testutil.StandardTestProperties.providerOf(factory)), versions,
                validations, indexes, mock(SearchIndexSelectionRegistry.class), audits,
                properties(), readiness);

        assertThatThrownBy(() -> service.select(2, "admin"))
                .hasMessageContaining("kwiki-parse-2 未注册");
        verify(indexes, never()).atomicSwitchAlias(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        verify(validations, never()).currentReadyReport(2);
        verify(lock).unlock();
    }

    @Test
    void 灰度版本不能被全局选择() {
        AliasSwitchService service = new AliasSwitchService(new KwikiDistributedLocks(
                com.kwiki.testutil.StandardTestProperties.nullProvider()),
                mock(SearchIndexVersionRepository.class), mock(SearchIndexValidationService.class),
                mock(ElasticsearchIndexManager.class), mock(SearchIndexSelectionRegistry.class),
                mock(SearchIndexAuditRepository.class), properties(), com.kwiki.indexing.parse.plugin.ParserTestFixtures.named("parser"));
        com.kwiki.indexing.gray.IndexVersionKbScope scope =
                org.mockito.Mockito.mock(com.kwiki.indexing.gray.IndexVersionKbScope.class);
        org.mockito.Mockito.when(scope.isScoped(3)).thenReturn(true);
        service.setKbScope(scope);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.select(3, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gray release version");
    }

    private static IndexingProperties properties(){return new IndexingProperties(null,null,
            new IndexingProperties.Rebuild(50,2,20),
            new IndexingProperties.Catchup(100,Duration.ofSeconds(30)),
            new IndexingProperties.Capacity(20,8,Duration.ofSeconds(5)),
            new IndexingProperties.Management(true));}
}
