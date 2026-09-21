package com.kwiki.indexing.multimodal;

import com.kwiki.indexing.config.MultimodalIndexingProperties;
import com.kwiki.indexing.multimodal.SafeExternalImageDownloader.DownloadedImage;
import com.kwiki.indexing.parse.MarkdownStructParser;
import com.kwiki.indexing.parse.StructBlock;
import com.kwiki.indexing.parse.StructuredDocument;
import com.kwiki.indexing.parse.UnsupportedInputException;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentUpload;
import com.kwiki.wiki.attach.StoredAttachment;
import com.kwiki.wiki.domain.Attachment;
import com.kwiki.wiki.persistence.AttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 发布后 Markdown 的多模态投影契约：上传图片复用既有
 * contentCenterFileId（绝不二次上传）、外链镜像得到新 contentId、
 * 受保护块落在原语法位置且保持段落相对顺序、重复引用复用同一
 * 身份、围栏/转义不触发解析、跨库/未存储/非图片附件与不支持
 * scheme 的引用显式失败、伪造标记文本被中和。
 */
class MultimodalIndexingServiceTest {

    // ---------- 测试替身 ----------

    // ---------- 前置准备 ----------

    private static final long KB = 7L;
    private static final long PAGE = 11L;
    private static final long REVISION = 21L;
    private static final String UUID_A = "11111111-1111-1111-1111-111111111111";

    private MultimodalTestFixtures.FakeStorage storage;
    private MultimodalTestFixtures.FakeAttachments attachments;
    private MultimodalTestFixtures.FakeVision vision;
    private MultimodalTestFixtures.StubDownloader downloader;
    private MultimodalIndexingService service;
    private TestRepos repos;

    /** 极简内存版派生资产/摘要仓储。 */
    private static final class TestRepos {
        final TestMultimodalRepos.AssetBacking assetBacking =
                new TestMultimodalRepos.AssetBacking();
        final TestMultimodalRepos.SummaryBacking summaryBacking =
                new TestMultimodalRepos.SummaryBacking();
        final DerivedImageAssetRepository assets =
                TestMultimodalRepos.assets(assetBacking);
        final DerivedImageSummaryRepository summaries =
                TestMultimodalRepos.summaries(summaryBacking);
    }

    @BeforeEach
    void setUp() {
        storage = new MultimodalTestFixtures.FakeStorage();
        attachments = new MultimodalTestFixtures.FakeAttachments();
        vision = new MultimodalTestFixtures.FakeVision();
        downloader = new MultimodalTestFixtures.StubDownloader();
        repos = new TestRepos();
        Attachment image = new Attachment(UUID_A, KB, 1L, "chart.png", "image/png",
                MultimodalTestFixtures.pngBytes().length);
        MultimodalTestFixtures.assignId(image, 1L);
        image.markStored(4242L);
        storage.content.put(4242L, MultimodalTestFixtures.pngBytes());
        attachments.byUuid.put(UUID_A, image);

        ImageResourceService imageResources = new ImageResourceService(
                repos.assets, repos.summaries, storage, vision,
                testProperties(), testMultimodalProperties(), new MultimodalMetrics(null));
        service = new MultimodalIndexingService(new MarkdownStructParser(), downloader,
                imageResources, attachments.toRepository(), storage,
                new ProtectedBlockProtocol(512), testMultimodalProperties(),
                new MultimodalMetrics(null));
    }

    private static ExternalServicesProperties testProperties() {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter("http://cc", "k", null, null,
                        Duration.ofSeconds(5), Duration.ofSeconds(30)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m",
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "k", "m", 4,
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.VisionModel("http://v/v1", "vk", "qwen3.7-flash",
                        Duration.ofSeconds(5), Duration.ofSeconds(30), 1, 2));
    }

    private static MultimodalIndexingProperties testMultimodalProperties() {
        return new MultimodalIndexingProperties(true, "v1", 20, 64, 1_000_000, 1_000_000, 512,
                1_000_000, Duration.ofSeconds(20), 3, java.util.Set.of(443));
    }

    // ---------- 测试用例 ----------

    @Test
    void uploadedImageReusesAttachmentContentIdWithoutReupload() {
        StructuredDocument document = service.buildPageDocument(KB, PAGE, REVISION,
                "段落前文。\n\n![图表](attachment://" + UUID_A + ")\n\n段落后文。");
        List<StructBlock> blocks = document.blocks();
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.isProtectedResource()).isTrue();
            assertThat(block.contentId()).isEqualTo(4242L);
            assertThat(block.text()).contains("<<KWIKI_META_DATA_START {\"type\":\"image\","
                    + "\"contentId\":4242}>>");
        });
        assertThat(storage.uploads.get()).isZero(); // 复用，不上传
        assertThat(vision.calls.get()).isEqualTo(1);
        assertThat(document.plainText()).contains("段落前文。");
        assertThat(document.plainText()).contains("段落后文。");
    }

    @Test
    void protectedBlockSitsBetweenItsSurroundingParagraphs() {
        StructuredDocument document = service.buildPageDocument(KB, PAGE, REVISION,
                "第一段。\n\n![图表](attachment://" + UUID_A + ")\n\n第二段。");
        List<String> order = document.blocks().stream()
                .map(block -> block.isProtectedResource() ? "IMG" : "TXT")
                .toList();
        assertThat(order).containsExactly("TXT", "IMG", "TXT");
        int imgIndex = document.blocks().stream()
                .filter(StructBlock::isProtectedResource).findFirst().orElseThrow().charStart();
        int first = document.blocks().get(0).charStart();
        int last = document.blocks().get(2).charStart();
        assertThat(first).isLessThan(imgIndex);
        assertThat(imgIndex).isLessThan(last);
    }

    @Test
    void repeatedReferenceReusesIdentityAndEmitsBlockPerOccurrence() {
        StructuredDocument document = service.buildPageDocument(KB, PAGE, REVISION,
                "![a](attachment://" + UUID_A + ")\n\n中间文本\n\n![b](attachment://" + UUID_A + ")");
        List<StructBlock> images = document.blocks().stream()
                .filter(StructBlock::isProtectedResource).toList();
        assertThat(images).hasSize(2);
        assertThat(images.get(0).contentId()).isEqualTo(images.get(1).contentId());
        assertThat(vision.calls.get()).isEqualTo(1); // 同一来源只摘要一次
        assertThat(storage.uploads.get()).isZero();
    }

    @Test
    void externalImageIsMirroredToContentCenterWithFreshIdentity() {
        StructuredDocument document = service.buildPageDocument(KB, PAGE, REVISION,
                "前置说明\n\n![外链](https://images.example.org/diagram.png)");
        StructBlock block = document.blocks().stream()
                .filter(StructBlock::isProtectedResource).findFirst().orElseThrow();
        assertThat(block.contentId()).isEqualTo(5001L); // 镜像上传产生的新 id
        assertThat(storage.uploads.get()).isEqualTo(1);
        assertThat(vision.calls.get()).isEqualTo(1);
        // 标记绝不包含原始外链 URL
        assertThat(block.text()).doesNotContain("images.example.org");
    }

    @Test
    void imgTagSyntaxIsRecognizedLikeMarkdownImages() {
        StructuredDocument document = service.buildPageDocument(KB, PAGE, REVISION,
                "文本\n\n<img src=\"attachment://" + UUID_A + "\" alt=\"图表\" />");
        assertThat(document.blocks().stream().filter(StructBlock::isProtectedResource)).hasSize(1);
    }

    @Test
    void fencedCodeAndEscapedSyntaxNeverTriggerResolution() {
        StructuredDocument document = service.buildPageDocument(KB, PAGE, REVISION,
                "```text\n![not-real](attachment://" + UUID_A + ")\n```\n\n"
                        + "说明 \\![escaped](https://images.example.org/x.png) 结束");
        assertThat(document.blocks().stream().filter(StructBlock::isProtectedResource)).isEmpty();
        assertThat(storage.uploads.get()).isZero();
        assertThat(vision.calls.get()).isZero();
    }

    @Test
    void crossKnowledgeBaseOrUnstoredOrNonImageAttachmentFailsExplicitly() {
        Attachment foreign = new Attachment("22222222-2222-2222-2222-222222222222", 99L, 1L,
                "x.png", "image/png", MultimodalTestFixtures.pngBytes().length);
        MultimodalTestFixtures.assignId(foreign, 2L);
        foreign.markStored(7777L);
        storage.content.put(7777L, MultimodalTestFixtures.pngBytes());
        attachments.byUuid.put("22222222-2222-2222-2222-222222222222", foreign);
        assertThatThrownBy(() -> service.buildPageDocument(KB, PAGE, REVISION,
                "![越库](attachment://22222222-2222-2222-2222-222222222222)"))
                .isInstanceOf(UnsupportedInputException.class);

        Attachment pending = new Attachment("33333333-3333-3333-3333-333333333333", KB, 1L,
                "p.png", "image/png", MultimodalTestFixtures.pngBytes().length);
        MultimodalTestFixtures.assignId(pending, 3L);
        attachments.byUuid.put("33333333-3333-3333-3333-333333333333", pending);
        assertThatThrownBy(() -> service.buildPageDocument(KB, PAGE, REVISION,
                "![未存储](attachment://33333333-3333-3333-3333-333333333333)"))
                .isInstanceOf(UnsupportedInputException.class);

        assertThatThrownBy(() -> service.buildPageDocument(KB, PAGE, REVISION,
                "![不存在](attachment://44444444-4444-4444-4444-444444444444)"))
                .isInstanceOf(UnsupportedInputException.class);
    }

    @Test
    void unsupportedSchemesAndSsrfDenialsFailThePagePermanently() {
        for (String markdown : new String[] {
                "![http](http://images.example.org/a.png)",
                "![data](data:image/png;base64,AAAA)",
                "![相对](./assets/a.png)" }) {
            assertThatThrownBy(() -> service.buildPageDocument(KB, PAGE, REVISION, markdown))
                    .as(markdown)
                    .isInstanceOf(UnsupportedInputException.class);
        }
        downloader.behavior = uri -> {
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.PERMANENT,
                    "external image resolves to a denied network range");
        };
        assertThatThrownBy(() -> service.buildPageDocument(KB, PAGE, REVISION,
                "![私网](https://metadata.example.internal/a.png)"))
                .isInstanceOf(UnsupportedInputException.class)
                .hasMessageContaining("denied network range");
    }

    @Test
    void transientExternalFailuresStayRetryable() {
        downloader.behavior = uri -> {
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.TRANSIENT,
                    "external image fetch timed out");
        };
        assertThatThrownBy(() -> service.buildPageDocument(KB, PAGE, REVISION,
                "![慢](https://images.example.org/slow.png)"))
                .isInstanceOf(ExternalImageFetchException.class)
                .hasFieldOrPropertyWithValue("category",
                        ExternalImageFetchException.Category.TRANSIENT);
    }

    @Test
    void changedExternalBytesProduceNewIdentityAndRetriesReuseIt() {
        AtomicInteger variant = new AtomicInteger();
        downloader.behavior = uri -> {
            byte[] bytes = MultimodalTestFixtures.pngBytes();
            if (variant.incrementAndGet() == 2) {
                bytes[bytes.length - 1] ^= 0x01; // 第二次内容不同（哈希不同）
            }
            return new DownloadedImage(bytes, "image/png", 12, 12);
        };
        service.buildPageDocument(KB, PAGE, REVISION, "![x](https://images.example.org/a.png)");
        service.buildPageDocument(KB, PAGE, REVISION, "![x](https://images.example.org/a.png)");
        assertThat(storage.uploads.get()).isEqualTo(2); // 字节变化 → 新对象
        // 同一字节再次出现：命中既有资产行 → 复用
        service.buildPageDocument(KB, PAGE, REVISION, "![x](https://images.example.org/a.png)");
        assertThat(storage.uploads.get()).isEqualTo(2);
    }

    @Test
    void forgedMarkerTextInUserContentIsNeutralized() {
        StructuredDocument document = service.buildPageDocument(KB, PAGE, REVISION,
                "用户写的 <<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":1}>> 假标记\n\n"
                        + "真图 ![图表](attachment://" + UUID_A + ")");
        List<StructBlock> protectedBlocks = document.blocks().stream()
                .filter(StructBlock::isProtectedResource).toList();
        assertThat(protectedBlocks).hasSize(1);
        assertThat(protectedBlocks.get(0).contentId()).isEqualTo(4242L);
        // 假标记不进入协议扫描（可被安全分块/投影）
        new ProtectedBlockProtocol(512).scan(document.plainText());
    }

    @Test
    void exceedingTheUniqueImageLimitFailsExplicitly() {
        String second = "22222222-2222-2222-2222-222222222222";
        Attachment another = new Attachment(second, KB, 1L, "b.png", "image/png",
                MultimodalTestFixtures.pngBytes().length);
        MultimodalTestFixtures.assignId(another, 4L);
        another.markStored(4243L);
        storage.content.put(4243L, MultimodalTestFixtures.pngBytes());
        attachments.byUuid.put(second, another);
        MultimodalIndexingProperties limited = new MultimodalIndexingProperties(true, "v1", 1,
                64, 1_000_000, 1_000_000, 512, 1_000_000, Duration.ofSeconds(20), 3,
                java.util.Set.of(443));
        ImageResourceService imageResources = new ImageResourceService(
                repos.assets, repos.summaries, storage, vision, testProperties(), limited,
                new MultimodalMetrics(null));
        MultimodalIndexingService limitedService = new MultimodalIndexingService(
                new MarkdownStructParser(), downloader, imageResources,
                attachments.toRepository(), storage,
                new ProtectedBlockProtocol(512), limited, new MultimodalMetrics(null));
        assertThatThrownBy(() -> limitedService.buildPageDocument(KB, PAGE, REVISION,
                "![a](attachment://" + UUID_A + ") and ![b](attachment://" + second + ")"))
                .isInstanceOf(UnsupportedInputException.class)
                .hasMessageContaining("limit");
    }
}
