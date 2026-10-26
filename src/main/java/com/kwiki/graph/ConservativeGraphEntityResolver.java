package com.kwiki.graph;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 知识库内实体消解：
 * <ol>
 *   <li>归一化名称（NFKC、去空白、小写）与类型都相同 → 直接合并为同一实体，不再按所在块区分；</li>
 *   <li>只是别名命中，或同名但类型不同 → 有争议，交给 {@link GraphEntityMergeJudge} 判定，
 *       判定为同一事物才合并，结果缓存；无法判定时不合并；</li>
 *   <li>都不命中 → 登记新实体。</li>
 * </ol>
 * 合并后返回已登记实体的规范名称与类型，并把候选的名称与别名追加为该实体的别名。
 */
public final class ConservativeGraphEntityResolver implements GraphEntityResolutionPort {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ConservativeGraphEntityResolver.class);

    /** 每个候选最多判定的争议实体数，限制模型调用量。 */
    static final int MAX_DISPUTED = 3;
    static final int SNIPPET_RADIUS = 120;
    static final int SNIPPET_MAX = 500;

    private final GraphEntityRegistryPort registry;
    private final GraphEntityMergeJudge judge;

    public ConservativeGraphEntityResolver(GraphEntityRegistryPort registry) {
        this(registry, GraphEntityMergeJudge.none());
    }

    public ConservativeGraphEntityResolver(GraphEntityRegistryPort registry, GraphEntityMergeJudge judge) {
        this.registry = registry;
        this.judge = judge == null ? GraphEntityMergeJudge.none() : judge;
    }

    @Override
    public List<GraphEntity> resolve(long kbId, List<GraphEntity> candidates,
                                     String context, String resolverVersion) {
        if (kbId <= 0 || resolverVersion == null || resolverVersion.isBlank()) {
            throw new IllegalArgumentException("实体消歧身份无效");
        }
        if (candidates == null) {
            return List.of();
        }
        List<GraphEntity> resolved = new ArrayList<>(candidates.size());
        for (GraphEntity candidate : candidates) {
            resolved.add(resolveOne(kbId, candidate, context, resolverVersion));
        }
        return resolved;
    }

    private GraphEntity resolveOne(long kbId, GraphEntity candidate, String context, String version) {
        String normalized = GraphEntityNames.normalize(candidate.canonicalName());
        Set<String> terms = GraphEntityNames.terms(candidate.canonicalName(), candidate.aliases());

        Optional<GraphEntityRegistryPort.RegisteredEntity> exact =
                registry.findByName(kbId, normalized, candidate.entityType(), version);
        if (exact.isPresent()) {
            return merged(kbId, exact.get(), terms, version);
        }

        String snippet = snippet(context, candidate.canonicalName());
        String candidateKey = candidateKey(normalized, candidate);
        for (GraphEntityRegistryPort.RegisteredEntity disputed
                : registry.findByTerms(kbId, terms, version, MAX_DISPUTED)) {
            if (sameEntity(kbId, version, candidate, snippet, candidateKey, disputed)) {
                return merged(kbId, disputed, terms, version);
            }
        }

        GraphEntityRegistryPort.RegisteredEntity registered =
                registry.register(kbId, candidate, normalized, snippet, version);
        return merged(kbId, registered, terms, version);
    }

    private boolean sameEntity(long kbId, String version, GraphEntity candidate, String snippet,
                               String candidateKey, GraphEntityRegistryPort.RegisteredEntity disputed) {
        Optional<Boolean> cached = registry.findMergeDecision(kbId, version, candidateKey, disputed.entityId());
        if (cached.isPresent()) {
            return cached.get();
        }
        Optional<GraphEntityMergeJudge.Verdict> verdict = judge.judge(new GraphEntityMergeJudge.Candidate(
                candidate.canonicalName(), candidate.entityType(), candidate.aliases(), snippet), disputed);
        if (verdict.isEmpty()) {
            return false; // 无法判定：保守不合并，也不缓存，下次仍会尝试判定
        }
        registry.saveMergeDecision(kbId, version, candidateKey, disputed.entityId(),
                verdict.get().sameEntity(), verdict.get().judge(), verdict.get().reason());
        log.debug("graph entity merge judged kb={} candidate={}({}) existing={}({}) same={} reason={}",
                kbId, candidate.canonicalName(), candidate.entityType(), disputed.canonicalName(),
                disputed.entityType(), verdict.get().sameEntity(), verdict.get().reason());
        return verdict.get().sameEntity();
    }

    private GraphEntity merged(long kbId, GraphEntityRegistryPort.RegisteredEntity entity,
                               Set<String> candidateTerms, String version) {
        Set<String> aliases = new LinkedHashSet<>(candidateTerms);
        aliases.remove(GraphEntityNames.normalize(entity.canonicalName()));
        if (!aliases.isEmpty()) {
            registry.addAliases(kbId, entity.entityId(), aliases, version);
        }
        return entity.toEntity();
    }

    /** 候选身份：归一化名称、类型与别名，用作判定缓存键。 */
    static String candidateKey(String normalizedName, GraphEntity candidate) {
        List<String> aliases = new ArrayList<>(GraphEntityNames.terms(null, candidate.aliases()));
        aliases.sort(null);
        return sha256(normalizedName + "|" + candidate.entityType() + "|" + String.join(",", aliases));
    }

    /** 名称在正文中的前后片段（找不到时取开头），供争议判定参考。 */
    static String snippet(String context, String name) {
        if (context == null || context.isBlank()) {
            return "";
        }
        String text = context.strip();
        int at = name == null ? -1 : text.indexOf(name);
        int start = at < 0 ? 0 : Math.max(0, at - SNIPPET_RADIUS);
        int end = at < 0 ? Math.min(text.length(), SNIPPET_RADIUS * 2)
                : Math.min(text.length(), at + name.length() + SNIPPET_RADIUS);
        String snippet = text.substring(start, end);
        return snippet.length() > SNIPPET_MAX ? snippet.substring(0, SNIPPET_MAX) : snippet;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法计算实体候选指纹", e);
        }
    }
}
