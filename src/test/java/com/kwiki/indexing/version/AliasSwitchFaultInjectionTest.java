package com.kwiki.indexing.version;

import com.kk2004.common.lock.DistributedLock;
import com.kk2004.common.lock.DistributedLockFactory;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AliasSwitchFaultInjectionTest {
    @Test void elasticsearchRejectionLeavesDatabaseUnchanged(){assertEsFailure("rejected");}
    @Test void timeoutBeforeCommitLeavesDatabaseUnchanged(){assertEsFailure("timeout");}
    @Test void externalAliasMismatchLeavesDatabaseUnchanged(){assertEsFailure("alias source mismatch");}

    @Test
    void failureAfterElasticsearchCommitIsRecoverableByReconciliation() throws Exception {
        Fixture f=fixture(alwaysAcquired());
        doThrow(new IllegalStateException("database unavailable")).when(f.selection).select(2);

        assertThatThrownBy(()->f.service.select(2,"admin")).hasMessageContaining("database unavailable");
        verify(f.indexes).atomicSwitchAlias("kwiki-chunks-v1","kwiki-chunks-v2");
        assertThat(f.lastAudit.getOutcome()).isEqualTo("FAILURE");
    }

    @Test
    void concurrentRequestGetsBusyWhileFirstOwnsAliasLock() throws Exception {
        AtomicBoolean owned=new AtomicBoolean();
        DistributedLock lock=mock(DistributedLock.class);
        when(lock.tryLock(2000,30000,TimeUnit.MILLISECONDS))
                .thenAnswer(invocation->owned.compareAndSet(false,true));
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        doAnswer(invocation->{owned.set(false);return null;}).when(lock).unlock();
        Fixture f=fixture(lock);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();release.await(5,TimeUnit.SECONDS);return null;})
                .when(f.indexes).atomicSwitchAlias(any(),any());
        try(var executor=Executors.newSingleThreadExecutor()){
            var first=executor.submit(()->f.service.select(2,"admin-1"));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(f.service.select(2,"admin-2").code()).isEqualTo("BUSY");
            release.countDown(); assertThat(first.get(2,TimeUnit.SECONDS).switched()).isTrue();
        }
    }

    private void assertEsFailure(String message){
        Fixture f=fixture(alwaysAcquired());
        try{doThrow(new IllegalStateException(message)).when(f.indexes)
                    .atomicSwitchAlias(any(),any());}
        catch(Exception impossible){throw new AssertionError(impossible);}
        assertThatThrownBy(()->f.service.select(2,"admin")).hasMessageContaining(message);
        verify(f.selection,never()).select(2);
        assertThat(f.lastAudit.getOutcome()).isEqualTo("FAILURE");
    }

    private static Fixture fixture(DistributedLock lock){
        DistributedLockFactory factory=mock(DistributedLockFactory.class);
        when(factory.getDistributedLock("kwiki:lock:search-index-alias")).thenReturn(lock);
        var versions=mock(SearchIndexVersionRepository.class);
        var validations=mock(SearchIndexValidationService.class);
        var indexes=mock(ElasticsearchIndexManager.class);
        var selection=mock(SearchIndexSelectionRegistry.class);
        var audits=mock(SearchIndexAuditRepository.class);
        var config=new EditableIndexConfig("parser","chunker","default","model",1024,1);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(1,"kwiki-chunks-v1",config,"h")));
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(2,"kwiki-chunks-v2",config,"h")));
        var report=mock(SearchIndexValidationReport.class); when(report.getRunId()).thenReturn(91L);
        when(validations.currentReadyReport(2)).thenReturn(Optional.of(report));
        Fixture[] holder=new Fixture[1];
        when(audits.saveAndFlush(any())).thenAnswer(invocation->{
            SearchIndexAudit audit=invocation.getArgument(0); holder[0].lastAudit=audit; return audit;});
        when(audits.save(any())).thenAnswer(invocation->invocation.getArgument(0));
        var service=new AliasSwitchService(new KwikiDistributedLocks(
                com.kwiki.testutil.StandardTestProperties.providerOf(factory)),versions,
                validations,indexes,selection,audits,properties());
        Fixture result=new Fixture(service,indexes,selection,audits);holder[0]=result;return result;
    }

    private static DistributedLock alwaysAcquired(){
        DistributedLock lock=mock(DistributedLock.class);
        try{when(lock.tryLock(2000,30000,TimeUnit.MILLISECONDS)).thenReturn(true);}
        catch(InterruptedException failure){throw new AssertionError(failure);}
        when(lock.isHeldByCurrentThread()).thenReturn(true);return lock;
    }
    private static IndexingProperties properties(){return new IndexingProperties(null,null,
            new IndexingProperties.Rebuild(50,2,20),new IndexingProperties.Catchup(100,Duration.ofSeconds(30)),
            new IndexingProperties.Capacity(20,8,Duration.ofSeconds(5)),new IndexingProperties.Management(true));}
    private static final class Fixture{
        final AliasSwitchService service;final ElasticsearchIndexManager indexes;
        final SearchIndexSelectionRegistry selection;final SearchIndexAuditRepository audits;
        SearchIndexAudit lastAudit;
        Fixture(AliasSwitchService service,ElasticsearchIndexManager indexes,
                SearchIndexSelectionRegistry selection,SearchIndexAuditRepository audits){
            this.service=service;this.indexes=indexes;this.selection=selection;this.audits=audits;}
    }
}
