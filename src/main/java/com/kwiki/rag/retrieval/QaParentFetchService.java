package com.kwiki.rag.retrieval;

import com.kwiki.rag.answer.EvidenceAssembler;
import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.access.ScopeVersionService;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 父分块获取：工作流在生成前（kwiki.agentic.auto-expand-parents，默认开启）
 * 或子分块阶段的候选未通过门禁之后，获取该阶段所保留子分块的父分块。父分块按
 * 首个命中的子分块顺序去重，受阶段父分块上限与
 * 固定的父分块字符预算约束，且每次获取都会重新校验
 * 授权作用域与生命周期排除项。当保留下来的子分块的
 * 父分块全部已在已用上下文中时，该阶段会报告「已跳过」，
 * 而不是重复提供相同的上下文。
 */
@Component
public class QaParentFetchService {

    public record ParentStageOutcome(List<ParentEvidence> evidence, boolean skipped,
                                     String reason, int parentCount, long elapsedMs) {}

    private final ParentEvidenceResolver resolver;
    private final EvidenceAssembler assembler;
    private final ScopeVersionService scopeVersions;

    public QaParentFetchService(ParentEvidenceResolver resolver,
                                EvidenceAssembler assembler,
                                ScopeVersionService scopeVersions) {
        this.resolver = resolver;
        this.assembler = assembler;
        this.scopeVersions = scopeVersions;
    }

    /**
     * @param usedParentKeys 本查询上下文中已存在的父级 key
     *                       （子阶段按构造不含任何父级）
     */
    public ParentStageOutcome fetchParents(AuthorizationScope scope, RunContext run,
                                           List<ChildEvidence> retainedChildren,
                                           int parentLimit, long parentCharBudget,
                                           Set<String> usedParentKeys) {
        long started = System.nanoTime();
        authorize(scope);
        run.authorize();
        var filter = ScopeFilter.from(scope);

        Map<String, ChildEvidence> childrenByKey = new LinkedHashMap<>();
        List<StandardRrfFusion.FusedChunk> fused = new ArrayList<>();
        Map<String, ChunkHit> hitsByKey = new LinkedHashMap<>();
        for (ChildEvidence child : retainedChildren) {
            childrenByKey.putIfAbsent(child.chunkKey(), child);
            fused.add(new StandardRrfFusion.FusedChunk(
                    child.chunkKey(), child.rrfScore(), Map.of()));
            hitsByKey.putIfAbsent(child.chunkKey(), toHit(child));
        }

        // QA 各阶段可以配置得比共享组装器更宽松。
        // 在解析之前先给请求设上限，这样合法的扩展阶段就不会仅仅
        // 因为找到的去重父块数量超过组装器的接受范围而失败。
        int effectiveParentLimit = Math.min(parentLimit, assembler.maxParentCount());
        long effectiveCharBudget = Math.min(parentCharBudget, assembler.maxContextChars());
        var parentChunks = resolver.resolve(fused, hitsByKey, filter, effectiveParentLimit);
        authorize(scope);
        run.authorize();

        boolean allUsed = !parentChunks.isEmpty()
                && parentChunks.stream()
                        .allMatch(parent -> usedParentKeys.contains(parent.parentChunkKey()));
        if (allUsed || parentChunks.isEmpty()) {
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            return new ParentStageOutcome(List.of(), true,
                    parentChunks.isEmpty() ? "no-parents" : "no-new-parents",
                    0, elapsed);
        }
        var evidence = assembler.assemble(parentChunks, effectiveCharBudget);
        authorize(scope);
        run.authorize();
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return new ParentStageOutcome(evidence, false, null, evidence.size(), elapsed);
    }

    /** 当前在 evidence 列表中表示的父级 key。 */
    public static Set<String> keysOf(List<ParentEvidence> evidence) {
        Set<String> keys = new HashSet<>();
        for (ParentEvidence parent : evidence) {
            keys.add(parent.parentChunkKey());
        }
        return keys;
    }

    /**
     * 生成上下文只放父块时，没被任何父块正文覆盖的子块（超出父块上限或字符预算）会丢失：
     * 把它们按所属父块分组，作为截断的父块（正文只含命中的子块，按原文位置排列）
     * 追加在已有父块之后，firstHitOrder 顺延，引用与评审照常按父块处理。
     */
    public static List<ParentEvidence> withUncoveredChildren(List<ParentEvidence> parents,
                                                             List<ChildEvidence> children) {
        Map<String, ParentEvidence> byKey = new HashMap<>();
        parents.forEach(parent -> byKey.putIfAbsent(parent.parentChunkKey(), parent));
        Map<String, List<ChildEvidence>> uncovered = new LinkedHashMap<>();
        for (ChildEvidence child : children) {
            ParentEvidence parent = byKey.get(child.parentChunkKey());
            if (parent != null && parent.body().contains(child.content())) {
                continue;
            }
            uncovered.computeIfAbsent(child.parentChunkKey(), key -> new ArrayList<>()).add(child);
        }
        if (uncovered.isEmpty()) {
            return parents;
        }
        List<ParentEvidence> all = new ArrayList<>(parents);
        int order = parents.stream().mapToInt(ParentEvidence::firstHitOrder).max().orElse(-1) + 1;
        for (List<ChildEvidence> group : uncovered.values()) {
            ChildEvidence first = group.get(0);
            List<ChildEvidence> inTextOrder = group.stream()
                    .sorted(Comparator.comparingInt(ChildEvidence::charStart)).toList();
            all.add(new ParentEvidence(
                    first.parentChunkKey(), first.resourceType(), first.resourceId(),
                    first.revisionId(), first.kbId(), first.headingPath(),
                    inTextOrder.stream().map(ChildEvidence::content).collect(Collectors.joining("\n……\n")),
                    true, order++,
                    group.stream().map(QaParentFetchService::toHit).toList()));
        }
        return List.copyOf(all);
    }

    private static ChunkHit toHit(ChildEvidence child) {
        return new ChunkHit(
                child.chunkKey(), child.parentChunkKey(), child.kbId(),
                child.resourceType(), child.resourceId(), child.revisionId(),
                child.headingPath(), child.charStart(), child.charEnd(), child.content(),
                List.of(), child.sourceChunkId(), child.entityIds(),
                child.entityLinkingVersion(), child.entityLinkingStatus());
    }

    private void authorize(AuthorizationScope scope) {
        if (scope == null || scope.isStale(scopeVersions::current)) {
            throw new HybridRetrievalOrchestrator.StaleScopeException();
        }
    }
}
