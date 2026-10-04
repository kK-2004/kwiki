package com.kwiki.infrastructure.arcadedb;

import com.kwiki.graph.config.ArcadeDbProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 社区检测的临时投影空间。ArcadeDB 建库/删库属于服务器命令，只有 root 能执行，
 * 因此由管理员预建一个专用计算库（scratch-database），每个 run 在其中建独立的
 * 顶点/边类型，跑完删除类型。类型名只由固定前缀和 runId 组成，不接受外部输入。
 */
@Component
// 组件扫描阶段 @ConditionalOnBean 看不到配置类中的 @Bean，改用与 ArcadeDbHttpAdapter 相同的开关条件
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ArcadeDbScratchProjectionSpace {

    private final ArcadeDbHttpAdapter adapter;
    private final ArcadeDbProperties properties;

    public ArcadeDbScratchProjectionSpace(ArcadeDbHttpAdapter adapter, ArcadeDbProperties properties) {
        this.adapter = adapter;
        this.properties = properties;
    }

    public record Lease(long runId, String database, String vertexType, String edgeType) {
        public Lease {
            if (runId <= 0 || database == null || database.isBlank()) {
                throw new IllegalArgumentException("临时投影租约无效");
            }
        }
    }

    /** 建本 run 的顶点/边类型；同一 run 重试时先清理上次残留。 */
    public Lease create(long runId) {
        Lease lease = lease(runId);
        drop(lease);
        for (String statement : java.util.List.of(
                "CREATE VERTEX TYPE " + lease.vertexType() + " IF NOT EXISTS",
                "CREATE PROPERTY " + lease.vertexType() + ".entityId IF NOT EXISTS STRING",
                "CREATE INDEX IF NOT EXISTS ON " + lease.vertexType() + " (entityId) UNIQUE",
                "CREATE EDGE TYPE " + lease.edgeType() + " IF NOT EXISTS")) {
            adapter.command(lease.database(), statement, Map.of(), ArcadeDbTimeoutKind.BATCH_WRITE);
        }
        return lease;
    }

    /** 删除本 run 的类型及其数据；类型已不存在时为空操作，可重复调用。 */
    public void drop(Lease lease) {
        adapter.command(lease.database(), "DROP TYPE " + lease.edgeType() + " IF EXISTS UNSAFE",
                Map.of(), ArcadeDbTimeoutKind.BATCH_WRITE);
        adapter.command(lease.database(), "DROP TYPE " + lease.vertexType() + " IF EXISTS UNSAFE",
                Map.of(), ArcadeDbTimeoutKind.BATCH_WRITE);
    }

    public Lease lease(long runId) {
        if (runId <= 0) throw new IllegalArgumentException("runId 必须为正数");
        return new Lease(runId, properties.scratchDatabase(),
                "LeidenEntity_" + runId, "LeidenEdge_" + runId);
    }
}
