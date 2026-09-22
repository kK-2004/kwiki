package com.kwiki.indexing.job;

import com.kwiki.infrastructure.observability.SecretRedaction;
import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.parse.AttachmentIndexEligibility;
import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.UnsupportedInputException;
import com.kwiki.indexing.pipeline.ChunkIndexPort;
import com.kwiki.indexing.pipeline.IndexedVersion;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry;
import com.kwiki.indexing.pipeline.VersionedIndexingPipelineRegistry.ResolvedPipeline;
import com.kwiki.indexing.version.EditableIndexConfig;
import com.kwiki.indexing.version.SearchIndexVersion;
import com.kwiki.indexing.version.SearchIndexVersionRepository;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.attach.MediaContentSniffer;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.WikiPage;
import com.kwiki.wiki.domain.WikiPageRevision;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.WikiPageRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 作用于已租用目标行的至少一次（at-least-once）工作线程。每个物理
 * 目标按其版本"成功构建的配置修订"经
 * {@link VersionedIndexingPipelineRegistry} 解析流水线：
 * 解析 → 父分块 → 子分块 → 仅对子分块做向量嵌入 → 校验维度/模型/
 * parser/chunker 身份 → 幂等写该目标行记录的显式物理索引。
 * 同一资源事件（同一 job）的多个目标只在 built 配置快照等价时共享
 * 中间结果；配置不同（例如维度不同）时分别生成向量并单独计数。
 * 确定性的 key 使重放安全；失败按目标独立使用有界指数退避；
 * 不支持的输入直接终止性失败；错误在存储前已被净化。
 */
@Component
public class IndexingWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexingWorker.class);

    public static final String PARSER_VERSION = "kwiki-parse-1";
    public static final String CHUNKER_VERSION = "kwiki-chunk-1";
    /** 多模态解析代：PDF 内嵌图片 + 发布后 Markdown 图片进入受保护块。 */
    public static final String PARSER_VERSION_MULTIMODAL = "kwiki-parse-2";

    private final IndexingJobTargetClaimer targetClaimer;
    private final IndexingJobTargetStore targets;
    private final VersionedIndexingPipelineRegistry pipelines;
    private final ObjectProvider<SearchIndexVersionRepository> versionRegistry;
    private final ChunkIndexPort index;
    private final org.springframework.beans.factory.ObjectProvider<com.kwiki.indexing.search.ChunkIndexRepository> chunkIndexRepository;
    private final WikiPageRepository pages;
    private final com.kwiki.wiki.persistence.KnowledgeBaseRepository knowledgeBases;
    private final WikiPageRevisionRepository revisions;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final org.springframework.beans.factory.ObjectProvider<
            com.kwiki.indexing.multimodal.MultimodalIndexingService> multimodal;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final long maxBackoffSeconds;
    private final MeterRegistry metrics;
    private volatile boolean cancelled;

    public IndexingWorker(IndexingJobTargetClaimer targetClaimer,
                          IndexingJobTargetStore targets,
                          VersionedIndexingPipelineRegistry pipelines,
                          ObjectProvider<SearchIndexVersionRepository> versionRegistry,
                          ChunkIndexPort index,
                          org.springframework.beans.factory.ObjectProvider<com.kwiki.indexing.search.ChunkIndexRepository> chunkIndexRepository,
                          WikiPageRepository pages,
                          com.kwiki.wiki.persistence.KnowledgeBaseRepository knowledgeBases,
                          WikiPageRevisionRepository revisions,
                          AttachmentRepository attachments,
                          AttachmentStorage storage,
                          MeterRegistry metrics,
                          @Value("${kwiki.indexing.max-attempts:8}") int maxAttempts,
                          @Value("${kwiki.indexing.base-backoff-seconds:30}") long baseBackoffSeconds,
                          @Value("${kwiki.indexing.max-backoff-seconds:3600}") long maxBackoffSeconds) {
        this(targetClaimer, targets, pipelines, versionRegistry, index, chunkIndexRepository,
                pages, knowledgeBases, revisions, attachments, storage, null, metrics,
                maxAttempts, baseBackoffSeconds, maxBackoffSeconds);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IndexingWorker(IndexingJobTargetClaimer targetClaimer,
                          IndexingJobTargetStore targets,
                          VersionedIndexingPipelineRegistry pipelines,
                          ObjectProvider<SearchIndexVersionRepository> versionRegistry,
                          ChunkIndexPort index,
                          org.springframework.beans.factory.ObjectProvider<com.kwiki.indexing.search.ChunkIndexRepository> chunkIndexRepository,
                          WikiPageRepository pages,
                          com.kwiki.wiki.persistence.KnowledgeBaseRepository knowledgeBases,
                          WikiPageRevisionRepository revisions,
                          AttachmentRepository attachments,
                          AttachmentStorage storage,
                          org.springframework.beans.factory.ObjectProvider<
                                  com.kwiki.indexing.multimodal.MultimodalIndexingService> multimodal,
                          MeterRegistry metrics,
                          @Value("${kwiki.indexing.max-attempts:8}") int maxAttempts,
                          @Value("${kwiki.indexing.base-backoff-seconds:30}") long baseBackoffSeconds,
                          @Value("${kwiki.indexing.max-backoff-seconds:3600}") long maxBackoffSeconds) {
        this.targetClaimer = targetClaimer;
        this.targets = targets;
        this.pipelines = pipelines;
        this.versionRegistry = versionRegistry;
        this.index = index;
        this.chunkIndexRepository = chunkIndexRepository;
        this.pages = pages;
        this.knowledgeBases = knowledgeBases;
        this.revisions = revisions;
        this.attachments = attachments;
        this.storage = storage;
        this.multimodal = multimodal;
        this.maxAttempts = maxAttempts;
        this.baseBackoffSeconds = baseBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.metrics = metrics;
    }

    public void cancel() {
        this.cancelled = true;
    }

    /** 认领并处理一个批次的目标行；返回已处理的目标数。 */
    public int runBatch(String owner, int batchSize) {
        List<Map<String, Object>> claimed = targetClaimer.claim(owner, batchSize);
        // 同一资源事件的目标共享中间结果；缓存只在本批次内存在，
        // 绝不成为跨任务的事实来源。
        Map<Long, JobIntermediates> intermediates = new LinkedHashMap<>();
        int processed = 0;
        for (Map<String, Object> target : claimed) {
            if (cancelled) {
                break;
            }
            long jobId = ((Number) target.get("job_id")).longValue();
            process(target, intermediates.computeIfAbsent(jobId, key -> new JobIntermediates()));
            processed++;
        }
        return processed;
    }

    /** 处理一个已租出的目标行：写入其记录的物理索引，绝不经过读别名。 */
    void process(Map<String, Object> target, JobIntermediates intermediates) {
        long targetId = ((Number) target.get("target_id")).longValue();
        long start = System.nanoTime();
        try {
            ResolvedPipeline pipeline = resolvePipeline(target);
            log.info("Indexing target started: targetId={}, jobId={}, targetVersion={},"
                            + " physicalIndex={}, manifest={}, type={}, resourceType={}, resourceId={}",
                    targetId, target.get("job_id"), target.get("target_version"),
                    target.get("physical_name"), pipeline.manifestId(), target.get("job_type"),
                    target.get("resource_type"), target.get("resource_id"));
            execute(target, pipeline, intermediates);
            targets.completeTarget(targetId);
            metrics.counter("kwiki_indexing_jobs_total", "outcome", "completed").increment();
            log.info("Indexing target completed: targetId={}", targetId);
        } catch (UnsupportedInputException e) {
            targets.failTarget(targetId, e.getClass().getSimpleName(),
                    sanitize(e.getMessage()), 0, baseBackoffSeconds, maxBackoffSeconds);
            metrics.counter("kwiki_indexing_jobs_total", "outcome", "unsupported").increment();
            log.warn("Indexing target unsupported: targetId={}, error={}", targetId,
                    sanitize(e.getMessage()));
        } catch (com.kwiki.indexing.multimodal.ExternalImageFetchException
                | com.kwiki.indexing.multimodal.VisionSummaryException e) {
            // 多模态外链抓取/视觉摘要：瞬时进入退避重试，永久立即死信；
            // 消息已在上游脱敏（无 URL、无凭据）。
            boolean transientFailure =
                    (e instanceof com.kwiki.indexing.multimodal.ExternalImageFetchException fetch
                            && fetch.getCategory()
                                    == com.kwiki.indexing.multimodal.ExternalImageFetchException
                                            .Category.TRANSIENT)
                    || (e instanceof com.kwiki.indexing.multimodal.VisionSummaryException vision
                            && vision.getCategory()
                                    == com.kwiki.indexing.multimodal.VisionSummaryException
                                            .Category.TRANSIENT);
            targets.failTarget(targetId, e.getClass().getSimpleName(),
                    sanitize(e.getMessage()), transientFailure ? maxAttempts : 0,
                    baseBackoffSeconds, maxBackoffSeconds);
            metrics.counter("kwiki_indexing_jobs_total", "outcome",
                    transientFailure ? "transient-multimodal-failure"
                            : "permanent-multimodal-failure").increment();
            log.warn("Indexing target multimodal failure: targetId={}, transient={}, error={}",
                    targetId, transientFailure, sanitize(e.getMessage()));
        } catch (AttachmentStorageException e) {
            // 永久性存储故障（请求被拒、文件 id 缺失、内容过大）
            // 立即进入死信；临时性故障则重新进入退避。
            int attempts = e.getCategory() == AttachmentStorageException.Category.PERMANENT
                    ? 0 : maxAttempts;
            targets.failTarget(targetId, e.getClass().getSimpleName(),
                    sanitize(e.getMessage()), attempts, baseBackoffSeconds, maxBackoffSeconds);
            metrics.counter("kwiki_indexing_jobs_total", "outcome",
                    attempts == 0 ? "permanent-storage-failure" : "transient-storage-failure").increment();
            log.warn("Indexing target storage failure: targetId={}, error={}", targetId,
                    sanitize(e.getMessage()));
        } catch (Exception e) {
            targets.failTarget(targetId, e.getClass().getSimpleName(), sanitize(e.getMessage()),
                    maxAttempts, baseBackoffSeconds, maxBackoffSeconds);
            metrics.counter("kwiki_indexing_jobs_total", "outcome", "failed").increment();
            log.warn("Indexing target failed: targetId={}, errorClass={}, error={}", targetId,
                    e.getClass().getSimpleName(), sanitize(e.getMessage()));
        } finally {
            Timer.builder("kwiki_indexing_job_duration")
                    .description("indexing job processing time")
                    .register(metrics)
                    .record(java.time.Duration.ofNanos(System.nanoTime() - start));
        }
    }

    /** 目标版本的流水线解析：built 配置不受支持时该目标失败关闭。 */
    private ResolvedPipeline resolvePipeline(Map<String, Object> target) {
        int versionNumber = ((Number) target.get("target_version")).intValue();
        SearchIndexVersionRepository repository = versionRegistry.getIfAvailable();
        SearchIndexVersion version = repository == null ? null
                : repository.findByVersionNumber(versionNumber).orElse(null);
        if (version == null || version.getDeletedAt() != null) {
            throw new IllegalStateException(
                    "index target references unknown or deleted version " + versionNumber);
        }
        EditableIndexConfig config = version.editableConfig();
        return pipelines.resolve(config).orElseThrow(() -> new IllegalStateException(
                "pipeline unsupported for version " + versionNumber + ": "
                        + pipelines.unsupportedReason(config).orElse("unknown")));
    }

    private void execute(Map<String, Object> target, ResolvedPipeline pipeline,
                         JobIntermediates intermediates) {
        String jobType = String.valueOf(target.get("job_type"));
        String resourceType = String.valueOf(target.get("resource_type"));
        long resourceId = ((Number) target.get("resource_id")).longValue();
        Object revisionValue = target.get("revision_id");
        Long revisionId = revisionValue == null ? null : ((Number) revisionValue).longValue();
        Long expectedVersion = target.get("expected_lifecycle_version") == null
                ? null
                : ((Number) target.get("expected_lifecycle_version")).longValue();
        String physicalIndex = String.valueOf(target.get("physical_name"));
        int indexVersion = ((Number) target.get("target_version")).intValue();

        if ("DELETE".equals(jobType)) {
            executeFencedDelete(physicalIndex, resourceType, resourceId, expectedVersion);
            return;
        }
        if ("KNOWLEDGE_BASE".equals(resourceType)) {
            return; // upsert 只针对 PAGE 与 ATTACHMENT
        }
        if ("PAGE".equals(resourceType)) {
            executeFencedPageUpsert(physicalIndex, indexVersion, resourceId, revisionId,
                    expectedVersion, pipeline, intermediates);
        } else {
            executeAttachmentUpsert(physicalIndex, indexVersion, resourceId, pipeline, intermediates);
        }
    }

    /**
     * 删除操作的生命周期防护：若某任务的预期版本已不再
     * 匹配（资源被恢复，版本号递增），则跳过该任务，因此
     * 延迟的删除绝不会移除已恢复的索引。不带版本的删除
     * （历史遗留的非图片清理）始终执行 —— 它们针对的是分块，而非状态。
     */
    private void executeFencedDelete(String physicalIndex, String resourceType, long resourceId,
                                     Long expectedVersion) {
        switch (resourceType) {
            case "KNOWLEDGE_BASE" -> {
                com.kwiki.wiki.domain.KnowledgeBase kb = knowledgeBases.findById(resourceId)
                        .orElse(null);
                if (kb == null) {
                    index.deleteResourceChunks(physicalIndex, "KNOWLEDGE_BASE", resourceId);
                    return;
                }
                if (expectedVersion != null && kb.getLifecycleVersion() != expectedVersion) {
                    return; // 入队之后被恢复：保留新索引
                }
                var checked = chunkIndexChecked();
                if (checked != null) {
                    checked.deleteKnowledgeBaseChunksChecked(physicalIndex, resourceId);
                } else {
                    index.deleteResourceChunks(physicalIndex, resourceType, resourceId);
                }
            }
            case "PAGE" -> {
                WikiPage page = pages.findById(resourceId).orElse(null);
                if (page == null) {
                    index.deleteResourceChunks(physicalIndex, resourceType, resourceId);
                    return;
                }
                if (expectedVersion != null && page.getLifecycleVersion() != expectedVersion) {
                    return; // 入队之后被恢复：保留新索引
                }
                index.deleteResourceChunks(physicalIndex, resourceType, resourceId);
            }
            default -> {
                // ATTACHMENT：带版本的删除来自归档批次；若该附件
                // 在此期间已被恢复为 STORED，则跳过。
                if ("ATTACHMENT".equals(resourceType) && expectedVersion != null) {
                    Attachment attachment = attachments.findById(resourceId).orElse(null);
                    if (attachment != null && attachment.isStored()) {
                        return;
                    }
                }
                index.deleteResourceChunks(physicalIndex, resourceType, resourceId);
            }
        }
    }

    private void executeFencedPageUpsert(String physicalIndex, int indexVersion, long pageId,
                                         Long revisionId, Long expectedVersion,
                                         ResolvedPipeline pipeline, JobIntermediates intermediates) {
        WikiPage page = pages.findById(pageId).orElse(null);
        if (page == null || page.isArchived()) {
            return; // 已归档的页面绝不会（重新）进入索引
        }
        if (expectedVersion != null && page.getLifecycleVersion() != expectedVersion) {
            return; // 自入队以来生命周期已变化：过期的 upsert
        }
        writeFencedUpsert(physicalIndex, indexVersion, "PAGE", pageId, revisionId,
                page.getLifecycleVersion(), page.getKbId(), pipeline, intermediates,
                pageDocument(page, revisionId));
        // 写入后复查：归档可能在我们写入期间已提交。
        WikiPage after = pages.findById(pageId).orElse(null);
        if (after == null || after.isArchived()
                || after.getLifecycleVersion() != page.getLifecycleVersion()) {
            index.deleteResourceChunks(physicalIndex, "PAGE", pageId);
        }
    }

    /**
     * 附件 upsert 仅限已校验的图片；多模态解析代额外接受 PDF
     * （内嵌图片走受保护块）。其他附件仅供展示：任何历史遗留的
     * 分块都会被清除，任务不做任何 upsert 即完成。
     */
    private void executeAttachmentUpsert(String physicalIndex, int indexVersion,
                                         long attachmentId, ResolvedPipeline pipeline,
                                         JobIntermediates intermediates) {
        Attachment attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> new IllegalStateException("attachment missing for indexing job"));
        if (!attachment.isStored()) {
            return; // 已归档的附件不得复活
        }
        boolean multimodalPdf = PARSER_VERSION_MULTIMODAL.equals(pipeline.parserVersion())
                && multimodal != null && multimodal.getIfAvailable() != null
                && isPdf(attachment.getContentType());
        if (!AttachmentIndexEligibility.isIndexableImage(attachment.getContentType())
                && !multimodalPdf) {
            index.deleteResourceChunks(physicalIndex, "ATTACHMENT", attachmentId);
            return;
        }
        writeFencedUpsert(physicalIndex, indexVersion, "ATTACHMENT", attachmentId, null,
                0, attachment.getKbId(), pipeline, intermediates,
                attachmentDocument(attachment, intermediates));
        Attachment after = attachments.findById(attachmentId).orElse(null);
        if (after == null || !after.isStored()) {
            index.deleteResourceChunks(physicalIndex, "ATTACHMENT", attachmentId);
        }
    }

    /** 共享解析/分块/嵌入中间结果，并在写入前后校验身份与维度。 */
    private void writeFencedUpsert(String physicalIndex, int indexVersion, String resourceType,
                                   long resourceId, Long revisionId, long lifecycleVersion, long kbId,
                                   ResolvedPipeline pipeline, JobIntermediates intermediates,
                                   DocumentSupplier documentSupplier) {
        String documentKey = pipeline.parserVersion();
        String chunkerKey = pipeline.parserVersion() + "|" + pipeline.chunkerVersion();
        String embeddingKey = pipeline.embeddingModel() + ":" + pipeline.embeddingDimensions();
        StructuredDocument document = intermediates.documents.computeIfAbsent(documentKey,
                key -> documentSupplier.load(pipeline));
        List<ParentChunk> parents = intermediates.parents.computeIfAbsent(chunkerKey,
                key -> pipeline.parentChunker().chunk(
                        chunkKeyPrefix(resourceType, resourceId, revisionId), document));
        List<ChildChunk> children = intermediates.children.computeIfAbsent(chunkerKey,
                key -> {
                    List<ChildChunk> all = new ArrayList<>();
                    for (ParentChunk parent : parents) {
                        all.addAll(pipeline.childChunker().chunk(parent, document));
                    }
                    return all;
                });
        // embedding 投影：去掉受保护块标记包装，保留完整图片摘要
        List<String> childTexts = children.stream()
                .map(child -> com.kwiki.indexing.multimodal.ProtectedTextProjection
                        .strip(child.content()))
                .toList();
        List<float[]> vectors = intermediates.vectors.computeIfAbsent(embeddingKey, key -> {
            metrics.counter("kwiki_indexing_embedding_calls_total",
                    "targetVersion", String.valueOf(indexVersion),
                    "model", pipeline.embeddingModel(),
                    "dimensions", String.valueOf(pipeline.embeddingDimensions()))
                    .increment();
            return pipeline.embeddings().embed(childTexts);
        });
        if (vectors.size() != children.size()) {
            throw new IllegalStateException("embedding count mismatch");
        }
        // 身份与维度防护（任务 5.4）：写入前。
        for (float[] vector : vectors) {
            if (vector.length != pipeline.embeddingDimensions()) {
                throw new IllegalStateException("embedding dimension mismatch: expected "
                        + pipeline.embeddingDimensions());
            }
        }
        IndexedVersion version = new IndexedVersion(resourceType, resourceId, revisionId,
                lifecycleVersion, kbId,
                pipeline.parserVersion(), pipeline.chunkerVersion(), pipeline.embeddingModel(),
                indexVersion, parents, children, vectors, pipeline.mappingSchemaVersion(),
                pipeline.entityLinkingVersion());
        index.upsertChunks(version, physicalIndex);
        // 写入后：文档身份再次核对（IndexedVersion 携带流水线身份）。
        if (!version.parserVersion().equals(pipeline.parserVersion())
                || !version.chunkerVersion().equals(pipeline.chunkerVersion())
                || !version.embeddingModel().equals(pipeline.embeddingModel())) {
            throw new IllegalStateException("indexed version identity drifted from pipeline");
        }
    }

    /** 按目标流水线解析出结构化文档；缓存命中时不会被调用。 */
    private interface DocumentSupplier {
        StructuredDocument load(ResolvedPipeline pipeline);
    }

    private DocumentSupplier pageDocument(WikiPage page, Long revisionId) {
        return pipeline -> {
            WikiPageRevision revision = revisions.findById(revisionId)
                    .orElseThrow(() -> new IllegalStateException("revision missing for indexing job"));
            com.kwiki.indexing.multimodal.MultimodalIndexingService multimodalService =
                    requireMultimodalFor(pipeline);
            if (multimodalService != null) {
                // 多模态解析代：发布修订的图片语法在索引投影中成为受保护块；
                // 数据库中的修订 Markdown 与页面渲染保持原样。
                return multimodalService.buildPageDocument(
                        page.getKbId(), page.getId(), revisionId, revision.getMarkdown());
            }
            return pipeline.parser().parse(page.getTitle() + ".md", "text/markdown",
                    new ByteArrayInputStream(
                            revision.getMarkdown().getBytes(StandardCharsets.UTF_8)));
        };
    }

    private DocumentSupplier attachmentDocument(Attachment attachment,
                                                JobIntermediates intermediates) {
        return pipeline -> {
            Long fileId = attachment.getContentCenterFileId();
            if (fileId == null || fileId <= 0) {
                // 故障关闭：没有内容标识，就没有任何可安全读取的东西。
                throw new AttachmentStorageException(
                        AttachmentStorageException.Category.PERMANENT,
                        "attachment has no content-center file id for indexing");
            }
            // 同一事件的多个目标只读一次内容：字节缓存按 job 共享。
            byte[] bytes = intermediates.attachmentBytes.computeIfAbsent(attachment.getId(),
                    key -> storage.readContent(fileId));
            if (AttachmentIndexEligibility.isIndexableImage(attachment.getContentType())) {
                // 图片索引基于元数据：不做 OCR、不做转写、不编造内容。
                return imageDescriptorDocument(attachment, bytes);
            }
            com.kwiki.indexing.multimodal.MultimodalIndexingService multimodalService =
                    requireMultimodalFor(pipeline);
            if (multimodalService != null && isPdf(attachment.getContentType())) {
                // 多模态解析代：PDF 内嵌图片按位置进入受保护块
                return multimodalService.buildPdfDocument(
                        pipeline.parser(), attachment.getKbId(), attachment.getId(),
                        attachment.getFileName(), attachment.getContentType(), bytes);
            }
            return pipeline.parser().parse(attachment.getFileName(),
                    attachment.getContentType(), new ByteArrayInputStream(bytes));
        };
    }

    /** 多模态代要求服务存在（fail closed）；旧代返回 null 走原路径。 */
    private com.kwiki.indexing.multimodal.MultimodalIndexingService requireMultimodalFor(
            ResolvedPipeline pipeline) {
        if (!PARSER_VERSION_MULTIMODAL.equals(pipeline.parserVersion())) {
            return null;
        }
        com.kwiki.indexing.multimodal.MultimodalIndexingService service =
                multimodal == null ? null : multimodal.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException(
                    "multimodal parser version requested but multimodal indexing is disabled");
        }
        return service;
    }

    private static boolean isPdf(String contentType) {
        return contentType != null
                && "application/pdf".equalsIgnoreCase(contentType.trim());
    }

    /**
     * 图片附件的可检索描述符。解析失败（字节
     * 与声明的图片类型不符）是显式的；空分块
     * 绝不能伪装成解析成功。
     */
    private StructuredDocument imageDescriptorDocument(Attachment attachment, byte[] bytes) {
        String declared = attachment.getContentType() == null
                ? "" : attachment.getContentType().toLowerCase(Locale.ROOT);
        if (!MediaContentSniffer.sniffImageType(bytes)
                .map(sniffed -> sniffed.equals(declared)
                        || ("image/jpg".equals(sniffed) && "image/jpeg".equals(declared)))
                .orElse(false)) {
            throw new UnsupportedInputException(
                    "image content does not match the declared type; not indexed");
        }
        String fileName = attachment.getFileName() == null ? "" : attachment.getFileName();
        String text = "图片附件 " + fileName + "（" + declared + "，"
                + bytes.length + " 字节）";
        return new com.kwiki.indexing.parse.StructuredDocument(
                List.of(new StructBlock(0, text, 0, text.length())), text);
    }

    private static String chunkKeyPrefix(String resourceType, long resourceId, Long revisionId) {
        return resourceType + ":" + resourceId + ":"
                + (revisionId == null ? "-" : revisionId);
    }

    /** 单个资源事件（job）内的中间结果缓存；仅限本批次，不跨任务。 */
    static final class JobIntermediates {
        final Map<String, StructuredDocument> documents = new HashMap<>();
        final Map<String, List<ParentChunk>> parents = new HashMap<>();
        final Map<String, List<ChildChunk>> children = new HashMap<>();
        final Map<String, List<float[]>> vectors = new HashMap<>();
        final Map<Long, byte[]> attachmentBytes = new HashMap<>();
    }

    private com.kwiki.indexing.search.ChunkIndexRepository chunkIndexChecked() {
        return chunkIndexRepository == null ? null : chunkIndexRepository.getIfAvailable();
    }

    private static String sanitize(String message) {
        String cleaned = SecretRedaction.redact(message == null ? "" : message);
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
