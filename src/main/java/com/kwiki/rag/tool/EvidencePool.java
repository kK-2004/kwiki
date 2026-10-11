package com.kwiki.rag.tool;

import com.kwiki.rag.answer.ParentEvidence;
import com.kwiki.rag.retrieval.ChildEvidence;
import com.kwiki.rag.retrieval.GraphRetrievalEnhancer;
import com.kwiki.rag.routing.ClarifyRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 单次 run 的证据池与工具运行状态。非线程安全：一个 run 内的工具由代码顺序执行。
 * 子块按 chunkKey 去重（同键保留更高 RRF 分数）、按分数降序展示，并受字符预算约束；
 * 预算只升不降，避免较浅的检索把先前较深检索的结果挤掉。
 */
public final class EvidencePool {
    private final Map<String, ChildEvidence> children = new LinkedHashMap<>();
    private final Map<String, ParentEvidence> parents = new LinkedHashMap<>();
    private final Map<String, float[]> embeddingCache = new LinkedHashMap<>();
    private final GraphRetrievalEnhancer.SharedBudget graphBudget =
            new GraphRetrievalEnhancer.SharedBudget();
    private final List<String> rewriteHistory = new ArrayList<>();
    private final List<String> stageFailures = new ArrayList<>();
    private List<String> rewriteSuggestions = List.of();
    private String currentQuery;
    private long childCharBudget;
    private int hybridRetrievals;
    private int parentFetches;
    private int rewritesUsed;
    private int version;
    private ClarifyRequest clarification;

    public EvidencePool(String initialQuery) {
        this.currentQuery = initialQuery == null ? "" : initialQuery;
    }

    // ---- 子块 ----------------------------------------------------------

    /** 按 RRF 分数降序（同分按 chunkKey）。 */
    public List<ChildEvidence> children() {
        List<ChildEvidence> sorted = new ArrayList<>(children.values());
        sorted.sort(ChildEvidence::orderByScore);
        return List.copyOf(sorted);
    }

    public Set<String> childKeys() {
        return new LinkedHashSet<>(children.keySet());
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public Optional<ChildEvidence> child(String chunkKey) {
        return Optional.ofNullable(children.get(chunkKey));
    }

    /** @return 合并后新进入池中的子块数 */
    public int mergeChildren(List<ChildEvidence> incoming, long charBudget) {
        Set<String> before = childKeys();
        Map<String, ChildEvidence> merged = new LinkedHashMap<>(children);
        for (ChildEvidence child : incoming) {
            ChildEvidence existing = merged.get(child.chunkKey());
            if (existing == null || child.rrfScore() > existing.rrfScore()) {
                merged.put(child.chunkKey(), child);
            }
        }
        return install(merged, before, charBudget);
    }

    /** 图增强等返回「已合并的完整列表」时整体替换；沿用池里已有的字符预算。 */
    public int replaceChildren(List<ChildEvidence> mergedList) {
        Set<String> before = childKeys();
        Map<String, ChildEvidence> merged = new LinkedHashMap<>();
        for (ChildEvidence child : mergedList) {
            merged.put(child.chunkKey(), child);
        }
        return install(merged, before, 0);
    }

    private int install(Map<String, ChildEvidence> candidates, Set<String> before, long charBudget) {
        childCharBudget = Math.max(childCharBudget, charBudget);
        List<ChildEvidence> ordered = new ArrayList<>(candidates.values());
        ordered.sort(ChildEvidence::orderByScore);
        Map<String, ChildEvidence> previous = new LinkedHashMap<>(children);
        children.clear();
        long remaining = childCharBudget > 0 ? childCharBudget : Long.MAX_VALUE;
        for (ChildEvidence child : ordered) {
            if (remaining <= 0) {
                break;
            }
            children.put(child.chunkKey(), child);
            remaining -= child.content().length();
        }
        if (!previous.equals(children)) {
            version++;
        }
        int added = 0;
        for (String key : children.keySet()) {
            if (!before.contains(key)) {
                added++;
            }
        }
        return added;
    }

    // ---- 父块 ----------------------------------------------------------

    public List<ParentEvidence> parents() {
        return List.copyOf(parents.values());
    }

    public Set<String> usedParentKeys() {
        return Set.copyOf(parents.keySet());
    }

    /** @return 新进入池中的父块数 */
    public int addParents(List<ParentEvidence> incoming) {
        int added = 0;
        for (ParentEvidence parent : incoming) {
            if (parents.putIfAbsent(parent.parentChunkKey(), parent) == null) {
                added++;
                version++;
            }
        }
        return added;
    }

    // ---- 预算计数 ------------------------------------------------------

    public int hybridRetrievals() {
        return hybridRetrievals;
    }

    public void useHybridRetrieval() {
        hybridRetrievals++;
    }

    public int parentFetches() {
        return parentFetches;
    }

    public void useParentFetch() {
        parentFetches++;
    }

    public int rewritesUsed() {
        return rewritesUsed;
    }

    public void useRewrite() {
        rewritesUsed++;
    }

    // ---- query 状态 ----------------------------------------------------

    /** 证据（子块 / 父块）每次实际变化时递增；用来判断自上次生成以来证据是否有更新。 */
    public int version() {
        return version;
    }

    /**
     * 生成用的父块：按池内顺序把 firstHitOrder 重新编号为 0..n-1。
     * 多次 expand_parents 各自从 0 编号，直接用会让 [P#] 引用冲突。
     */
    public List<ParentEvidence> numberedParents() {
        List<ParentEvidence> numbered = new ArrayList<>();
        int order = 0;
        for (ParentEvidence parent : parents.values()) {
            numbered.add(new ParentEvidence(parent.parentChunkKey(), parent.resourceType(),
                    parent.resourceId(), parent.revisionId(), parent.kbId(), parent.headingPath(),
                    parent.body(), parent.truncated(), order++, parent.matchedChildren()));
        }
        return List.copyOf(numbered);
    }

    /** ask_user 登记的反问；非空表示本次 run 应以反问结束。 */
    public ClarifyRequest clarification() {
        return clarification;
    }

    public void requestClarification(ClarifyRequest request) {
        this.clarification = request;
    }

    public String currentQuery() {
        return currentQuery;
    }

    public List<String> rewriteHistory() {
        return List.copyOf(rewriteHistory);
    }

    public void applyRewrite(String query) {
        rewriteHistory.add(query);
        currentQuery = query;
    }

    /** 最近一次改写给出的候选 query（分解模式下为子查询），供下一轮观察使用。 */
    public List<String> rewriteSuggestions() {
        return rewriteSuggestions;
    }

    public void setRewriteSuggestions(List<String> suggestions) {
        this.rewriteSuggestions = List.copyOf(suggestions);
    }

    public List<String> stageFailures() {
        return List.copyOf(stageFailures);
    }

    public String latestReason() {
        return stageFailures.isEmpty() ? "" : stageFailures.get(stageFailures.size() - 1);
    }

    public void recordFailure(String reason) {
        if (reason != null && !reason.isBlank()) {
            stageFailures.add(reason);
        }
    }

    // ---- 共享资源 ------------------------------------------------------

    public Map<String, float[]> embeddingCache() {
        return embeddingCache;
    }

    public GraphRetrievalEnhancer.SharedBudget graphBudget() {
        return graphBudget;
    }

    /** 有界的计数摘要（不含 query 文本与证据正文），用于 DEBUG 日志。 */
    public Map<String, Object> summary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("children", children.size());
        summary.put("parents", parents.size());
        summary.put("hybridRetrievals", hybridRetrievals);
        summary.put("parentFetches", parentFetches);
        summary.put("rewritesUsed", rewritesUsed);
        summary.put("rewrites", rewriteHistory.size());
        return summary;
    }
}
