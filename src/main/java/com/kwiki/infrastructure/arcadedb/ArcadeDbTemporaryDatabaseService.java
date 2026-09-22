package com.kwiki.infrastructure.arcadedb;

import com.kwiki.graph.config.ArcadeDbProperties;
import com.kwiki.graph.persistence.GraphRemoteAlgorithmGuard;
import com.kwiki.graph.persistence.GraphRemoteAlgorithmState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 为单个 run 创建并清理受控临时库。库名只由配置前缀和服务端生成的
 * 数字身份组成；算法状态未知时拒绝清理，避免删除仍在运行的投影。
 */
@Component
@ConditionalOnBean(ArcadeDbHttpAdapter.class)
public class ArcadeDbTemporaryDatabaseService {

    private final ArcadeDbHttpAdapter adapter;
    private final ArcadeDbProperties properties;

    public ArcadeDbTemporaryDatabaseService(ArcadeDbHttpAdapter adapter,
                                            ArcadeDbProperties properties) {
        this.adapter = adapter;
        this.properties = properties;
    }

    public record Lease(long runId, String database, String owner) {
        public Lease {
            if (runId <= 0 || database == null || database.isBlank()
                    || owner == null || owner.isBlank()) {
                throw new IllegalArgumentException("临时库租约无效");
            }
        }
    }

    public Lease create(long runId, String owner) {
        String database = databaseName(runId);
        adapter.command(properties.database(), "CREATE DATABASE " + database,
                Map.of(), ArcadeDbTimeoutKind.BATCH_WRITE);
        return new Lease(runId, database, owner);
    }

    public void cleanup(Lease lease, boolean remoteAlgorithmConfirmedStopped) {
        if (!remoteAlgorithmConfirmedStopped) {
            throw new IllegalStateException("远端算法状态未知，暂不清理临时库");
        }
        adapter.command(properties.database(), "DROP DATABASE " + lease.database(),
                Map.of(), ArcadeDbTimeoutKind.BATCH_WRITE);
    }

    /** 只有远端明确进入终态才允许释放临时库和算法槽位。 */
    public void cleanup(Lease lease, GraphRemoteAlgorithmState remoteState) {
        if (!GraphRemoteAlgorithmGuard.canCleanup(remoteState)) {
            throw new IllegalStateException("远端算法尚未确认结束: " + remoteState);
        }
        cleanup(lease, true);
    }

    public String databaseName(long runId) {
        if (runId <= 0) throw new IllegalArgumentException("runId 必须为正数");
        String prefix = properties.temporaryDatabasePrefix();
        if (prefix == null || !prefix.matches("[A-Za-z_][A-Za-z0-9_]{0,40}")) {
            throw new IllegalStateException("ArcadeDB 临时库前缀无效");
        }
        return prefix + runId;
    }
}
