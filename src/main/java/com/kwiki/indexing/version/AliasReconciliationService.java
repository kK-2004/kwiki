package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;

/** ES 别名是读目标事实源；修复 ES 已提交但数据库未提交的崩溃窗口。 */
@Service
public class AliasReconciliationService implements ApplicationRunner {
    private final KwikiDistributedLocks locks; private final ElasticsearchIndexManager indexes;
    private final SearchIndexVersionRepository versions; private final SearchIndexSelectionRegistry selection;
    private final SearchIndexAuditRepository audits;
    public AliasReconciliationService(KwikiDistributedLocks locks,ElasticsearchIndexManager indexes,
            SearchIndexVersionRepository versions,SearchIndexSelectionRegistry selection,
            SearchIndexAuditRepository audits){this.locks=locks;this.indexes=indexes;
        this.versions=versions;this.selection=selection;this.audits=audits;}

    @Override public void run(ApplicationArguments args){reconcile("startup-reconciler");}

    @Scheduled(fixedDelayString="${kwiki.indexing.reconcile-interval:60s}")
    public void scheduled(){reconcile("scheduled-reconciler");}

    public boolean reconcile(String operator){
        AutoCloseable held=locks.acquire("search-index-alias",Duration.ofMillis(500),
                Duration.ofSeconds(30));
        if(held==null)return false;
        try(held){
            var targets=indexes.aliasTargets();
            if(targets.size()!=1)return false;
            SearchIndexVersion actual=versions
                    .findByPhysicalNameAndDeletedAtIsNull(targets.get(0)).orElse(null);
            if(actual==null)return false;
            SearchIndexVersion recorded=versions.findBySelectedTrue().orElse(null);
            if(recorded!=null&&recorded.getVersionNumber()==actual.getVersionNumber())return true;
            SearchIndexAudit audit=audits.saveAndFlush(SearchIndexAudit.pending("RECONCILE",
                    actual.getVersionNumber(),null,operator,actual.getConfigRevision(),
                    recorded==null?"<missing>":recorded.getPhysicalName()));
            selection.select(actual.getVersionNumber());
            audit.recovered(actual.getPhysicalName()); audits.save(audit); return true;
        }catch(Exception failure){return false;}
    }
}
