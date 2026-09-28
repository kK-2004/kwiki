package com.kwiki.mcp;

import com.kwiki.wiki.access.AuthorizationScopeResolver;
import com.kwiki.rag.retrieval.HybridRetrievalOrchestrator;
import com.kwiki.rag.retrieval.ParentEvidenceChunk;
import com.kwiki.rag.retrieval.RetrievalBudgets;
import com.kwiki.rag.retrieval.RetrievalStrategy;
import com.kwiki.wiki.access.AuthorizationScope;
import com.kwiki.wiki.domain.KnowledgeBase;
import com.kwiki.wiki.persistence.KnowledgeBaseRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 工具的业务实现：薄适配到既有的检索编排与授权作用域解析。
 *
 * <p>检索范围是 token 对应用户有权限的范围：默认覆盖其全部可检索
 * 知识库（管理员为全库），传 kb_ids 时在查询层显式收窄。指定知识库
 * 的 id 若不在调用方可检索范围内，直接拒绝并列出被拒 id——知道 id
 * 不等于获得权限。工具入参不承载任何身份信息。</p>
 */
@Component
@ConditionalOnProperty(prefix = "kwiki.mcp", name = "enabled", havingValue = "true")
public class McpSearchTools {

    static final int MAX_QUERY_LENGTH = 512;
    static final int MAX_LISTED_KNOWLEDGE_BASES = 200;

    private final AuthorizationScopeResolver scopes;
    private final HybridRetrievalOrchestrator retrieval;
    private final KnowledgeBaseRepository knowledgeBases;
    private final RetrievalBudgets budgets;

    public McpSearchTools(AuthorizationScopeResolver scopes,
                          HybridRetrievalOrchestrator retrieval,
                          KnowledgeBaseRepository knowledgeBases,
                          RetrievalBudgets budgets) {
        this.scopes = scopes;
        this.retrieval = retrieval;
        this.knowledgeBases = knowledgeBases;
        this.budgets = budgets;
    }

    /** 列出 token 对应用户可检索的全部知识库（id/名称/描述）。 */
    public Map<String, Object> listKnowledgeBases(McpActor actor) {
        AuthorizationScope scope = scopes.resolve(actor.currentUser());
        List<KnowledgeBase> accessible = new ArrayList<>();
        if (actor.admin()) {
            for (KnowledgeBase kb : knowledgeBases.findAll()) {
                if (!kb.isArchived()) {
                    accessible.add(kb);
                }
            }
        } else if (!scope.accessibleKbIds().isEmpty()) {
            for (KnowledgeBase kb : knowledgeBases.findAllById(scope.accessibleKbIds())) {
                if (!kb.isArchived()) {
                    accessible.add(kb);
                }
            }
        }
        accessible.sort(Comparator.comparing(kb -> kb.getId() == null ? Long.MAX_VALUE : kb.getId()));
        boolean truncated = accessible.size() > MAX_LISTED_KNOWLEDGE_BASES;
        List<Map<String, Object>> entries = accessible.stream()
                .limit(MAX_LISTED_KNOWLEDGE_BASES)
                .map(kb -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("kbId", kb.getId());
                    entry.put("name", kb.getName());
                    entry.put("description", kb.getDescription() == null ? "" : kb.getDescription());
                    return entry;
                })
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBases", entries);
        result.put("total", accessible.size());
        result.put("truncated", truncated);
        return result;
    }

    /**
     * 在调用方权限范围内检索。默认检索其全部可检索知识库；
     * kb_ids 收窄到指定知识库（越权 id 会被整体拒绝）。
     *
     * @param query       检索问题或关键词（不超过 512 字符）
     * @param kbIds       可选：限定检索的知识库 id
     * @param strategy    可选：hybrid（默认）/ bm25 / vector
     * @param topK        可选：每分支召回数，默认 10
     * @param parentLimit 可选：返回的父分块数量，默认取系统预算
     */
    public Map<String, Object> search(McpActor actor,
                                      String query,
                                      List<Long> kbIds,
                                      String strategy,
                                      Integer topK,
                                      Integer parentLimit) {
        if (!actor.hasSearchScope()) {
            throw new McpToolException("insufficient_scope",
                    "token does not carry scope " + McpActor.SEARCH_SCOPE);
        }
        if (query == null || query.isBlank()) {
            throw new McpToolException("invalid_query", "query must not be blank");
        }
        String trimmed = query.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            throw new McpToolException("invalid_query",
                    "query exceeds " + MAX_QUERY_LENGTH + " characters");
        }
        int branchTopK = clamp(topK == null ? 10 : topK, 1, budgets.childBranchTopK);
        int parents = clamp(parentLimit == null ? budgets.distinctParentLimit : parentLimit,
                1, budgets.distinctParentLimit);
        RetrievalStrategy retrievalStrategy = parseStrategy(strategy);

        AuthorizationScope scope = resolveScope(actor, kbIds);
        HybridRetrievalOrchestrator.RetrievalOutcome outcome;
        try {
            outcome = retrieval.retrieve(
                    scope,
                    new com.kwiki.rag.tool.SearchArguments(
                            List.of(trimmed), retrievalStrategy, branchTopK),
                    new HybridRetrievalOrchestrator.Accumulation(),
                    parents,
                    budgets.parentContextCharBudget);
        } catch (HybridRetrievalOrchestrator.StaleScopeException e) {
            throw new McpToolException("scope_changed",
                    "permissions changed during retrieval; retry the request");
        }
        return evidence(actor, scope, outcome);
    }

    private AuthorizationScope resolveScope(McpActor actor, List<Long> kbIds) {
        AuthorizationScope base = scopes.resolve(actor.currentUser());
        if (kbIds == null || kbIds.isEmpty()) {
            return base;
        }
        Set<Long> requested = Set.copyOf(kbIds);
        if (!actor.admin() && !base.accessibleKbIds().containsAll(requested)) {
            List<Long> denied = requested.stream()
                    .filter(id -> !base.accessibleKbIds().contains(id))
                    .sorted()
                    .toList();
            throw new McpToolException("knowledge_base_denied",
                    "kb_ids outside the token's retrieval scope: " + denied);
        }
        return scopes.resolve(actor.currentUser(), requested, Set.of());
    }

    private Map<String, Object> evidence(McpActor actor,
                                         AuthorizationScope scope,
                                         HybridRetrievalOrchestrator.RetrievalOutcome outcome) {
        List<Long> hitKbIds = outcome.parents().stream()
                .map(ParentEvidenceChunk::kbId)
                .distinct()
                .toList();
        Map<Long, String> kbNames = new LinkedHashMap<>();
        for (KnowledgeBase kb : hitKbIds.isEmpty() ? List.<KnowledgeBase>of() : knowledgeBases.findAllById(hitKbIds)) {
            kbNames.put(kb.getId(), kb.getName());
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        for (ParentEvidenceChunk parent : outcome.parents()) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("kbId", parent.kbId());
            hit.put("kbName", kbNames.get(parent.kbId()));
            hit.put("resourceType", parent.resourceType());
            hit.put("resourceId", parent.resourceId());
            hit.put("revisionId", parent.revisionId());
            hit.put("headingPath", parent.headingPath());
            hit.put("content", parent.content());
            hit.put("score", parent.bestRrfScore());
            hit.put("matchedChildren", parent.matchedChildren().stream()
                    .map(child -> {
                        Map<String, Object> childView = new LinkedHashMap<>();
                        childView.put("chunkKey", child.chunkKey());
                        childView.put("headingPath", child.headingPath());
                        childView.put("charStart", child.charStart());
                        childView.put("charEnd", child.charEnd());
                        return childView;
                    })
                    .toList());
            hits.add(hit);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", hits);
        result.put("hitCount", hits.size());
        result.put("degradations", outcome.degradations());
        return result;
    }

    private static RetrievalStrategy parseStrategy(String strategy) {
        if (strategy == null || strategy.isBlank()) {
            return RetrievalStrategy.HYBRID;
        }
        return switch (strategy.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "hybrid" -> RetrievalStrategy.HYBRID;
            case "bm25" -> RetrievalStrategy.BM25;
            case "vector" -> RetrievalStrategy.VECTOR;
            default -> throw new McpToolException("invalid_strategy",
                    "strategy must be one of: hybrid, bm25, vector");
        };
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 业务性拒绝：以结构化错误码返回给调用方，不带内部细节。 */
    public static final class McpToolException extends RuntimeException {

        private final String code;

        public McpToolException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
