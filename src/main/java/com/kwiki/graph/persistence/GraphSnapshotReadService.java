package com.kwiki.graph.persistence;

import com.kwiki.graph.GraphSnapshotPin;
import com.kwiki.graph.GraphSnapshotPinGuard;
import com.kwiki.graph.config.GraphProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 请求级快照 pin：请求开始时对每个 (kbId, chunkIndexVersion) 配对固定一次
 * 发布指针、物理索引名和读取租约；读取期间不得重新解析别名，也不得用其他
 * 图版本补齐结果。多库请求各库独立固定，单库不可用不阻断其他库。
 */
@Service
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
@ConditionalOnBean(GraphBuildRepository.class)
public class GraphSnapshotReadService {

    private final GraphBuildRepository repository;
    private final java.time.Duration leaseDuration;

    public GraphSnapshotReadService(GraphBuildRepository repository,
                                    GraphProperties properties) {
        this.repository = repository;
        this.leaseDuration = properties.readLeaseDuration();
    }

    /** 单库固定；无已发布配对或快照拒绝新 pin 时抛出，由调用方降级关闭该库图增强。 */
    public GraphSnapshotPin pin(long kbId, long chunkIndexVersion) {
        GraphSnapshotEntry entry = repository.findActivePublicationSnapshot(
                        kbId, Math.toIntExact(chunkIndexVersion))
                .orElseThrow(() -> new IllegalStateException(
                        "该 Chunk 版本没有已发布的图快照: kb=" + kbId
                                + ",chunkVersion=" + chunkIndexVersion));
        if (!entry.pinnable()) {
            throw new IllegalStateException("图快照不再接受新的读取 pin: "
                    + entry.snapshot().snapshotId() + "," + entry.state());
        }
        long leaseId = repository.acquireReadLease(entry.snapshot().snapshotId(),
                Instant.now().plus(leaseDuration));
        if (leaseId <= 0) {
            throw new IllegalStateException("图快照读取租约获取失败: "
                    + entry.snapshot().snapshotId());
        }
        return GraphSnapshotPinGuard.pin(entry.snapshot(), leaseId,
                Math.toIntExact(chunkIndexVersion));
    }

    /**
     * 多库请求固定各自配对；每个库独立成功或降级，失败的库返回 empty
     * 并保留原因，不混合其他库或版本的快照。
     */
    public Map<Long, Optional<GraphSnapshotPin>> pinAll(Map<Long, Long> chunkIndexVersions) {
        Map<Long, Optional<GraphSnapshotPin>> pins = new LinkedHashMap<>();
        for (Map.Entry<Long, Long> target : chunkIndexVersions.entrySet()) {
            try {
                pins.put(target.getKey(), Optional.of(pin(target.getKey(), target.getValue())));
            } catch (IllegalStateException unavailable) {
                pins.put(target.getKey(), Optional.empty());
            }
        }
        return pins;
    }

    public void release(GraphSnapshotPin pin) {
        if (pin != null) {
            repository.releaseReadLease(pin.leaseId());
        }
    }

    public void releaseAll(Iterable<GraphSnapshotPin> pins) {
        if (pins == null) {
            return;
        }
        for (GraphSnapshotPin pin : pins) {
            release(pin);
        }
    }
}
