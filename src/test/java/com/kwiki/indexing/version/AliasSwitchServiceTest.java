package com.kwiki.indexing.version;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        var config=new EditableIndexConfig("parser","chunker","default","model",1024,1);
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
                validations,indexes,selection,audits,properties());

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
                mock(SearchIndexAuditRepository.class),properties());
        assertThat(service.select(2,"admin").code()).isEqualTo("BUSY");
    }

    private static IndexingProperties properties(){return new IndexingProperties(null,null,
            new IndexingProperties.Rebuild(50,2,20),
            new IndexingProperties.Catchup(100,Duration.ofSeconds(30)),
            new IndexingProperties.Capacity(20,8,Duration.ofSeconds(5)),
            new IndexingProperties.Management(true));}
}
