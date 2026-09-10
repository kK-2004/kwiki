package com.kwiki.indexing.job;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.infrastructure.observability.SecretRedaction;
import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ChildChunker;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.chunk.ParentChunker;
import com.kwiki.indexing.parse.AttachmentIndexEligibility;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.UnsupportedInputException;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.indexing.pipeline.ChunkIndexPort;
import com.kwiki.indexing.pipeline.IndexedVersion;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 作用于已租用任务的至少一次（at-least-once）工作线程：解析 → 父分块 → 子分块 →
 * 仅对子分块做向量嵌入 → 幂等写索引。确定性的 key 使重放
 * 安全（写入后崩溃只会覆盖同一份带版本号的文档）。
 * 失败使用有界指数退避；不支持的输入直接终止性失败；
 * 错误在存储前已被净化。
 */
@Component
public class IndexingWorker {

    public static final String PARSER_VERSION = "kwiki-parse-1";
    public static final String CHUNKER_VERSION = "kwiki-chunk-1";

    private final IndexingJobClaimer claimer;
    private final IndexingJobStore jobs;
    private final DocumentParseService parser;
    private final ParentChunker parentChunker;
    private final ChildChunker childChunker;
    private final ChunkEmbeddingPort embeddings;
    private final ChunkIndexPort index;
    private final org.springframework.beans.factory.ObjectProvider<com.kwiki.indexing.search.ChunkIndexRepository> chunkIndexRepository;
    private final WikiPageRepository pages;
    private final com.kwiki.wiki.persistence.KnowledgeBaseRepository knowledgeBases;
    private final WikiPageRevisionRepository revisions;
    private final AttachmentRepository attachments;
    private final AttachmentStorage storage;
    private final String embeddingModel;
    private final int embeddingDimensions;
    private final int maxAttempts;
    private final long baseBackoffSeconds;
    private final long maxBackoffSeconds;
    private final MeterRegistry metrics;
    private volatile boolean cancelled;

    public IndexingWorker(IndexingJobClaimer claimer,
                          IndexingJobStore jobs,
                          DocumentParseService parser,
                          ParentChunker parentChunker,
                          ChildChunker childChunker,
                          ChunkEmbeddingPort embeddings,
                          ChunkIndexPort index,
                          org.springframework.beans.factory.ObjectProvider<com.kwiki.indexing.search.ChunkIndexRepository> chunkIndexRepository,
                          WikiPageRepository pages,
                          com.kwiki.wiki.persistence.KnowledgeBaseRepository knowledgeBases,
                          WikiPageRevisionRepository revisions,
                          AttachmentRepository attachments,
                          AttachmentStorage storage,
                          ExternalServicesProperties properties,
                          MeterRegistry metrics,
                          @Value("${kwiki.indexing.max-attempts:8}") int maxAttempts,
                          @Value("${kwiki.indexing.base-backoff-seconds:30}") long baseBackoffSeconds,
                          @Value("${kwiki.indexing.max-backoff-seconds:3600}") long maxBackoffSeconds) {
        this.claimer = claimer;
        this.jobs = jobs;
        this.parser = parser;
        this.parentChunker = parentChunker;
        this.childChunker = childChunker;
        this.embeddings = embeddings;
        this.index = index;
        this.chunkIndexRepository = chunkIndexRepository;
        this.pages = pages;
        this.knowledgeBases = knowledgeBases;
        this.revisions = revisions;
        this.attachments = attachments;
        this.storage = storage;
        this.embeddingModel = properties.qwenEmbedding().model();
        this.embeddingDimensions = properties.qwenEmbedding().dimensions();
        this.maxAttempts = maxAttempts;
        this.baseBackoffSeconds = baseBackoffSeconds;
        this.maxBackoffSeconds = maxBackoffSeconds;
        this.metrics = metrics;
    }

    public void cancel() {
        this.cancelled = true;
    }

    /** 认领并处理一个批次；返回已处理的任务数。 */
    public int runBatch(String owner, int batchSize) {
        List<Long> claimed = claimer.claim(owner, batchSize);
        int processed = 0;
        for (Long jobId : claimed) {
            if (cancelled) {
                break;
            }
            process(jobId);
            processed++;
        }
        return processed;
    }

    void process(long jobId) {
        long start = System.nanoTime();
        try {
            Map<String, Object> row = jobs.findById(jobId).orElse(null);
            if (row == null || !"LEASED".equals(row.get("state"))) {
                return;
            }
            execute(row);
            jobs.complete(jobId);
            metrics.counter("kwiki_indexing_jobs_total", "outcome", "completed").increment();
        } catch (UnsupportedInputException e) {
            jobs.fail(jobId, e.getClass().getSimpleName(),
                    sanitize(e.getMessage()), 0, baseBackoffSeconds, maxBackoffSeconds);
            metrics.counter("kwiki_indexing_jobs_total", "outcome", "unsupported").increment();
        } catch (AttachmentStorageException e) {
            // 永久性存储故障（请求被拒、文件 id 缺失、内容过大）
            // 立即进入死信；临时性故障则重新进入退避。
            int attempts = e.getCategory() == AttachmentStorageException.Category.PERMANENT
                    ? 0 : maxAttempts;
            jobs.fail(jobId, e.getClass().getSimpleName(),
                    sanitize(e.getMessage()), attempts, baseBackoffSeconds, maxBackoffSeconds);
            metrics.counter("kwiki_indexing_jobs_total", "outcome",
                    attempts == 0 ? "permanent-storage-failure" : "transient-storage-failure").increment();
        } catch (Exception e) {
            jobs.fail(jobId, e.getClass().getSimpleName(), sanitize(e.getMessage()),
                    maxAttempts, baseBackoffSeconds, maxBackoffSeconds);
            metrics.counter("kwiki_indexing_jobs_total", "outcome", "failed").increment();
        } finally {
            Timer.builder("kwiki_indexing_job_duration")
                    .description("indexing job processing time")
                    .register(metrics)
                    .record(java.time.Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private void execute(Map<String, Object> row) {
        String jobType = String.valueOf(row.get("job_type"));
        String resourceType = String.valueOf(row.get("resource_type"));
        long resourceId = ((Number) row.get("resource_id")).longValue();
        Object revisionValue = row.get("revision_id");
        Long revisionId = revisionValue == null ? null : ((Number) revisionValue).longValue();
        Long expectedVersion = row.get("expected_lifecycle_version") == null
                ? null
                : ((Number) row.get("expected_lifecycle_version")).longValue();

        if ("DELETE".equals(jobType)) {
            executeFencedDelete(resourceType, resourceId, expectedVersion);
            return;
        }
        if ("KNOWLEDGE_BASE".equals(resourceType)) {
            return; // upsert 只针对 PAGE 与 ATTACHMENT
        }
        if ("PAGE".equals(resourceType)) {
            executeFencedPageUpsert(resourceId, revisionId, expectedVersion);
        } else {
            executeAttachmentUpsert(resourceId);
        }
    }

    /**
     * 删除操作的生命周期防护：若某任务的预期版本已不再
     * 匹配（资源被恢复，版本号递增），则跳过该任务，因此
     * 延迟的删除绝不会移除已恢复的索引。不带版本的删除
     * （历史遗留的非图片清理）始终执行 —— 它们针对的是分块，而非状态。
     */
    private void executeFencedDelete(String resourceType, long resourceId, Long expectedVersion) {
        switch (resourceType) {
            case "KNOWLEDGE_BASE" -> {
                com.kwiki.wiki.domain.KnowledgeBase kb = knowledgeBases.findById(resourceId)
                        .orElse(null);
                if (kb == null) {
                    index.deleteResourceChunks("KNOWLEDGE_BASE", resourceId);
                    return;
                }
                if (expectedVersion != null && kb.getLifecycleVersion() != expectedVersion) {
                    return; // 入队之后被恢复：保留新索引
                }
                var checked = chunkIndexChecked();
                if (checked != null) {
                    checked.deleteKnowledgeBaseChunksChecked(resourceId);
                } else {
                    index.deleteResourceChunks(resourceType, resourceId);
                }
            }
            case "PAGE" -> {
                WikiPage page = pages.findById(resourceId).orElse(null);
                if (page == null) {
                    index.deleteResourceChunks(resourceType, resourceId);
                    return;
                }
                if (expectedVersion != null && page.getLifecycleVersion() != expectedVersion) {
                    return; // 入队之后被恢复：保留新索引
                }
                index.deleteResourceChunks(resourceType, resourceId);
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
                index.deleteResourceChunks(resourceType, resourceId);
            }
        }
    }

    private void executeFencedPageUpsert(long pageId, Long revisionId, Long expectedVersion) {
        WikiPage page = pages.findById(pageId).orElse(null);
        if (page == null || page.isArchived()) {
            return; // 已归档的页面绝不会（重新）进入索引
        }
        if (expectedVersion != null && page.getLifecycleVersion() != expectedVersion) {
            return; // 自入队以来生命周期已变化：过期的 upsert
        }
        indexVersion(pageVersion(pageId, revisionId));
        // 写入后复查：归档可能在我们写入期间已提交。
        WikiPage after = pages.findById(pageId).orElse(null);
        if (after == null || after.isArchived()
                || after.getLifecycleVersion() != page.getLifecycleVersion()) {
            index.deleteResourceChunks("PAGE", pageId);
        }
    }

    /**
     * 附件 upsert 仅限已校验的图片。非图片
     * 附件仅供展示：任何历史遗留的分块都会被清除，任务
     * 不做任何 upsert 即完成。
     */
    private void executeAttachmentUpsert(long attachmentId) {
        Attachment attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> new IllegalStateException("attachment missing for indexing job"));
        if (!attachment.isStored()) {
            return; // 已归档的附件不得复活
        }
        if (!AttachmentIndexEligibility.isIndexableImage(attachment.getContentType())) {
            index.deleteResourceChunks("ATTACHMENT", attachmentId);
            return;
        }
        indexVersion(attachmentVersion(attachmentId));
        Attachment after = attachments.findById(attachmentId).orElse(null);
        if (after == null || !after.isStored()) {
            index.deleteResourceChunks("ATTACHMENT", attachmentId);
        }
    }

    private com.kwiki.indexing.search.ChunkIndexRepository chunkIndexChecked() {
        return chunkIndexRepository == null ? null : chunkIndexRepository.getIfAvailable();
    }

    private IndexedVersion pageVersion(long pageId, Long revisionId) {
        WikiPage page = pages.findById(pageId)
                .orElseThrow(() -> new IllegalStateException("page missing for indexing job"));
        WikiPageRevision revision = revisions.findById(revisionId)
                .orElseThrow(() -> new IllegalStateException("revision missing for indexing job"));
        StructuredDocument document = parser.parse(
                page.getTitle() + ".md", "text/markdown",
                new ByteArrayInputStream(revision.getMarkdown().getBytes(StandardCharsets.UTF_8)));
        return buildVersion("PAGE", pageId, revisionId, page.getKbId(), document);
    }

    private IndexedVersion attachmentVersion(long attachmentId) {
        Attachment attachment = attachments.findById(attachmentId)
                .orElseThrow(() -> new IllegalStateException("attachment missing for indexing job"));
        Long fileId = attachment.getContentCenterFileId();
        if (fileId == null || fileId <= 0) {
            // 故障关闭：没有内容标识，就没有任何可安全读取的东西。
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "attachment has no content-center file id for indexing");
        }
        StructuredDocument document;
        if (AttachmentIndexEligibility.isIndexableImage(attachment.getContentType())) {
            // 图片索引基于元数据（名称/类型/来源）。图片
            // 字节不会被转录：不做 OCR、不做转写、不编造
            // 内容。内容读取失败会以显式失败暴露出来。
            byte[] bytes = storage.readContent(fileId);
            document = imageDescriptorDocument(attachment, bytes);
        } else {
            byte[] bytes = storage.readContent(fileId);
            document = parser.parse(attachment.getFileName(),
                    attachment.getContentType(), new ByteArrayInputStream(bytes));
        }
        return buildVersion("ATTACHMENT", attachmentId, null, attachment.getKbId(), document);
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
        return new StructuredDocument(
                List.of(new StructBlock(0, text, 0, text.length())), text);
    }

    private IndexedVersion buildVersion(String resourceType, long resourceId, Long revisionId,
                                        long kbId, StructuredDocument document) {
        String keyPrefix = resourceType + ":" + resourceId + ":"
                + (revisionId == null ? "-" : revisionId);
        List<ParentChunk> parents = parentChunker.chunk(keyPrefix, document);
        List<ChildChunk> children = new ArrayList<>();
        for (ParentChunk parent : parents) {
            children.addAll(childChunker.chunk(parent, document));
        }
        List<String> childTexts = children.stream().map(ChildChunk::content).toList();
        List<float[]> vectors = childTexts.isEmpty() ? List.of() : embeddings.embed(childTexts);
        if (vectors.size() != children.size()) {
            throw new IllegalStateException("embedding count mismatch");
        }
        for (float[] vector : vectors) {
            if (vector.length != embeddingDimensions) {
                throw new IllegalStateException("embedding dimension mismatch");
            }
        }
        return new IndexedVersion(resourceType, resourceId, revisionId, kbId,
                PARSER_VERSION, CHUNKER_VERSION, embeddingModel, 1,
                parents, children, vectors);
    }

    private void indexVersion(IndexedVersion version) {
        index.upsertChunks(version);
    }

    private static String sanitize(String message) {
        String cleaned = SecretRedaction.redact(message == null ? "" : message);
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }
}
