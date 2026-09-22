package com.kwiki.graph;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 全来源覆盖证明：以知识库当前全部有效可索引资源与快照来源清单的并集为
 * 期望集合，逐资源核验用户可读与本次选择范围，并比较快照 epoch 与当前值。
 * 期望集合取并集意味着快照之外新增的私有文档同样使门禁关闭。
 */
public class GraphSourceCoverageService {

    private final GraphSourceInventoryPort inventory;

    public GraphSourceCoverageService(GraphSourceInventoryPort inventory) {
        if (inventory == null) {
            throw new IllegalArgumentException("来源清单端口不能为空");
        }
        this.inventory = inventory;
    }

    public GraphSourceCoverage prove(long userId, boolean superuser, long kbId,
                                     GraphSnapshot snapshot,
                                     List<GraphResourceId> manifestSources,
                                     GraphSelectionScope selection) {
        if (snapshot == null || selection == null) {
            throw new IllegalArgumentException("覆盖证明输入无效");
        }
        Set<GraphResourceId> expected = new LinkedHashSet<>();
        expected.addAll(inventory.listCurrentSources(kbId));
        expected.addAll(manifestSources == null ? List.of() : manifestSources);
        long visible = 0;
        long selected = 0;
        for (GraphResourceId resource : expected) {
            if (inventory.canRead(userId, superuser, resource)) {
                visible++;
            }
            if (selection.covers(resource)) {
                selected++;
            }
        }
        long[] current = inventory.currentEpochs(kbId);
        return new GraphSourceCoverage(expected.size(), visible, selected,
                snapshot.contentEpoch(), snapshot.securityEpoch(),
                current[0], current[1]);
    }

    /** 多库请求各库独立证明；输出顺序与输入一致。 */
    public LinkedHashMap<Long, GraphSourceCoverage> proveAll(
            long userId, boolean superuser,
            LinkedHashMap<Long, CoverageRequest> requests) {
        LinkedHashMap<Long, GraphSourceCoverage> proofs = new LinkedHashMap<>();
        if (requests == null) {
            return proofs;
        }
        for (var entry : requests.entrySet()) {
            CoverageRequest request = entry.getValue();
            proofs.put(entry.getKey(), prove(userId, superuser, entry.getKey(),
                    request.snapshot(), request.manifestSources(), request.selection()));
        }
        return proofs;
    }

    /** 单库覆盖证明请求。 */
    public record CoverageRequest(GraphSnapshot snapshot,
                                  List<GraphResourceId> manifestSources,
                                  GraphSelectionScope selection) {
    }
}
