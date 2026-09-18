package com.kwiki.indexing.multimodal;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多模态单元测试共享的内存桩：内容中心存储、视觉摘要端口与
 * PNG 字节生成。
 */
final class MultimodalTestFixtures {

    static final class FakeStorage implements com.kwiki.wiki.attach.AttachmentStorage {
        final Map<Long, byte[]> content = new ConcurrentHashMap<>();
        final AtomicLong nextId = new AtomicLong(5000);
        final AtomicInteger uploads = new AtomicInteger();
        volatile boolean failTransiently;
        volatile boolean returnBadId;

        @Override
        public com.kwiki.wiki.attach.StoredAttachment store(
                com.kwiki.wiki.attach.AttachmentUpload upload) {
            if (failTransiently) {
                throw new com.kwiki.wiki.attach.AttachmentStorageException(
                        com.kwiki.wiki.attach.AttachmentStorageException.Category.TRANSIENT,
                        "storage transport failure");
            }
            if (returnBadId) {
                return new com.kwiki.wiki.attach.StoredAttachment(0, upload.byteSize(),
                        upload.contentType());
            }
            try (InputStream in = upload.content()) {
                byte[] bytes = in.readAllBytes();
                long id = nextId.incrementAndGet();
                content.put(id, bytes);
                uploads.incrementAndGet();
                return new com.kwiki.wiki.attach.StoredAttachment(id, bytes.length,
                        upload.contentType());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public String downloadLink(long id, String name, Duration ttl) {
            return "https://dl.example.internal/" + id;
        }

        @Override
        public byte[] readContent(long id) {
            byte[] bytes = content.get(id);
            if (bytes == null) {
                throw new com.kwiki.wiki.attach.AttachmentStorageException(
                        com.kwiki.wiki.attach.AttachmentStorageException.Category.PERMANENT,
                        "missing content");
            }
            return bytes;
        }

        @Override
        public String cdnLink(long id) {
            return "https://cdn.example.internal/f/" + id;
        }
    }

    static final class FakeVision implements ImageSummaryPort {
        final AtomicInteger calls = new AtomicInteger();
        volatile VisionSummaryException fails;

        @Override
        public String summarize(String cdnUrl) {
            calls.incrementAndGet();
            if (fails != null) {
                throw fails;
            }
            return "图片摘要：内容来自 " + cdnUrl.substring(cdnUrl.lastIndexOf('/') + 1);
        }
    }

    /** 覆写 download 以驱动外链路径（默认返回固定 PNG）。 */
    static final class StubDownloader extends SafeExternalImageDownloader {
        volatile java.util.function.Function<
                java.net.URI, SafeExternalImageDownloader.DownloadedImage> behavior =
                uri -> new SafeExternalImageDownloader.DownloadedImage(
                        pngBytes(), "image/png", 12, 12);

        StubDownloader() {
            super(1_000_000, 1_000_000, 2, Duration.ofSeconds(10), java.util.Set.of(443),
                    new MultimodalMetrics(null));
        }

        @Override
        public SafeExternalImageDownloader.DownloadedImage download(java.net.URI url) {
            return behavior.apply(url);
        }
    }

    static byte[] pngBytes() {
        try {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    12, 12, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            graphics.setColor(java.awt.Color.PINK);
            graphics.fillRect(0, 0, 12, 12);
            graphics.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 附件仓储内存桩：仅覆盖多模态路径实际调用的查询。 */
    static final class FakeAttachments {
        final Map<String, com.kwiki.wiki.domain.Attachment> byUuid =
                new ConcurrentHashMap<>();

        com.kwiki.wiki.persistence.AttachmentRepository toRepository() {
            com.kwiki.wiki.persistence.AttachmentRepository repo =
                    org.mockito.Mockito.mock(com.kwiki.wiki.persistence.AttachmentRepository.class);
            org.mockito.Mockito.when(repo.findByUuid(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(inv -> java.util.Optional.ofNullable(byUuid.get(inv.getArgument(0))));
            org.mockito.Mockito.when(repo.findByKbIdAndStatusOrderByIdDesc(
                            org.mockito.ArgumentMatchers.anyLong(),
                            org.mockito.ArgumentMatchers.anyString()))
                    .thenReturn(java.util.List.of());
            return repo;
        }
    }

    /** 为测试构造的附件注入 JPA 生成的 id。 */
    static void assignId(com.kwiki.wiki.domain.Attachment attachment, long id) {
        try {
            java.lang.reflect.Field field =
                    com.kwiki.wiki.domain.Attachment.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(attachment, id);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private MultimodalTestFixtures() {
    }
}
