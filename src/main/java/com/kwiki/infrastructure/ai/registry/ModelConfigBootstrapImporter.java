package com.kwiki.infrastructure.ai.registry;

import com.kwiki.graph.config.GraphModelProperties;
import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.infrastructure.config.ExternalServicesProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 首次启动导入：模型目录为空时，把 Nacos 中旧的 kwiki.answer-llm、kwiki.graph.models.*、
 * kwiki.vision-model、kwiki.qwen-embedding、kwiki.indexing.embeddings.* 导入为 provider、模型与用途分配，
 * 保持与迁移前完全相同的模型选择。对话类 provider 按 (baseUrl, apiKey) 去重；embedding 以档案名作为
 * provider 名（kwiki.qwen-embedding 对应 default），使已有索引版本无需回填即可对上。
 * 整个导入在一个事务内完成，并先锁住版本行，多实例同时启动时只有一个实例真正导入。
 */
@Component
public class ModelConfigBootstrapImporter implements ModelConfigBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigBootstrapImporter.class);
    private static final int DEFAULT_RETRIES = 2;

    private final ModelConfigStore store;
    private final ModelSecretCipher cipher;
    private final ExternalServicesProperties external;
    private final GraphModelProperties graph;
    private final IndexingProperties indexing;
    private final Consumer<Runnable> inTransaction;

    @Autowired
    public ModelConfigBootstrapImporter(ModelConfigStore store, ModelSecretCipher cipher,
                                        ExternalServicesProperties external, GraphModelProperties graph,
                                        IndexingProperties indexing, PlatformTransactionManager transactions) {
        this(store, cipher, external, graph, indexing,
                body -> new TransactionTemplate(transactions).executeWithoutResult(status -> body.run()));
    }

    ModelConfigBootstrapImporter(ModelConfigStore store, ModelSecretCipher cipher,
                                 ExternalServicesProperties external, GraphModelProperties graph,
                                 IndexingProperties indexing, Consumer<Runnable> inTransaction) {
        this.store = store;
        this.cipher = cipher;
        this.external = external;
        this.graph = graph;
        this.indexing = indexing;
        this.inTransaction = inTransaction;
    }

    @Override
    public void importIfEmpty() {
        inTransaction.accept(() -> {
            store.lockVersionRow();
            if (!store.isEmpty()) {
                return;
            }
            Plan plan = plan();
            if (plan.providers.isEmpty()) {
                log.info("model catalog is empty and no legacy model configuration was found; nothing to import");
                return;
            }
            if (!cipher.configured()) {
                throw new IllegalStateException(
                        "需要把旧模型配置导入模型目录，但未配置 kwiki.model-config.master-key");
            }
            apply(plan);
            long version = store.bumpVersion();
            log.info("imported legacy model configuration: providers={} models={} version={}",
                    plan.providers.size(), plan.providers.values().stream().mapToInt(p -> p.models.size()).sum(),
                    version);
        });
    }

    // ---------- 导入计划 ----------

    /** 计划中的 provider：models 以模型名为键，同名模型合并标签。 */
    private static final class PlannedProvider {
        final String name;
        final String baseUrl;
        final String apiKey;
        final Duration timeout;
        final int retries;
        final Integer concurrency;
        final Double qps;
        final Map<String, PlannedModel> models = new LinkedHashMap<>();

        PlannedProvider(String name, String baseUrl, String apiKey, Duration timeout, int retries,
                        Integer concurrency, Double qps) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.timeout = timeout;
            this.retries = retries;
            this.concurrency = concurrency;
            this.qps = qps;
        }

        PlannedModel model(String modelName) {
            return models.computeIfAbsent(modelName, PlannedModel::new);
        }
    }

    private static final class PlannedModel {
        final String name;
        final Set<ModelTag> tags = EnumSet.noneOf(ModelTag.class);
        Integer dimensions;

        PlannedModel(String name) {
            this.name = name;
        }
    }

    private record Target(PlannedProvider provider, PlannedModel model) {
    }

    private static final class Plan {
        final Map<String, PlannedProvider> providers = new LinkedHashMap<>();
        final Map<ModelPurpose, Target> assignments = new EnumMap<>(ModelPurpose.class);
    }

    private Plan plan() {
        Plan plan = new Plan();
        // embedding 先占用档案名（必须与索引版本中的 provider 名一致），对话 provider 再避让重名
        embeddings(plan);
        Map<String, PlannedProvider> byConnection = new LinkedHashMap<>();
        ExternalServicesProperties.AnswerLlm answer = external == null ? null : external.answerLlm();
        Target answerTarget = null;
        if (answer != null && !blank(answer.baseUrl()) && !blank(answer.apiKey()) && !blank(answer.model())) {
            PlannedProvider provider = chatProvider(plan, byConnection, "answer-llm", answer.baseUrl(),
                    answer.apiKey(), answer.requestTimeout(), null, null, null);
            answerTarget = target(provider, answer.model(), ModelTag.CHAT);
            for (ModelPurpose purpose : ModelPurpose.values()) {
                if (purpose.requiredTag() == ModelTag.CHAT) {
                    plan.assignments.put(purpose, answerTarget);
                }
            }
        }
        graphTarget(plan, byConnection, answer, graph == null ? null : graph.extraction(), "graph-extraction",
                ModelPurpose.GRAPH_EXTRACTION);
        graphTarget(plan, byConnection, answer, graph == null ? null : graph.summary(), "graph-summary",
                ModelPurpose.GRAPH_SUMMARY);
        ExternalServicesProperties.VisionModel vision = external == null ? null : external.visionModel();
        if (vision != null && vision.isConfigured() && !blank(vision.model())) {
            PlannedProvider provider = chatProvider(plan, byConnection, "vision", vision.baseUrl(), vision.apiKey(),
                    vision.requestTimeout(), vision.maxRetries(), vision.concurrency(), null);
            plan.assignments.put(ModelPurpose.VISION_SUMMARY, target(provider, vision.model(), ModelTag.VISION));
        }
        return plan;
    }

    private void graphTarget(Plan plan, Map<String, PlannedProvider> byConnection,
                             ExternalServicesProperties.AnswerLlm answer, GraphModelProperties.Model configured,
                             String name, ModelPurpose purpose) {
        if (configured == null) return;
        String baseUrl = firstNonBlank(configured.baseUrl(), answer == null ? null : answer.baseUrl());
        String apiKey = firstNonBlank(configured.apiKey(), answer == null ? null : answer.apiKey());
        String model = firstNonBlank(configured.model(), answer == null ? null : answer.model());
        if (blank(baseUrl) || blank(apiKey) || blank(model)) return;
        Duration timeout = configured.requestTimeout() != null ? configured.requestTimeout()
                : answer != null && answer.requestTimeout() != null ? answer.requestTimeout() : Duration.ofSeconds(120);
        PlannedProvider provider = chatProvider(plan, byConnection, name, baseUrl, apiKey, timeout,
                configured.maxRetries(), configured.maxConcurrency(), configured.qps());
        plan.assignments.put(purpose, target(provider, model, ModelTag.CHAT));
    }

    /** 对话类 provider 按规范化 (baseUrl, apiKey) 去重；限流参数只在新建 provider 时生效。 */
    private static PlannedProvider chatProvider(Plan plan, Map<String, PlannedProvider> byConnection, String name,
                                                String baseUrl, String apiKey, Duration timeout, Integer retries,
                                                Integer concurrency, Double qps) {
        String key = normalize(baseUrl) + "\u0000" + apiKey.trim();
        return byConnection.computeIfAbsent(key, k -> {
            PlannedProvider created = new PlannedProvider(uniqueName(plan, name), normalize(baseUrl), apiKey.trim(),
                    timeout == null ? Duration.ofSeconds(120) : timeout,
                    retries == null ? DEFAULT_RETRIES : retries, concurrency, qps);
            plan.providers.put(created.name, created);
            return created;
        });
    }

    private void embeddings(Plan plan) {
        Map<String, List<IndexingProperties.Manifest>> manifestsByProfile = new LinkedHashMap<>();
        if (indexing != null && indexing.manifests() != null) {
            for (IndexingProperties.Manifest manifest : indexing.manifests()) {
                manifestsByProfile.computeIfAbsent(manifest.embeddingProfile(), k -> new ArrayList<>()).add(manifest);
            }
        }
        ExternalServicesProperties.QwenEmbedding qwen = external == null ? null : external.qwenEmbedding();
        if (qwen != null && !blank(qwen.baseUrl()) && !blank(qwen.apiKey()) && !blank(qwen.model())) {
            PlannedProvider provider = embeddingProvider(plan, IndexingProperties.DEFAULT_EMBEDDING_PROFILE,
                    qwen.baseUrl(), qwen.apiKey(), qwen.requestTimeout());
            embeddingModel(provider, qwen.model(), qwen.dimensions());
            manifestsByProfile.getOrDefault(IndexingProperties.DEFAULT_EMBEDDING_PROFILE, List.of())
                    .forEach(m -> embeddingModel(provider, m.embeddingModel(), m.dimensions()));
        }
        if (indexing != null && indexing.embeddings() != null) {
            indexing.embeddings().forEach((profile, connection) -> {
                if (connection == null || blank(connection.baseUrl()) || blank(connection.apiKey())) return;
                List<IndexingProperties.Manifest> manifests = manifestsByProfile.getOrDefault(profile, List.of());
                if (manifests.isEmpty()) {
                    log.warn("embedding profile {} is not referenced by any manifest; skipped", profile);
                    return;
                }
                PlannedProvider provider = embeddingProvider(plan, profile, connection.baseUrl(),
                        connection.apiKey(), connection.requestTimeout());
                manifests.forEach(m -> embeddingModel(provider, m.embeddingModel(), m.dimensions()));
            });
        }
    }

    /** embedding provider 名必须等于档案名，不参与去重。 */
    private static PlannedProvider embeddingProvider(Plan plan, String profile, String baseUrl, String apiKey,
                                                     Duration timeout) {
        PlannedProvider provider = new PlannedProvider(profile, normalize(baseUrl), apiKey.trim(),
                timeout == null ? Duration.ofSeconds(30) : timeout, DEFAULT_RETRIES, null, null);
        plan.providers.put(profile, provider);
        return provider;
    }

    private static void embeddingModel(PlannedProvider provider, String modelName, Integer dimensions) {
        if (blank(modelName) || dimensions == null) return;
        PlannedModel model = provider.model(modelName.trim());
        model.tags.add(ModelTag.EMBEDDING);
        if (model.dimensions != null && !model.dimensions.equals(dimensions)) {
            log.warn("embedding model {}/{} is declared with dimensions {} and {}; keeping {}",
                    provider.name, modelName, model.dimensions, dimensions, model.dimensions);
            return;
        }
        model.dimensions = dimensions;
    }

    private static Target target(PlannedProvider provider, String modelName, ModelTag tag) {
        PlannedModel model = provider.model(modelName.trim());
        model.tags.add(tag);
        return new Target(provider, model);
    }

    // ---------- 写入 ----------

    private void apply(Plan plan) {
        Map<PlannedModel, Long> ids = new LinkedHashMap<>();
        for (PlannedProvider provider : plan.providers.values()) {
            long providerId = store.insertProvider(new ModelProvider(null, provider.name,
                    ModelProvider.OPENAI_COMPATIBLE, provider.baseUrl, cipher.encrypt(provider.apiKey),
                    ModelSecretCipher.hint(provider.apiKey), (int) Math.min(600_000, Math.max(1000,
                    provider.timeout.toMillis())), Math.min(10, Math.max(0, provider.retries)),
                    provider.concurrency, provider.qps, true));
            for (PlannedModel model : provider.models.values()) {
                boolean chat = model.tags.contains(ModelTag.CHAT);
                ids.put(model, store.insertModel(new ModelDefinition(null, providerId, model.name, null, model.tags,
                        model.tags.contains(ModelTag.EMBEDDING) ? model.dimensions : null, null, chat, false,
                        true)));
            }
        }
        List<ModelAssignment> assignments = new ArrayList<>();
        plan.assignments.forEach((purpose, target) -> {
            Long id = ids.get(target.model());
            if (id != null) {
                assignments.add(new ModelAssignment(purpose, id, null, null, null));
            }
        });
        store.saveAssignments(assignments);
    }

    private static String uniqueName(Plan plan, String base) {
        String name = base;
        for (int i = 2; plan.providers.containsKey(name); i++) {
            name = base + "-" + i;
        }
        return name;
    }

    private static String normalize(String baseUrl) {
        return baseUrl.trim().replaceAll("/+$", "");
    }

    private static String firstNonBlank(String a, String b) {
        return blank(a) ? b : a;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
