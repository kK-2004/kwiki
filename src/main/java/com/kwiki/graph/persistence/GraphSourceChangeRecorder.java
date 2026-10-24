package com.kwiki.graph.persistence;

import com.kwiki.wiki.graph.KnowledgeSourceChangePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link KnowledgeSourceChangePort} 的 graph 侧实现：推进该库的 content/security
 * epoch 并 upsert 待重建记录。方法在调用方的业务事务内执行（epoch 服务的
 * REQUIRED 传播加入现有事务，JDBC 写入随之同事务），任一写入失败都会让
 * 业务事务整体回滚，epoch 与待重建记录不会前进。图功能关闭时本 Bean 不装配，
 * 使用方经 ObjectProvider 注入 {@link KnowledgeSourceChangePort#noop()}。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class GraphSourceChangeRecorder implements KnowledgeSourceChangePort {

    private static final Logger log = LoggerFactory.getLogger(GraphSourceChangeRecorder.class);

    private final GraphSourceEpochService epochs;
    private final GraphRebuildRequestRepository rebuildRequests;

    public GraphSourceChangeRecorder(GraphSourceEpochService epochs,
                                     GraphRebuildRequestRepository rebuildRequests) {
        this.epochs = epochs;
        this.rebuildRequests = rebuildRequests;
    }

    @Override
    public void contentChanged(long kbId) {
        advance(kbId, false);
    }

    @Override
    public void securityChanged(long kbId) {
        epochs.advanceSecurityEpoch(kbId);
        markDirty(kbId, false);
    }

    @Override
    public void hardDeleted(long kbId) {
        advance(kbId, true);
    }

    private void advance(long kbId, boolean hardDelete) {
        epochs.advanceContentEpoch(kbId);
        markDirty(kbId, hardDelete);
    }

    private void markDirty(long kbId, boolean hardDelete) {
        try {
            long[] current = epochs.current(kbId);
            rebuildRequests.markDirty(kbId, current[0], current[1], hardDelete);
        } catch (RuntimeException failure) {
            // 向上抛出让业务事务整体回滚：epoch、待重建记录与业务写入保持原子
            log.warn("graph rebuild request upsert failed for kb {}: {}", kbId, failure.getMessage());
            throw failure;
        }
    }
}
