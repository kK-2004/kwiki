package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import com.kwiki.infrastructure.redis.KwikiDistributedLocks;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import java.time.Duration;

/** 管理员原子选择/回滚入口；Redis 不可用或锁竞争时失败关闭。 */
@Service
@ConditionalOnBean(SearchIndexVersionRepository.class)
public class AliasSwitchService {
    private static final Duration LOCK_WAIT=Duration.ofSeconds(2);
    private static final Duration LOCK_LEASE=Duration.ofSeconds(30);
    private final KwikiDistributedLocks locks; private final SearchIndexVersionRepository versions;
    private final SearchIndexValidationService validations; private final ElasticsearchIndexManager indexes;
    private final SearchIndexSelectionRegistry selection; private final SearchIndexAuditRepository audits;
    private final IndexingProperties properties;
    public AliasSwitchService(KwikiDistributedLocks locks,SearchIndexVersionRepository versions,
            SearchIndexValidationService validations,ElasticsearchIndexManager indexes,
            SearchIndexSelectionRegistry selection,SearchIndexAuditRepository audits,
            IndexingProperties properties){this.locks=locks;this.versions=versions;
        this.validations=validations;this.indexes=indexes;this.selection=selection;
        this.audits=audits;this.properties=properties;}

    public Result select(int targetVersion,String operator){
        if(!Boolean.TRUE.equals(properties.management().mutationsEnabled()))
            throw new IllegalStateException(SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
        AutoCloseable held=locks.acquire("search-index-alias",LOCK_WAIT,LOCK_LEASE);
        if(held==null)return new Result(false,null,"BUSY");
        try(held){return switchWhileLocked(targetVersion,operator);}
        catch(RuntimeException failure){throw failure;}
        catch(Exception failure){throw new IllegalStateException("alias switch failed",failure);}
    }

    private Result switchWhileLocked(int targetVersion,String operator)throws Exception{
        SearchIndexVersion source=versions.findBySelectedTrue()
                .orElseThrow(()->new IllegalStateException("selected source is missing"));
        SearchIndexVersion target=versions.findByVersionNumber(targetVersion)
                .orElseThrow(()->new IllegalArgumentException("unknown target version"));
        SearchIndexValidationReport report=validations.currentReadyReport(targetVersion)
                .orElseThrow(()->new IllegalStateException("target has no current READY validation"));
        SearchIndexAudit audit=audits.saveAndFlush(SearchIndexAudit.pending("SELECT",
                targetVersion,report.getRunId(),operator,target.getConfigRevision(),
                source.getPhysicalName()));
        try{
            indexes.atomicSwitchAlias(source.getPhysicalName(),target.getPhysicalName());
            selection.select(targetVersion);
            audit.succeed(target.getPhysicalName()); audits.save(audit);
            return new Result(true,audit.getId(),"SUCCESS");
        }catch(Exception failure){audit.fail(sanitize(failure));audits.save(audit);throw failure;}
    }

    private static String sanitize(Exception failure){
        String value=failure.getClass().getSimpleName()+":"+String.valueOf(failure.getMessage());
        return value.substring(0,Math.min(1000,value.length()));
    }
    public record Result(boolean switched,Long auditId,String code){}
}
