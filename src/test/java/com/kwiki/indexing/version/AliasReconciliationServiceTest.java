package com.kwiki.indexing.version;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AliasReconciliationServiceTest {
    @Test
    void elasticsearchAliasTruthRepairsInterruptedDatabaseSelection() throws Exception {
        DistributedLock lock=mock(DistributedLock.class);
        when(lock.tryLock(500,30000,TimeUnit.MILLISECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        DistributedLockFactory factory=mock(DistributedLockFactory.class);
        when(factory.getDistributedLock("kwiki:lock:search-index-alias")).thenReturn(lock);
        var indexes=mock(ElasticsearchIndexManager.class);
        var versions=mock(SearchIndexVersionRepository.class);
        var selection=mock(SearchIndexSelectionRegistry.class);
        var audits=mock(SearchIndexAuditRepository.class);
        var config=new EditableIndexConfig("parser","chunker","default","model",1024,1);
        var database=SearchIndexVersion.bootstrapped(1,"kwiki-chunks-v1",config,"h");
        var actual=SearchIndexVersion.bootstrapped(2,"kwiki-chunks-v2",config,"h");
        when(indexes.aliasTargets()).thenReturn(List.of("kwiki-chunks-v2"));
        when(versions.findByPhysicalNameAndDeletedAtIsNull("kwiki-chunks-v2"))
                .thenReturn(Optional.of(actual));
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(database));
        when(audits.saveAndFlush(any())).thenAnswer(invocation->invocation.getArgument(0));
        when(audits.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        AliasReconciliationService service=new AliasReconciliationService(
                new KwikiDistributedLocks(com.kwiki.testutil.StandardTestProperties.providerOf(factory)),
                indexes,versions,selection,audits);

        assertThat(service.reconcile("test-recovery")).isTrue();
        verify(selection).select(2);
        verify(lock).unlock();
    }
}
