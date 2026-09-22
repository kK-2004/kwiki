package com.kwiki.graph;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多库请求的按库门禁：在社区 TopK 与种子提取之前执行，未通过门禁的库
 * 整条图增强路径关闭（不查社区索引、不使用摘要、不做任何图扩展）。
 * 各库独立评估；单库输入缺失或异常只关闭该库，不阻断其他库或原检索流程。
 */
public class GraphKnowledgeBaseGateService {

    private final GraphAuthorizationGate gate = new GraphAuthorizationGate();

    public LinkedHashMap<Long, GraphAuthorizationGate.Decision> evaluate(
            Map<Long, GateInput> inputs, long selectedChunkIndexVersion) {
        LinkedHashMap<Long, GraphAuthorizationGate.Decision> decisions = new LinkedHashMap<>();
        if (inputs == null) {
            return decisions;
        }
        for (Map.Entry<Long, GateInput> entry : inputs.entrySet()) {
            GateInput input = entry.getValue();
            try {
                decisions.put(entry.getKey(), gate.check(
                        input == null ? null : input.coverage(),
                        input == null ? null : input.snapshot(),
                        selectedChunkIndexVersion));
            } catch (RuntimeException failure) {
                decisions.put(entry.getKey(),
                        GraphAuthorizationGate.Decision.denied("gate-error"));
            }
        }
        return decisions;
    }

    /** 通过门禁的知识库集合；被拒绝的库后续不得发起社区搜索或图调用。 */
    public LinkedHashMap<Long, GraphSnapshot> eligible(
            Map<Long, GateInput> inputs, long selectedChunkIndexVersion) {
        LinkedHashMap<Long, GraphSnapshot> eligible = new LinkedHashMap<>();
        for (Map.Entry<Long, GraphAuthorizationGate.Decision> entry
                : evaluate(inputs, selectedChunkIndexVersion).entrySet()) {
            if (entry.getValue().allowed()) {
                eligible.put(entry.getKey(), inputs.get(entry.getKey()).snapshot());
            }
        }
        return eligible;
    }

    /** 按库门禁输入：覆盖证明与固定快照。 */
    public record GateInput(GraphSourceCoverage coverage, GraphSnapshot snapshot) {
    }
}
