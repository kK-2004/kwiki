# 模型 Provider 与用途分配：实现计划

> 按任务顺序执行，每个任务自带测试并单独提交。勾选框用来跟踪进度。

**目标**：provider、模型和用途分配落库，后台可以维护，修改后热更新；各个 LLM 用途可以独立选择模型；embedding 模型从目录中选择并绑定到索引版本。

**架构**：新增 `infrastructure/ai/registry` 包，`ModelRegistry` 在内存中持有不可变快照。适配器在每次调用时按 `ModelPurpose` 取客户端。写操作递增 `model_config_version`，事务提交后本机 reload，并通过 Redis 广播；同时用轮询兜底。

**技术栈**：Java 21、Spring Boot 3.5、JdbcTemplate、Flyway（MySQL）、LangChain4j 1.20 OpenAI-compatible、Vue 3 + Element Plus（admin-frontend）、JUnit 5 + Mockito、Vitest。

## 全局约束

- 注释一律使用简体中文（AGENT.md §1）；已有 Flyway 迁移文件只读，新增版本为 `V40__model_provider_catalog.sql`。
- `application.yml` 不新增配置项，新配置通过 `@Value` 提供默认值（例如 `kwiki.model-config.poll-interval:30s`）；主密钥 `kwiki.model-config.master-key` 放在 Nacos 或环境变量中。
- 测试不依赖 Docker 或 Testcontainers（`NoDockerMiddlewareAuditTest`）；持久化层用 JdbcTemplate 实现，上下文测试中通过 mock 隔离。
- 任何接口响应、日志、异常信息都不得包含 API key 明文；日志统一经过 `SecretRedaction` 脱敏。
- 提交信息使用中文，不加 Co-Authored-By。

---

### Task 1: 迁移与目录持久化

**Files**
- Create: `src/main/resources/db/migration/V40__model_provider_catalog.sql`
- Create: `src/main/java/com/kwiki/infrastructure/ai/registry/{ModelTag,ModelPurpose,ModelProvider,ModelDefinition,ModelAssignment,ModelCatalog,ModelConfigStore,JdbcModelConfigStore}.java`
- Test: `src/test/java/com/kwiki/infrastructure/ai/registry/ModelPurposeTest.java`

**Interfaces（Produces）**
- `enum ModelTag { CHAT, VISION, EMBEDDING; String dbValue(); static ModelTag fromDb(String) }`
- `enum ModelPurpose`：字段 `label`、`description`、`requiredTag`、`defaultTemperature`、`requiresTools`；实例为 AGENT_PLANNER、RETRIEVAL_PLANNER、ROUTER、REWRITE、DIRECT_ANSWER、QUALITY_ANALYZER、ANSWER、SELECTION_QUESTION、GRAPH_EXTRACTION、GRAPH_SUMMARY、VISION_SUMMARY
- `record ModelProvider(Long id, String name, String protocol, String baseUrl, String apiKeyCipher, String apiKeyHint, int requestTimeoutMs, int maxRetries, Integer maxConcurrency, Double qps, boolean enabled)`
- `record ModelDefinition(Long id, long providerId, String modelName, String displayName, Set<ModelTag> tags, Integer dimensions, Integer maxOutputTokens, boolean supportsTools, boolean supportsThinking, boolean enabled)`
- `record ModelAssignment(ModelPurpose purpose, Long modelId, Double temperature, Integer maxTokens, Boolean thinking)`
- `record ModelCatalog(long version, List<ModelProvider> providers, List<ModelDefinition> models, Map<ModelPurpose, ModelAssignment> assignments)`
- `interface ModelConfigStore`：`ModelCatalog load()`、`long currentVersion()`、`long bumpVersion()`、`long insertProvider(ModelProvider)`、`void updateProvider(ModelProvider)`、`void deleteProvider(long)`、`long insertModel(ModelDefinition)`、`void updateModel(ModelDefinition)`、`void deleteModel(long)`、`void saveAssignments(Collection<ModelAssignment>)`、`boolean isEmpty()`、`int renameEmbeddingProviderReferences(String from, String to)`、`List<String> indexVersionsReferencing(String provider, String model)`、`boolean tryImportLock()`、`void releaseImportLock()`

- [ ] 编写 V40：五张表、预置 11 行 `model_assignment`、`model_config_version` 的唯一一行（version=0），运行 `scripts/validate-migrations.sh`
- [ ] 测试 `ModelPurposeTest`：每个用途的标签和默认参数与现有硬编码一致（planner/router/rewrite/direct/quality/graph 为 0.0，answer/selection 为 0.2；VISION_SUMMARY 要求 `VISION`；两个 planner 要求 tools）
- [ ] 实现枚举、record 和 `JdbcModelConfigStore`（标签用 `model_definition_tag` 表；导入锁用 `GET_LOCK`/`RELEASE_LOCK`）
- [ ] `./mvnw -q -o test -Dtest=ModelPurposeTest` 通过后提交

### Task 2: API key 加密

**Files**：Create `registry/ModelSecretCipher.java`；Test `ModelSecretCipherTest.java`

**Produces**：`ModelSecretCipher(String base64MasterKey)`、`String encrypt(String)`、`String decrypt(String)`、`static String hint(String)`、`boolean configured()`

- [ ] 测试：加密后能解密回原文；同一明文两次加密结果不同；篡改密文后抛出 `IllegalStateException`，且异常信息不含明文；主密钥长度不是 32 字节时报错；hint 的规则：`sk-abcdef123456` → `sk-****3456`，短 key → `****56`
- [ ] 实现 AES-256-GCM（`v1:` + Base64(nonce‖ct‖tag)），然后提交

### Task 3: 客户端工厂、快照与 ModelRegistry

**Files**：Create `registry/{ModelClientFactory,ModelSnapshot,ResolvedModel,ModelRegistry,DefaultModelRegistry,ModelNotConfiguredException,EmbeddingBinding}.java`；Test `DefaultModelRegistryTest.java`

**Produces**
- `interface ModelRegistry { ChatModel chat(ModelPurpose); StreamingChatModel streaming(ModelPurpose); ResolvedModel resolve(ModelPurpose); Optional<ResolvedModel> find(ModelPurpose); Optional<EmbeddingBinding> embedding(String provider, String model); List<EmbeddingBinding> embeddings(); Status status(); static ModelRegistry fixed(ChatModel, StreamingChatModel) }`
- `record ResolvedModel(ModelPurpose purpose, long providerId, String providerName, String baseUrl, String modelName, double temperature, Integer maxTokens, Boolean thinking, int maxRetries, Duration timeout, RateLimiter limiter)`；`limiter` 仅在 provider 配置了并发上限时非空
- `record EmbeddingBinding(String providerName, String modelName, int dimensions, ChunkEmbeddingPort port)`
- `ModelClientFactory`：`ChatModel chat(ModelProvider, String apiKey, ModelDefinition, ResolvedParams)`、`StreamingChatModel streaming(...)`、`ChunkEmbeddingPort embedding(ModelProvider, String apiKey, ModelDefinition)`、`ImageSummaryPort vision(ModelProvider, String apiKey, ModelDefinition)`、`Integer probeOutputLimit(ModelProvider, String apiKey, String modelName)`
- `DefaultModelRegistry.reload()` 返回 `boolean`；`Status(long version, Instant loadedAt, String lastError)`

- [ ] 测试（fake factory 计数）：指纹相同则复用客户端；修改 temperature 只重建对应的客户端；用途未分配、模型停用、provider 停用时都抛出 `ModelNotConfiguredException`；解密失败时 reload 返回 false 并保留旧快照，`status().lastError` 不含 key；从未成功加载过时，`chat()` 抛出未配置异常
- [ ] 实现，然后提交

### Task 4: 刷新机制

**Files**：Create `registry/{ModelConfigChangedEvent,ModelConfigRefresher,RedisModelConfigBroadcaster}.java`；Test `ModelConfigRefresherTest.java`

- [ ] 测试：`AFTER_COMMIT` 事件触发本机 reload 并广播；收到的版本号不大于本地版本时忽略；轮询发现数据库版本更新时触发 reload
- [ ] 实现：`@TransactionalEventListener`；`@Scheduled(fixedDelayString="${kwiki.model-config.poll-interval:30s}")`；Redis 广播器在存在 `RedisConnectionFactory` 时注册（频道 `kwiki:model-config`），然后提交

### Task 5: 用途调用方接入 registry

**Files**：修改 `infrastructure/ai` 下的 10 个适配器、`KwikiAiClientConfiguration`（删除 chat/streaming/embedding WebClient 三个 Bean 和 `AnswerModelCapabilities`）、`QwenEmbeddingClient`（去掉 `@Component`）、`graph/model/GraphModelClients`、`indexing/multimodal/{MultimodalConfiguration,ImageResourceService,ImageSummaryPort}`、`indexing/config/{MultimodalSwitchReadiness,MultimodalIndexingProperties}`、`indexing/pipeline/VersionedIndexingPipelineRegistry`、`indexing/search/ElasticsearchIndexBootstrap`、`ExternalServicesProperties`（answerLlm、qwenEmbedding 改为可选）；新增 `registry/{SelectedIndexEmbeddingPort,RegistryImageSummaryPort}.java`；同步更新受影响的测试

- [ ] 适配器统一写法：`registry.chat(ModelPurpose.X).chat(...)`，测试中改用 `ModelRegistry.fixed(model, streaming)`
- [ ] `GraphModelClients.extraction()/summary()` 改为每次从 registry 解析得到 `ModelEndpoint`（limiter 为空时使用该用途的默认限流器）；`missing()` 返回「GRAPH_EXTRACTION 未分配模型」
- [ ] `VersionedIndexingPipelineRegistry` 改为通过 `registry.embedding(provider, model)` 取客户端；隐式 manifest 由 `registry.embeddings()` 生成
- [ ] `SelectedIndexEmbeddingPort` 作为唯一的 `ChunkEmbeddingPort` Bean；`RegistryImageSummaryPort` 作为 `ImageSummaryPort` Bean，并新增 `modelName()`
- [ ] `./mvnw -q -o test` 全部通过后提交

### Task 6: 管理服务与接口

**Files**：Create `registry/ModelConfigAdminService.java`、`wiki/api/ModelConfigAdminController.java`；修改 `indexing/version/SearchIndexAdminService`（新建/编辑版本时校验 embedding 并改写维度）；Test `ModelConfigAdminServiceTest.java`、`SearchIndexAdminServiceEmbeddingTest`（并入现有测试）

- [ ] 测试：标签不匹配返回 400；planner 在 native 模式下选了不支持 tools 的模型返回 400；删除或停用被引用的模型返回 409 并列出引用方；修改 provider 时 key 为空则保留原密文；provider 改名时联动更新索引版本；embedding 模型被索引版本引用时修改维度返回 409；每次写操作都递增版本号并发布事件；响应中只有 `apiKeyHint`
- [ ] 实现接口：`/api/v1/admin/models/{providers,providers/test,providers/{id}/discover,providers/{id}/models,models/{id},models/{id}/test,assignments,status}`，然后提交

### Task 7: 首次导入

**Files**：Create `registry/ModelConfigBootstrapImporter.java`；Test `ModelConfigBootstrapImporterTest.java`

- [ ] 测试：目录为空时，answer-llm 导入为 provider 加 chat 模型，9 个问答用途全部分配给它；graph 配置与 answer-llm 的 baseUrl 和 key 相同时复用同一个 provider；`kwiki.qwen-embedding` 导入为 provider `default`；vision 未配置时不分配；目录非空或没拿到锁时跳过；缺少主密钥但需要导入时报出明确错误
- [ ] 实现：`SmartInitializingSingleton`，在 registry 首次 reload 之前执行，然后提交

### Task 8: 后台页面

**Files**：修改 `admin-frontend/src/{api.ts,router.ts,components/AdminLayout.vue,views/IndexManagementView.vue}`；新建 `admin-frontend/src/views/ModelConfigView.vue`、`admin-frontend/src/modelConfig.ts`；Test `admin-frontend/tests/model-config.spec.ts`

- [ ] 测试（`modelConfig.ts` 纯函数和视图）：按标签过滤候选模型；参数输入框为空时提交 null；provider 表单 key 为空时请求体不带 `apiKey`；保存分配后显示版本号
- [ ] 实现两个 tab、拉取模型对话框、测试按钮；索引版本表单的 embedding 改为下拉框
- [ ] `pnpm typecheck && pnpm test && pnpm build` 通过后提交

### Task 9: 收尾验证

- [ ] `./mvnw -q -o test`、`scripts/validate-migrations.sh`、`openspec validate manage-model-providers-in-admin`
- [ ] 对照五份 spec 逐条核对，在 design.md 的 Migration Plan 中补充运维步骤，然后提交
