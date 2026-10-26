package com.kwiki.graph;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨块实体合并：同名同类型直接合并，别名命中或同名不同类型交给判定，判定结果缓存。 */
class ConservativeGraphEntityResolverTest {

    private static final String VERSION = "resolver-v1";

    private final FakeRegistry registry = new FakeRegistry();
    private final AtomicInteger judged = new AtomicInteger();

    private ConservativeGraphEntityResolver resolver(Optional<Boolean> verdict) {
        return new ConservativeGraphEntityResolver(registry, (candidate, existing) -> {
            judged.incrementAndGet();
            return verdict.map(same -> new GraphEntityMergeJudge.Verdict(same, "测试判定", "test"));
        });
    }

    private static GraphEntity entity(String name, GraphEntityType type, String... aliases) {
        return new GraphEntity(null, name, type, List.of(aliases));
    }

    @Test
    void sameNameAndTypeInDifferentChunksBecomeOneEntity() {
        ConservativeGraphEntityResolver resolver = resolver(Optional.empty());

        GraphEntity first = resolver.resolve(1, List.of(entity("Kwiki", GraphEntityType.PRODUCT)),
                "Kwiki uses ArcadeDB.", VERSION).getFirst();
        GraphEntity second = resolver.resolve(1, List.of(entity("Kwiki", GraphEntityType.PRODUCT)),
                "Another chunk mentioning Kwiki.", VERSION).getFirst();

        assertThat(second.entityId()).isEqualTo(first.entityId());
        assertThat(judged).hasValue(0);
    }

    @Test
    void normalizationIgnoresCaseWhitespaceAndFullWidth() {
        ConservativeGraphEntityResolver resolver = resolver(Optional.empty());

        String id = resolver.resolve(1, List.of(entity("ArcadeDB", GraphEntityType.TECHNOLOGY)), "a", VERSION)
                .getFirst().entityId();

        for (String variant : List.of("arcadedb", "  ArcadeDB ", "ＡｒｃａｄｅＤＢ")) {
            GraphEntity resolved = resolver.resolve(1, List.of(entity(variant, GraphEntityType.TECHNOLOGY)),
                    "b", VERSION).getFirst();
            assertThat(resolved.entityId()).as(variant).isEqualTo(id);
            // 合并后沿用已登记实体的规范名称
            assertThat(resolved.canonicalName()).isEqualTo("ArcadeDB");
        }
    }

    @Test
    void knowledgeBasesAndResolverVersionsStaySeparate() {
        ConservativeGraphEntityResolver resolver = resolver(Optional.empty());
        GraphEntity candidate = entity("Kwiki", GraphEntityType.PRODUCT);

        String kb1 = resolver.resolve(1, List.of(candidate), "x", VERSION).getFirst().entityId();
        String kb2 = resolver.resolve(2, List.of(candidate), "x", VERSION).getFirst().entityId();
        String v2 = resolver.resolve(1, List.of(candidate), "x", "resolver-v2").getFirst().entityId();

        assertThat(kb2).isNotEqualTo(kb1);
        assertThat(v2).isNotEqualTo(kb1);
    }

    @Test
    void aliasMatchIsMergedOnlyWhenTheJudgeAgreesAndTheVerdictIsCached() {
        ConservativeGraphEntityResolver resolver = resolver(Optional.of(true));
        String id = resolver.resolve(1, List.of(entity("Kubernetes", GraphEntityType.TECHNOLOGY, "K8s")),
                "Kubernetes (K8s) 编排容器", VERSION).getFirst().entityId();

        GraphEntity viaAlias = resolver.resolve(1, List.of(entity("k8s", GraphEntityType.TECHNOLOGY)),
                "用 k8s 部署", VERSION).getFirst();
        GraphEntity again = resolver.resolve(1, List.of(entity("k8s", GraphEntityType.TECHNOLOGY)),
                "另一处 k8s", VERSION).getFirst();

        assertThat(viaAlias.entityId()).isEqualTo(id);
        assertThat(again.entityId()).isEqualTo(id);
        assertThat(judged).hasValue(1);
    }

    @Test
    void judgedDifferentEntitiesStaySeparate() {
        ConservativeGraphEntityResolver resolver = resolver(Optional.of(false));
        String distributedLock = resolver.resolve(1, List.of(entity("分布式锁", GraphEntityType.CONCEPT, "锁")),
                "Redisson 分布式锁", VERSION).getFirst().entityId();

        String doorLock = resolver.resolve(1, List.of(entity("锁", GraphEntityType.PRODUCT)),
                "门锁安装说明", VERSION).getFirst().entityId();

        assertThat(doorLock).isNotEqualTo(distributedLock);
        assertThat(judged).hasValue(1);
    }

    @Test
    void sameNameWithDifferentTypeIsDisputed() {
        ConservativeGraphEntityResolver resolver = resolver(Optional.of(true));
        String product = resolver.resolve(1, List.of(entity("Redis", GraphEntityType.PRODUCT)), "x", VERSION)
                .getFirst().entityId();

        GraphEntity technology = resolver.resolve(1, List.of(entity("Redis", GraphEntityType.TECHNOLOGY)),
                "y", VERSION).getFirst();

        assertThat(technology.entityId()).isEqualTo(product);
        assertThat(technology.entityType()).isEqualTo(GraphEntityType.PRODUCT);
        assertThat(judged).hasValue(1);
    }

    @Test
    void undecidableDisputeIsNotMergedAndNotCached() {
        ConservativeGraphEntityResolver resolver = resolver(Optional.empty());
        String first = resolver.resolve(1, List.of(entity("Kubernetes", GraphEntityType.TECHNOLOGY, "K8s")),
                "x", VERSION).getFirst().entityId();

        String second = resolver.resolve(1, List.of(entity("K8s", GraphEntityType.TECHNOLOGY)), "y", VERSION)
                .getFirst().entityId();
        resolver.resolve(1, List.of(entity("K8s", GraphEntityType.TECHNOLOGY)), "z", VERSION);

        assertThat(second).isNotEqualTo(first);
        assertThat(registry.decisions).isEmpty();
    }

    @Test
    void snippetKeepsTheTextAroundTheName() {
        String context = "a".repeat(300) + "Kwiki" + "b".repeat(300);

        String snippet = ConservativeGraphEntityResolver.snippet(context, "Kwiki");

        assertThat(snippet).contains("Kwiki").hasSize(ConservativeGraphEntityResolver.SNIPPET_RADIUS * 2 + 5);
        assertThat(ConservativeGraphEntityResolver.snippet(null, "Kwiki")).isEmpty();
    }

    /** 按 kb + 版本隔离的内存注册表，语义与 MySQL 实现一致。 */
    private static final class FakeRegistry implements GraphEntityRegistryPort {
        private final Map<String, RegisteredEntity> byName = new LinkedHashMap<>();
        private final Map<String, List<String>> aliases = new HashMap<>();
        final Map<String, Boolean> decisions = new HashMap<>();
        private long nextId = 1;

        private static String nameKey(long kb, String name, GraphEntityType type, String version) {
            return kb + "|" + version + "|" + type + "|" + name;
        }

        @Override
        public Optional<RegisteredEntity> findByName(long kbId, String normalizedName, GraphEntityType type,
                                                     String resolverVersion) {
            return Optional.ofNullable(byName.get(nameKey(kbId, normalizedName, type, resolverVersion)));
        }

        @Override
        public List<RegisteredEntity> findByTerms(long kbId, Collection<String> normalizedTerms,
                                                  String resolverVersion, int limit) {
            List<RegisteredEntity> found = new ArrayList<>();
            for (Map.Entry<String, RegisteredEntity> entry : byName.entrySet()) {
                String[] parts = entry.getKey().split("\\|", 4);
                if (!parts[0].equals(String.valueOf(kbId)) || !parts[1].equals(resolverVersion)) {
                    continue;
                }
                RegisteredEntity entity = entry.getValue();
                boolean nameHit = normalizedTerms.contains(parts[3]);
                boolean aliasHit = aliases.getOrDefault(kbId + "|" + resolverVersion + "|" + entity.entityId(), List.of())
                        .stream().anyMatch(normalizedTerms::contains);
                if ((nameHit || aliasHit) && found.size() < limit) {
                    found.add(entity);
                }
            }
            return found;
        }

        @Override
        public RegisteredEntity register(long kbId, GraphEntity candidate, String normalizedName,
                                         String contextSnippet, String resolverVersion) {
            return byName.computeIfAbsent(nameKey(kbId, normalizedName, candidate.entityType(), resolverVersion),
                    key -> new RegisteredEntity("e-" + nextId++, candidate.canonicalName(),
                            candidate.entityType(), candidate.aliases(), contextSnippet));
        }

        @Override
        public void addAliases(long kbId, String entityId, Collection<String> normalizedAliases,
                               String resolverVersion) {
            List<String> list = aliases.computeIfAbsent(kbId + "|" + resolverVersion + "|" + entityId,
                    key -> new ArrayList<>());
            for (String alias : normalizedAliases) {
                if (!list.contains(alias)) {
                    list.add(alias);
                }
            }
        }

        @Override
        public Optional<Boolean> findMergeDecision(long kbId, String resolverVersion, String candidateKey,
                                                   String entityId) {
            return Optional.ofNullable(decisions.get(kbId + "|" + resolverVersion + "|" + candidateKey + "|" + entityId));
        }

        @Override
        public void saveMergeDecision(long kbId, String resolverVersion, String candidateKey, String entityId,
                                      boolean sameEntity, String judge, String reason) {
            decisions.put(kbId + "|" + resolverVersion + "|" + candidateKey + "|" + entityId, sameEntity);
        }
    }
}
