package com.kwiki.wiki.api;

import com.kk2004.common.exception.NotFoundException;
import com.kwiki.indexing.multimodal.DerivedImageAsset;
import com.kwiki.indexing.multimodal.DerivedImageAssetRepository;
import com.kwiki.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SourceImageLocatorTest {

    private final PageSourcePreviewService previews = mock(PageSourcePreviewService.class);
    private final DerivedImageAssetRepository assets = mock(DerivedImageAssetRepository.class);
    private final CurrentUser user = mock(CurrentUser.class);

    @SuppressWarnings("unchecked")
    private SourceImageLocator locator() {
        ObjectProvider<DerivedImageAssetRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(assets);
        return new SourceImageLocator(previews, provider);
    }

    private static DerivedImageAsset asset(long kbId, String sourceRef, Integer page) {
        DerivedImageAsset asset = mock(DerivedImageAsset.class);
        when(asset.getSourceKind()).thenReturn(DerivedImageAsset.KIND_ATTACHMENT_PDF);
        when(asset.getSourceKbId()).thenReturn(kbId);
        when(asset.getSourceRef()).thenReturn(sourceRef);
        when(asset.getSourcePage()).thenReturn(page);
        return asset;
    }

    @Test
    void 返回建索引时记录的来源PDF页码() {
        when(previews.sourceAttachmentId(user, 1L, 19L)).thenReturn(Optional.of(9L));
        DerivedImageAsset candidate = asset(1L, "attachment:9", 3);
        when(assets.findByContentId(202L)).thenReturn(List.of(candidate));

        assertThat(locator().locate(user, 1L, 19L, 202L).page()).isEqualTo(3);
    }

    @Test
    void 图片不属于本页来源附件时不返回页码() {
        when(previews.sourceAttachmentId(user, 1L, 19L)).thenReturn(Optional.of(9L));
        DerivedImageAsset candidate = asset(1L, "attachment:10", 3);
        when(assets.findByContentId(202L)).thenReturn(List.of(candidate));

        assertThatThrownBy(() -> locator().locate(user, 1L, 19L, 202L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 历史资产没有页码时返回未找到() {
        when(previews.sourceAttachmentId(user, 1L, 19L)).thenReturn(Optional.of(9L));
        DerivedImageAsset candidate = asset(1L, "attachment:9", null);
        when(assets.findByContentId(202L)).thenReturn(List.of(candidate));

        assertThatThrownBy(() -> locator().locate(user, 1L, 19L, 202L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void 页面没有来源附件时返回未找到且不查资产() {
        when(previews.sourceAttachmentId(user, 1L, 19L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locator().locate(user, 1L, 19L, 202L)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(assets);
    }
}
