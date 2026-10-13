package com.kwiki.infrastructure.ai.registry;

import com.kk2004.common.exception.BusinessException;
import com.kwiki.infrastructure.observability.SecretRedaction;

import dev.langchain4j.model.chat.ChatModel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 模型目录的管理端用例：provider / 模型增删改、用途分配、连通性测试与模型发现。
 * 每次写入在同一事务内递增配置版本并发布 {@link ModelConfigChangedEvent}，
 * 事务提交后由 {@link ModelConfigRefresher} 重新加载并广播。
 * 校验失败返回 400，被引用导致的冲突返回 409；任何返回值都不含 API key 明文。
 */
@Service
public class ModelConfigAdminService {

    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int DEFAULT_RETRIES = 2;
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(20);

    private final ModelConfigStore store;
    private final ModelSecretCipher cipher;
    private final ModelClientFactory factory;
    private final DefaultModelRegistry registry;
    private final ApplicationEventPublisher events;
    private final boolean nativeTools;

    public ModelConfigAdminService(ModelConfigStore store, ModelSecretCipher cipher, ModelClientFactory factory,
                                   DefaultModelRegistry registry, ApplicationEventPublisher events,
                                   @Value("${kwiki.agentic.tool-mode:native}") String toolMode) {
        this.store = store;
        this.cipher = cipher;
        this.factory = factory;
        this.registry = registry;
        this.events = events;
        this.nativeTools = "native".equals(toolMode);
    }

    // ---------- 请求与视图 ----------

    public record ProviderRequest(String name, String baseUrl, String apiKey, Integer requestTimeoutMs,
                                  Integer maxRetries, Integer maxConcurrency, Double qps, Boolean enabled) {
        @Override
        public String toString() {
            return "ProviderRequest[name=" + name + ", baseUrl=" + baseUrl + ", apiKey=****]";
        }
    }

    public record ProviderView(long id, String name, String protocol, String baseUrl, String apiKeyHint,
                               int requestTimeoutMs, int maxRetries, Integer maxConcurrency, Double qps,
                               boolean enabled, List<ModelView> models) {
    }

    public record ModelRequest(String modelName, String displayName, Set<String> tags, Integer dimensions,
                               Integer maxOutputTokens, Boolean supportsTools, Boolean supportsThinking,
                               Boolean enabled, Map<String, Object> extra) {
        public ModelRequest(String modelName, String displayName, Set<String> tags, Integer dimensions,
                            Integer maxOutputTokens, Boolean supportsTools, Boolean supportsThinking,
                            Boolean enabled) {
            this(modelName, displayName, tags, dimensions, maxOutputTokens, supportsTools, supportsThinking,
                    enabled, null);
        }
    }

    public record ModelView(long id, long providerId, String modelName, String displayName, List<String> tags,
                            Integer dimensions, Integer maxOutputTokens, boolean supportsTools,
                            boolean supportsThinking, boolean enabled, List<String> usedBy,
                            Map<String, Object> extra) {
    }

    public record AssignmentRequest(String purpose, Long modelId, Double temperature, Integer maxTokens,
                                    Boolean thinking, Map<String, Object> extra) {
        public AssignmentRequest(String purpose, Long modelId, Double temperature, Integer maxTokens,
                                 Boolean thinking) {
            this(purpose, modelId, temperature, maxTokens, thinking, null);
        }
    }

    public record AssignmentView(String purpose, String label, String description, String requiredTag,
                                 boolean requiresTools, double defaultTemperature, Long modelId,
                                 Double temperature, Integer maxTokens, Boolean thinking, String effectiveModel,
                                 Map<String, Object> extra) {
    }

    /** providerId 非空且 apiKey 为空时使用已保存的 key；表单中的其他字段优先。 */
    public record ConnectionTest(Long providerId, String baseUrl, String apiKey, Integer requestTimeoutMs) {
        @Override
        public String toString() {
            return "ConnectionTest[providerId=" + providerId + ", baseUrl=" + baseUrl + ", apiKey=****]";
        }
    }

    public record TestResult(boolean ok, String message, long latencyMs) {
    }

    public record StatusView(long version, Instant loadedAt, String lastError, long databaseVersion) {
    }

    // ---------- provider ----------

    public List<ProviderView> providers() {
        ModelCatalog catalog = store.load();
        return catalog.providers().stream().map(p -> view(p, catalog)).toList();
    }

    @Transactional
    public ProviderView createProvider(ProviderRequest request) {
        ModelCatalog catalog = store.load();
        String name = requireName(request.name());
        if (catalog.providerNamed(name).isPresent()) {
            throw conflict("provider 名称已存在：" + name);
        }
        String key = request.apiKey() == null ? "" : request.apiKey().trim();
        if (key.isEmpty()) {
            throw invalid("API key 不能为空");
        }
        ModelProvider provider = new ModelProvider(null, name, ModelProvider.OPENAI_COMPATIBLE,
                requireBaseUrl(request.baseUrl()), cipher.encrypt(key), ModelSecretCipher.hint(key),
                timeout(request.requestTimeoutMs()), retries(request.maxRetries()),
                concurrency(request.maxConcurrency()), qps(request.qps()), !Boolean.FALSE.equals(request.enabled()));
        long id = store.insertProvider(provider);
        changed();
        return view(provider.withId(id), store.load());
    }

    @Transactional
    public ProviderView updateProvider(long id, ProviderRequest request) {
        ModelCatalog catalog = store.load();
        ModelProvider existing = catalog.provider(id).orElseThrow(() -> notFound("provider 不存在：" + id));
        String name = requireName(request.name());
        if (!name.equals(existing.name()) && catalog.providerNamed(name).isPresent()) {
            throw conflict("provider 名称已存在：" + name);
        }
        boolean enabled = !Boolean.FALSE.equals(request.enabled());
        if (existing.enabled() && !enabled) {
            List<String> refs = providerReferences(existing, catalog);
            if (!refs.isEmpty()) {
                throw conflict("provider 仍被引用，不能停用：" + String.join("、", refs));
            }
        }
        String key = request.apiKey() == null ? "" : request.apiKey().trim();
        ModelProvider updated = new ModelProvider(id, name, existing.protocol(), requireBaseUrl(request.baseUrl()),
                key.isEmpty() ? existing.apiKeyCipher() : cipher.encrypt(key),
                key.isEmpty() ? existing.apiKeyHint() : ModelSecretCipher.hint(key),
                timeout(request.requestTimeoutMs()), retries(request.maxRetries()),
                concurrency(request.maxConcurrency()), qps(request.qps()), enabled);
        store.updateProvider(updated);
        if (!name.equals(existing.name())) {
            store.renameEmbeddingProviderReferences(existing.name(), name);
        }
        changed();
        return view(updated, store.load());
    }

    @Transactional
    public void deleteProvider(long id) {
        ModelCatalog catalog = store.load();
        ModelProvider existing = catalog.provider(id).orElseThrow(() -> notFound("provider 不存在：" + id));
        List<String> refs = providerReferences(existing, catalog);
        if (!refs.isEmpty()) {
            throw conflict("provider 仍被引用，不能删除：" + String.join("、", refs));
        }
        for (ModelDefinition model : catalog.models()) {
            if (model.providerId() == id) store.deleteModel(model.id());
        }
        store.deleteProvider(id);
        changed();
    }

    // ---------- 模型 ----------

    @Transactional
    public List<ModelView> createModels(long providerId, List<ModelRequest> requests) {
        ModelCatalog catalog = store.load();
        catalog.provider(providerId).orElseThrow(() -> notFound("provider 不存在：" + providerId));
        if (requests == null || requests.isEmpty()) {
            throw invalid("至少提供一个模型");
        }
        Set<String> names = new LinkedHashSet<>();
        catalog.models().stream().filter(m -> m.providerId() == providerId).forEach(m -> names.add(m.modelName()));
        List<Long> ids = new ArrayList<>();
        for (ModelRequest request : requests) {
            ModelDefinition model = toModel(null, providerId, request);
            if (!names.add(model.modelName())) {
                throw conflict("该 provider 下已存在模型：" + model.modelName());
            }
            ids.add(store.insertModel(model));
        }
        changed();
        ModelCatalog after = store.load();
        return ids.stream().map(i -> view(after.model(i).orElseThrow(), after)).toList();
    }

    @Transactional
    public ModelView updateModel(long id, ModelRequest request) {
        ModelCatalog catalog = store.load();
        ModelDefinition existing = catalog.model(id).orElseThrow(() -> notFound("模型不存在：" + id));
        ModelDefinition updated = toModel(id, existing.providerId(), request);
        if (!updated.modelName().equals(existing.modelName()) && catalog.models().stream()
                .anyMatch(m -> m.providerId() == existing.providerId() && m.modelName().equals(updated.modelName()))) {
            throw conflict("该 provider 下已存在模型：" + updated.modelName());
        }
        ModelProvider provider = catalog.provider(existing.providerId()).orElseThrow();
        List<Integer> versions = existing.has(ModelTag.EMBEDDING)
                ? store.indexVersionsReferencing(provider.name(), existing.modelName()) : List.of();
        if (!versions.isEmpty() && (!updated.enabled() || !updated.has(ModelTag.EMBEDDING)
                || !updated.modelName().equals(existing.modelName())
                || !java.util.Objects.equals(updated.dimensions(), existing.dimensions()))) {
            throw conflict("embedding 模型仍被" + describeVersions(versions)
                    + "引用，不能停用或修改模型名、维度、embedding 标签");
        }
        for (ModelAssignment assignment : catalog.assignments().values()) {
            if (assignment.modelId() == null || assignment.modelId() != id) continue;
            String problem = assignmentProblem(assignment.purpose(), updated, provider, assignment.thinking());
            if (problem != null) {
                throw conflict(assignment.purpose().name() + " 正在使用该模型：" + problem + "；请先为该用途更换模型");
            }
        }
        store.updateModel(updated);
        changed();
        return view(updated, store.load());
    }

    @Transactional
    public void deleteModel(long id) {
        ModelCatalog catalog = store.load();
        ModelDefinition existing = catalog.model(id).orElseThrow(() -> notFound("模型不存在：" + id));
        List<String> refs = modelReferences(existing, catalog);
        if (!refs.isEmpty()) {
            throw conflict("模型仍被引用，不能删除：" + String.join("、", refs));
        }
        store.deleteModel(id);
        changed();
    }

    // ---------- 用途分配 ----------

    public List<AssignmentView> assignments() {
        ModelCatalog catalog = store.load();
        List<AssignmentView> views = new ArrayList<>();
        for (ModelPurpose purpose : ModelPurpose.values()) {
            ModelAssignment a = catalog.assignments().get(purpose);
            String effective = registry.find(purpose)
                    .map(r -> r.providerName() + " / " + r.modelName()).orElse(null);
            views.add(new AssignmentView(purpose.name(), purpose.label(), purpose.description(),
                    purpose.requiredTag().dbValue(), purpose.requiresTools() && nativeTools,
                    purpose.defaultTemperature(), a.modelId(), a.temperature(), a.maxTokens(), a.thinking(),
                    effective, a.extra()));
        }
        return views;
    }

    @Transactional
    public long saveAssignments(Collection<AssignmentRequest> requests) {
        ModelCatalog catalog = store.load();
        List<ModelAssignment> validated = new ArrayList<>();
        Set<ModelPurpose> seen = EnumSet.noneOf(ModelPurpose.class);
        for (AssignmentRequest request : requests) {
            ModelPurpose purpose;
            try {
                purpose = ModelPurpose.valueOf(request.purpose());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw invalid("未知的用途：" + request.purpose());
            }
            if (!seen.add(purpose)) {
                throw invalid("用途重复：" + purpose.name());
            }
            if (request.temperature() != null && (request.temperature() < 0 || request.temperature() > 2)) {
                throw invalid(purpose.name() + " 的 temperature 必须在 0 到 2 之间");
            }
            if (request.maxTokens() != null && request.maxTokens() < 1) {
                throw invalid(purpose.name() + " 的 maxTokens 必须为正整数");
            }
            if (request.modelId() != null) {
                ModelDefinition model = catalog.model(request.modelId())
                        .orElseThrow(() -> invalid(purpose.name() + " 选择的模型不存在：" + request.modelId()));
                ModelProvider provider = catalog.provider(model.providerId()).orElseThrow();
                String problem = assignmentProblem(purpose, model, provider, request.thinking());
                if (problem != null) {
                    throw invalid(purpose.name() + "（" + purpose.label() + "）" + problem);
                }
            }
            validated.add(new ModelAssignment(purpose, request.modelId(), request.temperature(),
                    request.maxTokens(), request.thinking(), extra(purpose.name() + " 的", request.extra())));
        }
        store.saveAssignments(validated);
        return changed();
    }

    // ---------- 测试、发现与状态 ----------

    public TestResult testProvider(ConnectionTest request) {
        ModelProvider saved = request.providerId() == null ? null : store.load().provider(request.providerId())
                .orElseThrow(() -> notFound("provider 不存在：" + request.providerId()));
        String baseUrl = blank(request.baseUrl()) ? (saved == null ? null : saved.baseUrl()) : request.baseUrl();
        String key = blank(request.apiKey()) ? (saved == null ? null : cipher.decrypt(saved.apiKeyCipher()))
                : request.apiKey().trim();
        if (blank(key)) {
            throw invalid("缺少 API key");
        }
        String url = requireBaseUrl(baseUrl);
        Duration timeout = Duration.ofMillis(Math.min(timeout(request.requestTimeoutMs()), (int) TEST_TIMEOUT.toMillis()));
        long start = System.nanoTime();
        try {
            int count = factory.listModels(url, key, timeout).size();
            return new TestResult(true, "连接成功，provider 返回 " + count + " 个模型", elapsed(start));
        } catch (Exception e) {
            return new TestResult(false, "连接失败：" + safeMessage(e), elapsed(start));
        }
    }

    public List<String> discover(long providerId) {
        ModelCatalog catalog = store.load();
        ModelProvider provider = catalog.provider(providerId).orElseThrow(() -> notFound("provider 不存在：" + providerId));
        Set<String> registered = new LinkedHashSet<>();
        catalog.models().stream().filter(m -> m.providerId() == providerId).forEach(m -> registered.add(m.modelName()));
        try {
            return factory.listModels(provider.baseUrl(), cipher.decrypt(provider.apiKeyCipher()), TEST_TIMEOUT)
                    .stream().filter(name -> !registered.contains(name)).sorted().toList();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "拉取模型列表失败：" + safeMessage(e));
        }
    }

    public TestResult testModel(long id) {
        ModelCatalog catalog = store.load();
        ModelDefinition model = catalog.model(id).orElseThrow(() -> notFound("模型不存在：" + id));
        ModelProvider provider = catalog.provider(model.providerId()).orElseThrow();
        String key = cipher.decrypt(provider.apiKeyCipher());
        Duration timeout = Duration.ofMillis(Math.min(provider.requestTimeoutMs(), (int) TEST_TIMEOUT.toMillis()));
        long start = System.nanoTime();
        try {
            if (model.has(ModelTag.CHAT)) {
                ChatModel chat = factory.chat(new ClientSpec(provider.baseUrl(), key, timeout, model.modelName(),
                        0.0, 16, null, ModelExtra.passthrough(model.extra())));
                chat.chat("ping");
                return new TestResult(true, "对话调用成功", elapsed(start));
            }
            if (model.has(ModelTag.EMBEDDING)) {
                int dims = model.dimensions() == null ? 0 : model.dimensions();
                Integer batch = ModelExtra.intValue(model.extra(), ModelExtra.BATCH_SIZE);
                List<float[]> vectors = factory.embedding(provider.baseUrl(), key, timeout, model.modelName(),
                        dims, 0, batch == null ? ModelExtra.DEFAULT_BATCH_SIZE : batch,
                        ModelExtra.passthrough(model.extra())).embed(List.of("ping"));
                int actual = vectors.isEmpty() ? 0 : vectors.get(0).length;
                return actual == dims
                        ? new TestResult(true, "向量调用成功，维度 " + actual, elapsed(start))
                        : new TestResult(false, "维度不符：登记 " + dims + "，实际 " + actual, elapsed(start));
            }
            factory.listModels(provider.baseUrl(), key, timeout);
            return new TestResult(true, "provider 连接成功（vision 模型只做连通性测试）", elapsed(start));
        } catch (Exception e) {
            return new TestResult(false, "调用失败：" + safeMessage(e), elapsed(start));
        }
    }

    public StatusView status() {
        ModelRegistry.Status status = registry.status();
        return new StatusView(status.version(), status.loadedAt(), status.lastError(), store.currentVersion());
    }

    // ---------- 内部 ----------

    private long changed() {
        long version = store.bumpVersion();
        events.publishEvent(new ModelConfigChangedEvent(version));
        return version;
    }

    /** 模型能否承担该用途；可以时返回 null。 */
    private String assignmentProblem(ModelPurpose purpose, ModelDefinition model, ModelProvider provider,
                                     Boolean thinking) {
        if (!model.enabled() || !provider.enabled()) {
            return "所选模型或其 provider 已停用";
        }
        if (!model.has(purpose.requiredTag())) {
            return "需要带 " + purpose.requiredTag().dbValue() + " 标签的模型";
        }
        if (purpose.requiresTools() && nativeTools && !model.supportsTools()) {
            return "需要支持原生 tool calling 的模型（当前 kwiki.agentic.tool-mode=native）";
        }
        if (Boolean.TRUE.equals(thinking) && !model.supportsThinking()) {
            return "开启 thinking 需要模型支持 thinking";
        }
        return null;
    }

    private List<String> providerReferences(ModelProvider provider, ModelCatalog catalog) {
        List<String> refs = new ArrayList<>();
        for (ModelDefinition model : catalog.models()) {
            if (model.providerId() == provider.id()) refs.addAll(modelReferences(model, catalog));
        }
        return refs;
    }

    private List<String> modelReferences(ModelDefinition model, ModelCatalog catalog) {
        List<String> refs = new ArrayList<>();
        for (ModelAssignment a : catalog.assignments().values()) {
            if (a.modelId() != null && a.modelId().equals(model.id())) {
                refs.add("用途 " + a.purpose().name());
            }
        }
        if (model.has(ModelTag.EMBEDDING)) {
            String providerName = catalog.provider(model.providerId()).map(ModelProvider::name).orElse("");
            List<Integer> versions = store.indexVersionsReferencing(providerName, model.modelName());
            if (!versions.isEmpty()) refs.add(describeVersions(versions));
        }
        return refs;
    }

    private static String describeVersions(List<Integer> versions) {
        return "索引版本 " + String.join("、", versions.stream().map(String::valueOf).toList());
    }

    private ModelDefinition toModel(Long id, long providerId, ModelRequest request) {
        String name = request.modelName() == null ? "" : request.modelName().trim();
        if (name.isEmpty() || name.length() > 200) {
            throw invalid("模型名不能为空且不超过 200 个字符");
        }
        Set<ModelTag> tags = EnumSet.noneOf(ModelTag.class);
        for (String tag : request.tags() == null ? Set.<String>of() : request.tags()) {
            try {
                tags.add(ModelTag.fromDb(tag));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw invalid("未知的模型标签：" + tag);
            }
        }
        if (tags.isEmpty()) {
            throw invalid("模型至少需要一个能力标签");
        }
        Integer dims = request.dimensions();
        if (tags.contains(ModelTag.EMBEDDING)) {
            if (dims == null || dims < 64 || dims > 2048) {
                throw invalid("embedding 模型必须登记 64–2048 之间的维度");
            }
        } else {
            dims = null;
        }
        if (request.maxOutputTokens() != null && request.maxOutputTokens() < 1) {
            throw invalid("输出上限必须为正整数");
        }
        String display = blank(request.displayName()) ? null : request.displayName().trim();
        return new ModelDefinition(id, providerId, name, display, tags, dims, request.maxOutputTokens(),
                !Boolean.FALSE.equals(request.supportsTools()), Boolean.TRUE.equals(request.supportsThinking()),
                !Boolean.FALSE.equals(request.enabled()), extra("模型 " + name + " 的", request.extra()));
    }

    private ProviderView view(ModelProvider p, ModelCatalog catalog) {
        List<ModelView> models = catalog.models().stream().filter(m -> m.providerId() == p.id())
                .map(m -> view(m, catalog)).toList();
        return new ProviderView(p.id(), p.name(), p.protocol(), p.baseUrl(), p.apiKeyHint(), p.requestTimeoutMs(),
                p.maxRetries(), p.maxConcurrency(), p.qps(), p.enabled(), models);
    }

    private ModelView view(ModelDefinition m, ModelCatalog catalog) {
        List<String> tags = m.tags().stream().sorted().map(ModelTag::dbValue).toList();
        return new ModelView(m.id(), m.providerId(), m.modelName(), m.displayName(), tags, m.dimensions(),
                m.maxOutputTokens(), m.supportsTools(), m.supportsThinking(), m.enabled(),
                modelReferences(m, catalog), m.extra());
    }

    private static Map<String, Object> extra(String owner, Map<String, Object> raw) {
        try {
            return ModelExtra.normalize(raw);
        } catch (IllegalArgumentException e) {
            throw invalid(owner + e.getMessage());
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) {
            throw invalid("provider 名称不能为空且不超过 100 个字符");
        }
        return trimmed;
    }

    private static String requireBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).matches("https?://\\S+") || trimmed.length() > 500) {
            throw invalid("baseUrl 必须是 http(s) 地址");
        }
        return trimmed;
    }

    private static int timeout(Integer value) {
        if (value == null) return DEFAULT_TIMEOUT_MS;
        if (value < 1000 || value > 600_000) throw invalid("超时必须在 1000–600000 毫秒之间");
        return value;
    }

    private static int retries(Integer value) {
        if (value == null) return DEFAULT_RETRIES;
        if (value < 0 || value > 10) throw invalid("重试次数必须在 0–10 之间");
        return value;
    }

    private static Integer concurrency(Integer value) {
        if (value != null && (value < 1 || value > 256)) throw invalid("并发上限必须在 1–256 之间");
        return value;
    }

    private static Double qps(Double value) {
        if (value != null && value <= 0) throw invalid("QPS 必须大于 0");
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static long elapsed(long start) {
        return Duration.ofNanos(System.nanoTime() - start).toMillis();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        message = SecretRedaction.redact(message);
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(400, message);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }

    private static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }
}
