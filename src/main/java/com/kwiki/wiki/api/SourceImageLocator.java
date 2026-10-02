package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.indexing.multimodal.DerivedImageAsset;
import com.kwiki.indexing.multimodal.DerivedImageAssetRepository;
import com.kwiki.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 把检索片段中的图片（contentId）定位到页面来源 PDF 的页码，供阅读页直接跳到源文件对应页。
 * 页码在建索引时写入图片资产行（source_page）；这里只做授权与归属校验后查表，
 * 历史资产没有页码时返回未找到，由前端回退为在解析文本中定位。
 */
@Service
public class SourceImageLocator {

    private final PageSourcePreviewService previews;
    private final ObjectProvider<DerivedImageAssetRepository> assets;

    public SourceImageLocator(PageSourcePreviewService previews,
                              ObjectProvider<DerivedImageAssetRepository> assets) {
        this.previews = previews;
        this.assets = assets;
    }

    public record ImageLocation(int page) {
    }

    public ImageLocation locate(CurrentUser user, long kbId, long pageId, long contentId) {
        DerivedImageAssetRepository repository = assets.getIfAvailable();
        if (repository == null) {
            throw new NotFoundException("image location not found");
        }
        // 授权（页面可读）与来源归属：资产必须派生自本页面的来源附件
        long attachmentId = previews.sourceAttachmentId(user, kbId, pageId)
                .orElseThrow(() -> new NotFoundException("image location not found"));
        String sourceRef = "attachment:" + attachmentId;
        return repository.findByContentId(contentId).stream()
                .filter(asset -> DerivedImageAsset.KIND_ATTACHMENT_PDF.equals(asset.getSourceKind()))
                .filter(asset -> asset.getSourceKbId() == kbId)
                .filter(asset -> sourceRef.equals(asset.getSourceRef()))
                .map(DerivedImageAsset::getSourcePage)
                .filter(Objects::nonNull)
                .findFirst()
                .map(ImageLocation::new)
                .orElseThrow(() -> new NotFoundException("image location not found"));
    }
}
