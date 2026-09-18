package com.kwiki.indexing.multimodal;

import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.domain.WikiPageRevision;
import com.kwiki.wiki.persistence.AttachmentRepository;
import com.kwiki.wiki.persistence.WikiPageRevisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 派生图片的孤儿审计：只标记、绝不删除。内容中心 SDK 目前没有
 * 删除能力，且活跃索引版本（别名目标与写启用版本）仍可能引用
 * 任意 contentId，因此本服务保守地把"源已消失"的资产行标记为
 * ORPHAN_CANDIDATE 并计入指标，供运维侧后续在具备安全删除
 * 能力时处置。源仍存在（附件 STORED / 修订存在）或已标记过的
 * 行不受影响；判定与标记都是幂等的。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "kwiki.multimodal.enabled", havingValue = "true")
public class DerivedImageCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DerivedImageCleanupService.class);

    private static final int BATCH = 200;

    private final DerivedImageAssetRepository assets;
    private final AttachmentRepository attachments;
    private final WikiPageRevisionRepository revisions;
    private final MultimodalMetrics metrics;

    @Autowired
    public DerivedImageCleanupService(DerivedImageAssetRepository assets,
                                      AttachmentRepository attachments,
                                      WikiPageRevisionRepository revisions,
                                      MultimodalMetrics metrics) {
        this.assets = assets;
        this.attachments = attachments;
        this.revisions = revisions;
        this.metrics = metrics;
    }

    public DerivedImageCleanupService(DerivedImageAssetRepository assets,
                                      AttachmentRepository attachments,
                                      WikiPageRevisionRepository revisions) {
        this(assets, attachments, revisions, new MultimodalMetrics(null));
    }

    /** 扫描一批非 NONE 的资产行并标记源已消失的候选；返回本批标记数。 */
    public int auditBatch() {
        List<DerivedImageAsset> batch = nextBatch();
        List<Long> orphanIds = new ArrayList<>();
        for (DerivedImageAsset asset : batch) {
            if (sourceIsGone(asset)) {
                orphanIds.add(asset.getId());
            }
        }
        if (orphanIds.isEmpty()) {
            return 0;
        }
        int marked = assets.markOrphanCandidates(orphanIds);
        for (int i = 0; i < marked; i++) {
            metrics.cleanupCandidate();
        }
        log.info("derived image cleanup audit marked candidates batch={} marked={}",
                batch.size(), marked);
        return marked;
    }

    private List<DerivedImageAsset> nextBatch() {
        // 只审计已上传完成的行：PENDING/FAILED 行由其创建路径继续负责
        List<DerivedImageAsset> batch = new ArrayList<>();
        for (DerivedImageAsset asset : assets.findAll(PageRequest.of(0, BATCH))) {
            if (asset.hasAuthoritativeContent()
                    && DerivedImageAsset.CLEANUP_NONE.equals(asset.getCleanupState())) {
                batch.add(asset);
            }
        }
        return batch;
    }

    private boolean sourceIsGone(DerivedImageAsset asset) {
        switch (asset.getSourceKind()) {
            case DerivedImageAsset.KIND_ATTACHMENT_PDF, DerivedImageAsset.KIND_ATTACHMENT_IMAGE -> {
                // sourceRef = "attachment:{id 或 uuid}"
                String ref = asset.getSourceRef().substring("attachment:".length());
                Optional<Attachment> attachment = ref.chars().allMatch(Character::isDigit)
                        && !ref.isEmpty()
                        ? attachments.findById(Long.parseLong(ref))
                        : attachments.findByUuid(ref);
                return attachment.isEmpty() || !attachment.get().isStored();
            }
            case DerivedImageAsset.KIND_EXTERNAL_URL -> {
                // sourceRef = "page:{pageId}:rev:{revisionId}"
                String ref = asset.getSourceRef();
                int revAt = ref.lastIndexOf(":rev:");
                if (revAt < 0) {
                    return false; // 未知形态：保守不动
                }
                long revisionId = Long.parseLong(ref.substring(revAt + ":rev:".length()));
                Optional<WikiPageRevision> revision = revisions.findById(revisionId);
                return revision.isEmpty();
            }
            default -> {
                return false;
            }
        }
    }
}
