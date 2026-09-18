package com.kwiki.indexing.multimodal;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.indexing.config.MultimodalIndexingProperties;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.attach.AttachmentUpload;
import com.kwiki.wiki.attach.StoredAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * 派生图片的内容中心生命周期：上传去重、已上传 Markdown 图片
 * contentId 复用、CDN 链接获取与视觉摘要的幂等状态机。
 *
 * <p>并发契约：同一（源身份, 解析器版本, 图片哈希）的多个 worker
 * 由数据库唯一约束收敛——插入竞争的落败者重读权威行并做有界等待；
 * 只有行创建者执行上传/摘要调用，其余执行者最终读到同一
 * contentId 与同一 READY 摘要。所有外部调用都发生在数据库
 * 事务之外：行状态在独立的小事务中推进，绝不跨上传或模型
 * 调用持有连接。瞬时失败保留中间状态供重试复用；永久失败
 * 置 FAILED 并保持可审计。</p>
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "kwiki.multimodal.enabled", havingValue = "true")
public class ImageResourceService {

    private static final Logger log = LoggerFactory.getLogger(ImageResourceService.class);

    /** 并发落败者等待权威结果的轮询参数。 */
    private static final Duration WAIT_POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(60);

    public record DerivedUpload(String sourceKind, String sourceRef, long kbId,
                                String parserVersion, String sourceUrlHash) {
    }

    private final DerivedImageAssetRepository assets;
    private final DerivedImageSummaryRepository summaries;
    private final AttachmentStorage storage;
    private final ImageSummaryPort vision;
    private final MultimodalMetrics metrics;
    private final String visionModel;
    private final String promptVersion;

    public ImageResourceService(DerivedImageAssetRepository assets,
                                DerivedImageSummaryRepository summaries,
                                AttachmentStorage storage,
                                ImageSummaryPort vision,
                                ExternalServicesProperties externalProperties,
                                MultimodalIndexingProperties multimodalProperties,
                                MultimodalMetrics metrics) {
        this.assets = assets;
        this.summaries = summaries;
        this.storage = storage;
        this.vision = vision;
        this.metrics = metrics;
        this.visionModel = externalProperties.visionModel().model();
        this.promptVersion = multimodalProperties.promptVersion();
    }

    /**
     * 派生图片（PDF 提取 / 外链镜像）的权威资产行：
     * 已有权威行直接复用；否则本执行者上传并把结果 CAS 回写，
     * 竞争落败者收敛到同一行。上传校验失败（无正数 id、元数据
     * 不一致）按永久失败处理且不产生标记。
     */
    public DerivedImageAsset resolveDerivedAsset(DerivedUpload identity, byte[] imageBytes,
                                                 String contentType, String fileName) {
        String hash = PdfMultimodalParser.sha256Hex(imageBytes);
        DerivedImageAsset asset = findOrCreate(identity, hash);
        if (asset.hasAuthoritativeContent()) {
            metrics.imageStage(MultimodalMetrics.STAGE_REUSED);
            return asset;
        }
        // FAILED 行在字节未变时同样不可重试（同哈希意味着同内容）
        if (DerivedImageAsset.STATE_FAILED.equals(asset.getState())) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "derived image previously failed permanently");
        }
        StoredAttachment stored = upload(imageBytes, contentType, fileName);
        completeUpload(asset.getId(), stored);
        return assets.findById(asset.getId())
                .orElseThrow(() -> new IllegalStateException("derived image asset disappeared"));
    }

    /**
     * 已上传 Markdown 图片：直接复用附件的 contentCenterFileId，
     * 绝不二次上传；资产行仅用于摘要身份与审计。
     */
    public DerivedImageAsset resolveAttachmentAsset(long attachmentId, String attachmentUuid,
                                                    long kbId, String parserVersion,
                                                    byte[] imageBytes, long contentCenterFileId) {
        if (contentCenterFileId <= 0) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "attachment has no content-center file id");
        }
        String hash = PdfMultimodalParser.sha256Hex(imageBytes);
        String sourceRef = "attachment:" + attachmentUuid;
        Optional<DerivedImageAsset> existing = assets
                .findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
                        DerivedImageAsset.KIND_ATTACHMENT_IMAGE, sourceRef, parserVersion, hash);
        DerivedImageAsset asset;
        if (existing.isPresent()) {
            asset = existing.get();
        } else {
            asset = new DerivedImageAsset(DerivedImageAsset.KIND_ATTACHMENT_IMAGE, sourceRef,
                    kbId, parserVersion, hash);
            try {
                asset = assets.saveAndFlush(asset);
            } catch (DataIntegrityViolationException concurrent) {
                asset = assets.findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
                                DerivedImageAsset.KIND_ATTACHMENT_IMAGE, sourceRef, parserVersion, hash)
                        .orElseThrow(() -> new IllegalStateException(
                                "concurrent attachment image asset disappeared"));
            }
        }
        if (!asset.hasAuthoritativeContent()) {
            // 首次见到该身份：记录既有 contentId（不经过内容中心写路径）
            asset.markUploaded(contentCenterFileId, sniffedOrPlain(imageBytes),
                    imageBytes.length);
            try {
                assets.saveAndFlush(asset);
            } catch (ObjectOptimisticLockingFailureException lost) {
                asset = assets.findById(asset.getId()).orElseThrow();
            }
        }
        if (asset.getContentId() != contentCenterFileId) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "attachment image identity disagrees with the referenced content");
        }
        metrics.imageStage(MultimodalMetrics.STAGE_REUSED);
        return asset;
    }

    /** 按 contentId 换取内容中心 CDN URL（每次调用新签发，绝不持久化）。 */
    public String cdnUrl(long contentId) {
        return storage.cdnLink(contentId);
    }

    /**
     * 幂等摘要：READY 直接复用；否则由行创建者调用视觉模型并
     * CAS 写入 READY；竞争落败者有界等待后复用权威摘要。
     */
    public String resolveSummary(DerivedImageAsset asset) {
        if (!asset.hasAuthoritativeContent()) {
            throw new IllegalStateException("cannot summarize an asset without content id");
        }
        Optional<DerivedImageSummary> existing = summaries
                .findByAssetIdAndModelAndPromptVersion(asset.getId(), visionModel, promptVersion);
        DerivedImageSummary summary = existing.orElse(null);
        if (summary == null) {
            DerivedImageSummary created = new DerivedImageSummary(
                    asset.getId(), visionModel, promptVersion);
            try {
                summary = summaries.saveAndFlush(created);
            } catch (DataIntegrityViolationException concurrent) {
                summary = summaries.findByAssetIdAndModelAndPromptVersion(
                                asset.getId(), visionModel, promptVersion)
                        .orElseThrow(() -> new IllegalStateException(
                                "concurrent summary row disappeared"));
                return awaitUsable(summary.getId());
            }
        } else if (summary.isUsable()) {
            metrics.imageStage(MultimodalMetrics.STAGE_REUSED);
            return summary.getSummary();
        } else if (DerivedImageSummary.STATE_FAILED.equals(summary.getState())) {
            throw new VisionSummaryException(VisionSummaryException.Category.PERMANENT,
                    "image summary previously failed permanently");
        }

        // 行创建者执行模型调用（事务之外），完成后一次性 CAS 为 READY
        String cdnUrl = cdnUrl(asset.getContentId());
        String text = vision.summarize(cdnUrl);
        try {
            summary.markReady(text);
            summaries.saveAndFlush(summary);
        } catch (ObjectOptimisticLockingFailureException lost) {
            return awaitUsable(summary.getId());
        } catch (IllegalStateException alreadyAuthoritative) {
            return awaitUsable(summary.getId());
        }
        metrics.imageStage(MultimodalMetrics.STAGE_SUMMARIZED);
        return text;
    }

    public String visionModel() {
        return visionModel;
    }

    public String promptVersion() {
        return promptVersion;
    }

    // ---------- 内部 ----------

    private DerivedImageAsset findOrCreate(DerivedUpload identity, String hash) {
        Optional<DerivedImageAsset> existing = assets
                .findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
                        identity.sourceKind(), identity.sourceRef(), identity.parserVersion(), hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        DerivedImageAsset created = new DerivedImageAsset(
                identity.sourceKind(), identity.sourceRef(), identity.kbId(),
                identity.parserVersion(), hash);
        created.assignSourceUrlHash(identity.sourceUrlHash());
        try {
            return assets.saveAndFlush(created);
        } catch (DataIntegrityViolationException concurrentWinner) {
            return assets.findBySourceKindAndSourceRefAndParserVersionAndImageSha256(
                            identity.sourceKind(), identity.sourceRef(),
                            identity.parserVersion(), hash)
                    .orElseThrow(() -> new IllegalStateException(
                            "concurrent derived image asset disappeared"));
        }
    }

    private StoredAttachment upload(byte[] imageBytes, String contentType, String fileName) {
        // 失败消息已由存储适配器脱敏；分类（瞬时/永久）保留给 worker 决策
        StoredAttachment stored = storage.store(new AttachmentUpload(
                fileName == null || fileName.isBlank() ? "derived-image.png" : fileName,
                contentType,
                new ByteArrayInputStream(imageBytes),
                imageBytes.length));
        metrics.imageStage(MultimodalMetrics.STAGE_UPLOADED);
        return stored;
    }

    private void completeUpload(Long assetId, StoredAttachment stored) {
        if (stored.contentCenterFileId() <= 0) {
            markFailed(assetId);
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "derived image upload returned no usable content id");
        }
        DerivedImageAsset asset = assets.findById(assetId)
                .orElseThrow(() -> new IllegalStateException("derived image asset disappeared"));
        if (asset.hasAuthoritativeContent()) {
            // 另一执行者已完成：以其结果为准，绝不覆盖
            return;
        }
        try {
            asset.markUploaded(stored.contentCenterFileId(), stored.verifiedContentType(),
                    stored.verifiedByteSize());
            assets.saveAndFlush(asset);
        } catch (ObjectOptimisticLockingFailureException lost) {
            DerivedImageAsset winner = assets.findById(assetId).orElseThrow();
            if (!winner.hasAuthoritativeContent()) {
                throw new IllegalStateException("upload completion raced without a winner");
            }
        }
    }

    private void markFailed(Long assetId) {
        assets.findById(assetId).ifPresent(asset -> {
            asset.markFailed();
            assets.save(asset);
        });
    }

    /** 并发落败者的有界等待：轮询直到 READY 或超限（转为瞬时失败）。 */
    private String awaitUsable(Long summaryId) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            DerivedImageSummary current = summaries.findById(summaryId).orElse(null);
            if (current == null) {
                throw new IllegalStateException("summary row disappeared");
            }
            if (current.isUsable()) {
                metrics.imageStage(MultimodalMetrics.STAGE_REUSED);
                return current.getSummary();
            }
            if (DerivedImageSummary.STATE_FAILED.equals(current.getState())) {
                throw new VisionSummaryException(VisionSummaryException.Category.PERMANENT,
                        "image summary previously failed permanently");
            }
            try {
                Thread.sleep(WAIT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                        "waiting for the authoritative summary was cancelled");
            }
        }
        throw new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                "waiting for the concurrent summary exceeded the time limit");
    }

    private static String sniffedOrPlain(byte[] bytes) {
        return com.kwiki.wiki.attach.MediaContentSniffer.sniffImageType(bytes).orElse("image/png");
    }
}
