package com.kwiki.indexing.multimodal;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.indexing.config.MultimodalIndexingProperties;
import com.kwiki.wiki.attach.AttachmentStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 派生图片内容中心生命周期契约（单元级，内存桩）：
 * 新派生上传、既有附件复用（绝不二次上传）、不一致元数据拒绝、
 * 瞬时/永久失败分类与中间状态复用、重试复用已上传 contentId、
 * 提示词版本演进产生独立摘要身份、CDN 查询、以及错误消息
 * 不含 CDN URL 或密钥。数据库唯一约束下的真实并发收敛由
 * 外部 MySQL IT（DerivedImageRepositoryContractTest）覆盖。
 */
class ImageResourceServiceTest {

    private MultimodalTestFixtures.FakeStorage storage;
    private MultimodalTestFixtures.FakeVision vision;
    private TestMultimodalRepos.AssetBacking assetBacking;
    private TestMultimodalRepos.SummaryBacking summaryBacking;
    private ImageResourceService service;

    @BeforeEach
    void setUpService() {
        storage = new MultimodalTestFixtures.FakeStorage();
        vision = new MultimodalTestFixtures.FakeVision();
        assetBacking = new TestMultimodalRepos.AssetBacking();
        summaryBacking = new TestMultimodalRepos.SummaryBacking();
        service = new ImageResourceService(
                TestMultimodalRepos.assets(assetBacking),
                TestMultimodalRepos.summaries(summaryBacking),
                storage, vision, properties(), multimodal("v1"), new MultimodalMetrics(null));
    }

    private static ExternalServicesProperties properties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("http://cc", "k", null, null,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m",
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "k", "m", 4,
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.VisionModel("http://v/v1", "vision-secret-key",
                        "qwen3.7-flash", Duration.ofSeconds(5), Duration.ofSeconds(30), 1, 2));
    }

    private static MultimodalIndexingProperties multimodal(String promptVersion) {
        return new MultimodalIndexingProperties(true, promptVersion, 20, 64, 1_000_000,
                1_000_000, 512, 1_000_000, Duration.ofSeconds(20), 3, java.util.Set.of(443));
    }

    private ImageResourceService.DerivedUpload pdfUpload() {
        return new ImageResourceService.DerivedUpload(DerivedImageAsset.KIND_ATTACHMENT_PDF,
                "attachment:9001", 7L, "kwiki-parse-2", null);
    }

    @Test
    void newDerivedImageUploadsOnceAndRetriesReuseTheContentId() {
        byte[] bytes = MultimodalTestFixtures.pngBytes();
        DerivedImageAsset first = service.resolveDerivedAsset(pdfUpload(), bytes,
                "image/png", "a.png");
        assertThat(first.hasAuthoritativeContent()).isTrue();
        assertThat(storage.uploads.get()).isEqualTo(1);

        DerivedImageAsset retry = service.resolveDerivedAsset(pdfUpload(), bytes,
                "image/png", "a.png");
        assertThat(retry.getContentId()).isEqualTo(first.getContentId());
        assertThat(storage.uploads.get()).isEqualTo(1); // 重试不重复上传

        String summary = service.resolveSummary(retry);
        assertThat(summary).contains("图片摘要");
        assertThat(vision.calls.get()).isEqualTo(1);
        assertThat(service.resolveSummary(retry)).isEqualTo(summary);
        assertThat(vision.calls.get()).isEqualTo(1); // READY 摘要复用
    }

    @Test
    void inconsistentUploadMetadataIsRejectedPermanently() {
        storage.returnBadId = true;
        assertThatThrownBy(() -> service.resolveDerivedAsset(pdfUpload(),
                MultimodalTestFixtures.pngBytes(), "image/png", "a.png"))
                .isInstanceOf(AttachmentStorageException.class)
                .hasFieldOrPropertyWithValue("category",
                        AttachmentStorageException.Category.PERMANENT);
        // 失败被持久审计：后续同身份重试判为永久失败
        assertThatThrownBy(() -> service.resolveDerivedAsset(pdfUpload(),
                MultimodalTestFixtures.pngBytes(), "image/png", "a.png"))
                .isInstanceOf(AttachmentStorageException.class);
    }

    @Test
    void transientStorageFailureKeepsIntermediatesForRetry() {
        storage.failTransiently = true;
        assertThatThrownBy(() -> service.resolveDerivedAsset(pdfUpload(),
                MultimodalTestFixtures.pngBytes(), "image/png", "a.png"))
                .isInstanceOf(AttachmentStorageException.class)
                .hasFieldOrPropertyWithValue("category",
                        AttachmentStorageException.Category.TRANSIENT);
        assertThat(assetBacking.byKey.values().iterator().next().getState())
                .isEqualTo(DerivedImageAsset.STATE_PENDING);
        storage.failTransiently = false;
        DerivedImageAsset recovered = service.resolveDerivedAsset(pdfUpload(),
                MultimodalTestFixtures.pngBytes(), "image/png", "a.png");
        assertThat(recovered.hasAuthoritativeContent()).isTrue();
        assertThat(storage.uploads.get()).isEqualTo(1);
    }

    @Test
    void attachmentReuseNeverUploadsAndEnforcesIdentity() {
        byte[] bytes = MultimodalTestFixtures.pngBytes();
        assertThatThrownBy(() -> service.resolveAttachmentAsset(1L, "uuid-a", 7L,
                "kwiki-parse-2", bytes, 0))
                .isInstanceOf(AttachmentStorageException.class)
                .hasMessageContaining("no content-center file id");

        DerivedImageAsset asset = service.resolveAttachmentAsset(1L, "uuid-a", 7L,
                "kwiki-parse-2", bytes, 4242L);
        assertThat(asset.getContentId()).isEqualTo(4242L);
        assertThat(storage.uploads.get()).isZero();
        // 再次解析：复用同一资产行
        assertThat(service.resolveAttachmentAsset(1L, "uuid-a", 7L, "kwiki-parse-2",
                bytes, 4242L).getId()).isEqualTo(asset.getId());
    }

    @Test
    void promptVersionBumpCreatesIndependentSummaryIdentity() {
        DerivedImageAsset asset = service.resolveDerivedAsset(pdfUpload(),
                MultimodalTestFixtures.pngBytes(), "image/png", "a.png");
        service.resolveSummary(asset);
        assertThat(vision.calls.get()).isEqualTo(1);

        ImageResourceService bumpedService = new ImageResourceService(
                TestMultimodalRepos.assets(assetBacking),
                TestMultimodalRepos.summaries(summaryBacking),
                storage, vision, properties(), multimodal("v2"), new MultimodalMetrics(null));
        bumpedService.resolveSummary(asset); // 新提示词 → 新摘要身份 → 再次调用模型
        assertThat(vision.calls.get()).isEqualTo(2);
    }

    @Test
    void cdnLookupDelegatesByContentIdAndDiagnosticsStaySanitized() {
        assertThat(service.cdnUrl(4242L)).isEqualTo("https://cdn.example.internal/f/4242");
        vision.fails = new VisionSummaryException(VisionSummaryException.Category.TRANSIENT,
                "vision summary transport failure");
        DerivedImageAsset asset = service.resolveDerivedAsset(pdfUpload(),
                MultimodalTestFixtures.pngBytes(), "image/png", "a.png");
        assertThatThrownBy(() -> service.resolveSummary(asset))
                .isInstanceOf(VisionSummaryException.class)
                .hasMessageNotContaining("cdn.example.internal")
                .hasMessageNotContaining("vision-secret-key");
    }
}
