package com.kwiki.indexing.job;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.infrastructure.observability.SecretRedaction;
import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ChildChunker;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.chunk.ParentChunker;
import com.kwiki.indexing.parse.DocumentParseService;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.UnsupportedInputException;
import com.kwiki.indexing.pipeline.ChunkEmbeddingPort;
import com.kwiki.indexing.pipeline.ChunkIndexPort;
import com.kwiki.indexing.pipeline.IndexedVersion;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
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
import java.util.Map;

/**
 * At-least-once worker over leased jobs: parse → parent chunks → child chunks →
 * child-only embedding → idempotent index writes. Deterministic keys make replays
 * safe (crash after write simply overwrites the same versioned documents).
 * Failures use bounded exponential backoff; unsupported inputs fail terminally;
 * errors are sanitized before storage.
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
    private final WikiPageRepository pages;
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
                          WikiPageRepository pages,
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
        this.pages = pages;
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

    /** Claims and processes one batch; returns the number of processed jobs. */
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
            // Permanent storage problems (rejected request, missing file id, oversized
            // content) dead-letter immediately; transient ones re-enter the backoff.
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

        if ("DELETE".equals(jobType)) {
            index.deleteResourceChunks(resourceType, resourceId);
            return;
        }
        if ("PAGE".equals(resourceType)) {
            indexVersion(pageVersion(resourceId, revisionId));
        } else {
            indexVersion(attachmentVersion(resourceId));
        }
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
            // Fail closed: without the content identity there is nothing safe to read.
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "attachment has no content-center file id for indexing");
        }
        byte[] bytes = storage.readContent(fileId);
        StructuredDocument document = parser.parse(attachment.getFileName(),
                attachment.getContentType(), new ByteArrayInputStream(bytes));
        return buildVersion("ATTACHMENT", attachmentId, null, attachment.getKbId(), document);
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
