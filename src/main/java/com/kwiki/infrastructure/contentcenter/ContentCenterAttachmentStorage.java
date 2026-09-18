package com.kwiki.infrastructure.contentcenter;

import com.kk.sdk.ContentCenterClient;
import com.kk.sdk.ContentCenterException;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.attach.AttachmentUpload;
import com.kwiki.wiki.attach.DirectUpload;
import com.kwiki.wiki.attach.StoredAttachment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 以内容中心（content center）为后端的附件存储（attachment storage）。上传经由 SDK 的
 * init/presigned-PUT/complete 流程流式处理，仅有返回的文件 id 会进入
 * kwiki；下载与索引读取始终通过该 id、经由新签发的短期链接寻址内容。
 * 任何失败都会表现为经过脱敏（redaction）的 {@link AttachmentStorageException}，其消息既不包含
 * app token 也不包含任何签名 URL，同时保留重试类别供 worker 使用。
 */
@Component
public class ContentCenterAttachmentStorage implements AttachmentStorage {

    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+)");

    private final ContentCenterClient client;
    private final ExternalServicesProperties.ContentCenter config;
    private final HttpClient fetchClient;
    private final long maxBytes;
    private final Duration presignTtl;

    public ContentCenterAttachmentStorage(ContentCenterClient kwikiContentCenterClient,
                                          ExternalServicesProperties properties,
                                          @Value("${kwiki.attachments.max-bytes:52428800}") long maxBytes,
                                          @Value("${kwiki.attachments.presign-ttl:300s}") Duration presignTtl) {
        this.client = kwikiContentCenterClient;
        this.config = properties.contentCenter();
        this.maxBytes = maxBytes;
        this.presignTtl = presignTtl;
        this.fetchClient = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public StoredAttachment store(AttachmentUpload upload) {
        ContentCenterClient.UploadOptions options = ContentCenterClient.UploadOptions.defaults()
                .contentType(upload.contentType());
        if (config.source() != null && !config.source().isBlank()) {
            options = options.source(config.source());
        }
        if (config.path() != null && !config.path().isBlank()) {
            options = options.path(config.path());
        }
        try {
            ContentCenterClient.UploadResult result = client.upload(
                    upload.content(), upload.fileName(), upload.byteSize(), options);
            return requireConsistentResult(upload, result);
        } catch (ContentCenterException e) {
            throw translate("upload", e);
        }
    }

    @Override
    public DirectUpload initiateUpload(String fileName, String contentType, long byteSize) {
        var options = ContentCenterClient.UploadOptions.defaults().contentType(contentType);
        if (config.source() != null && !config.source().isBlank()) options = options.source(config.source());
        if (config.path() != null && !config.path().isBlank()) options = options.path(config.path());
        try {
            var result = client.initUpload(fileName, byteSize, options);
            if (result == null || result.storageKey() == null || result.storageKey().isBlank()
                    || result.source() == null || result.source().isBlank()
                    || result.putUrl() == null || result.putUrl().isBlank() || result.expiresIn() <= 0) {
                throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                        "content-center returned an invalid upload session");
            }
            return new DirectUpload(result.storageKey(), result.source(), result.putUrl(), result.expiresIn(), result.fileId());
        } catch (ContentCenterException e) {
            throw translate("init upload", e);
        }
    }

    @Override
    public StoredAttachment completeUpload(String storageKey, String source, String contentType, long byteSize) {
        return completeUpload(storageKey, source, contentType, byteSize, null);
    }

    @Override
    public StoredAttachment completeUpload(String storageKey, String source, String contentType, long byteSize, Long fileId) {
        try {
            var result = client.completeUpload(storageKey, source);
            if (fileId != null && !fileId.equals(result.fileId())) {
                throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT, "content-center file identity disagrees");
            }
            return requireConsistentResult(new AttachmentUpload("direct-upload", contentType,
                    InputStream.nullInputStream(), byteSize), result);
        } catch (ContentCenterException e) {
            // SDK 0.1.3 provider consumes its UPLOADING record on completion. Recover only
            // this specific repeat-completion response, using the server-owned init identity.
            // Other errors (including auth, object missing and outages) must fail closed.
            if (fileId != null && fileId > 0 && e.getStatus() == 400 && e.getMessage() != null
                    && e.getMessage().startsWith("未找到上传初始化记录:")) {
                return recoverCompletedUpload(fileId, contentType, byteSize);
            }
            throw translate("complete upload", e);
        }
    }

    private StoredAttachment recoverCompletedUpload(long fileId, String expectedType, long expectedSize) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadLink(fileId, null, presignTtl)))
                .timeout(config.requestTimeout()).header("Range", "bytes=0-0").GET().build();
        try {
            var response = fetchClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                long size = -1;
                if (response.statusCode() == 206) {
                    String range = response.headers().firstValue("Content-Range").orElse("");
                    if (range.matches("bytes 0-0/[0-9]+")) size = Long.parseLong(range.substring(range.indexOf('/') + 1));
                } else if (response.statusCode() == 200) {
                    size = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                }
                String type = response.headers().firstValue("Content-Type").orElse("");
                if (size != expectedSize || !type.equalsIgnoreCase(expectedType)) {
                    throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                            "completed object metadata disagrees with upload session");
                }
                return new StoredAttachment(fileId, size, type);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT, "upload recovery interrupted");
        } catch (java.io.IOException | NumberFormatException e) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT, "upload recovery failed");
        }
    }

    @Override
    public byte[] readPrefix(long contentCenterFileId, int length) {
        if (length < 1 || length > 8192) throw new IllegalArgumentException("invalid prefix length");
        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadLink(contentCenterFileId, null, presignTtl)))
                .timeout(config.requestTimeout()).header("Range", "bytes=0-" + (length - 1)).GET().build();
        try {
            // Limit consumption even if a provider ignores Range; close immediately after the prefix.
            var response = fetchClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200 && response.statusCode() != 206) {
                    throw new AttachmentStorageException(categoryFor(response.statusCode()), "content prefix fetch failed");
                }
                if (response.statusCode() == 206
                        && !response.headers().firstValue("Content-Range").orElse("").startsWith("bytes 0-")) {
                    throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT, "invalid content range");
                }
                return body.readNBytes(length);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT, "content prefix fetch interrupted");
        } catch (java.io.IOException e) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT, "content prefix fetch failed");
        }
    }

    @Override
    public String downloadLink(long contentCenterFileId, String downloadFileName, Duration ttl) {
        if (contentCenterFileId <= 0) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "attachment has no content-center file id");
        }
        try {
            ContentCenterClient.DownloadLink link = client.getDownloadLink(
                    ContentCenterClient.DownloadLinkRequest.ofFileId(contentCenterFileId)
                            .filename(downloadFileName)
                            .expiresIn(ttl.toSeconds()));
            if (link == null || link.url() == null || link.url().isBlank()) {
                throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                        "content-center download link was empty");
            }
            if (link.expiresIn() <= 0) {
                throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                        "content-center download link expires immediately");
            }
            return link.url();
        } catch (ContentCenterException e) {
            throw translate("download link", e);
        }
    }

    @Override
    public String cdnLink(long contentCenterFileId) {
        if (contentCenterFileId <= 0) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "attachment has no content-center file id");
        }
        try {
            // 不传 expiresIn：向部署环境的 CDN 策略请求其最持久的形式。
            ContentCenterClient.CdnLink link = client.getCdnLink(
                    ContentCenterClient.CdnLinkRequest.ofFileId(contentCenterFileId));
            if (link == null || link.url() == null || link.url().isBlank()) {
                throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                        "content-center cdn link was empty");
            }
            return link.url();
        } catch (ContentCenterException e) {
            throw translate("cdn link", e);
        }
    }

    @Override
    public byte[] readContent(long contentCenterFileId) {
        if (contentCenterFileId <= 0) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "attachment has no content-center file id");
        }
        // 每次读取都生成新链接：绝不在多次尝试之间复用可能已过期的 URL。
        String url = downloadLink(contentCenterFileId, null, presignTtl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .GET()
                .build();
        HttpResponse<byte[]> response;
        try {
            response = fetchClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT,
                    "content fetch timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT,
                    "content fetch interrupted", e);
        } catch (java.io.IOException e) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT,
                    "content fetch transport failure", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new AttachmentStorageException(categoryFor(response.statusCode()),
                    "content fetch failed (HTTP " + response.statusCode() + ")");
        }
        byte[] body = response.body();
        if (body == null || body.length == 0) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "content fetch returned no bytes");
        }
        if (body.length > maxBytes) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "content exceeds the configured attachment size limit");
        }
        return body;
    }

    @Override
    public ContentRange readContentRange(long contentCenterFileId, long start, long end) {
        if (contentCenterFileId <= 0 || start < 0 || end < start) {
            throw new IllegalArgumentException("invalid content range");
        }
        String url = downloadLink(contentCenterFileId, null, presignTtl);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(config.requestTimeout())
                .header("Range", "bytes=" + start + "-" + end)
                .GET().build();
        try {
            HttpResponse<byte[]> response = fetchClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 206) {
                Matcher matcher = CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse(""));
                if (!matcher.matches()) {
                    throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                            "content range response is invalid");
                }
                long actualStart = Long.parseLong(matcher.group(1));
                long actualEnd = Long.parseLong(matcher.group(2));
                long total = Long.parseLong(matcher.group(3));
                byte[] body = response.body();
                if (actualStart != start || actualEnd < actualStart || total <= actualEnd
                        || body == null || body.length != actualEnd - actualStart + 1
                        || total > maxBytes) {
                    throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                            "content range response is inconsistent");
                }
                return new ContentRange(body, actualStart, actualEnd, total);
            }
            if (response.statusCode() == 200) {
                byte[] body = response.body();
                long total = response.headers().firstValueAsLong("Content-Length").orElse(body == null ? -1 : body.length);
                if (body == null || body.length == 0 || total <= 0 || total > maxBytes
                        || total != body.length || start >= total) {
                    throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                            "content range response is unavailable");
                }
                long actualEnd = Math.min(end, total - 1);
                return new ContentRange(java.util.Arrays.copyOfRange(body, (int) start, (int) actualEnd + 1),
                        start, actualEnd, total);
            }
            throw new AttachmentStorageException(categoryFor(response.statusCode()),
                    "content range fetch failed (HTTP " + response.statusCode() + ")");
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT,
                    "content range fetch timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT,
                    "content range fetch interrupted", e);
        } catch (java.io.IOException e) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT,
                    "content range fetch transport failure", e);
        }
    }

    /** 严格校验：不一致或不完整的结果绝不会变为 STORED。 */
    private StoredAttachment requireConsistentResult(AttachmentUpload upload,
                                                     ContentCenterClient.UploadResult result) {
        if (result == null || result.fileId() == null || result.fileId() <= 0) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "content-center upload returned no usable file id");
        }
        if (result.storageKey() == null || result.storageKey().isBlank()
                || result.source() == null || result.source().isBlank()) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "content-center upload result is missing storage metadata");
        }
        if (result.size() != upload.byteSize()) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "content-center upload size disagrees with the request");
        }
        String verifiedType = result.contentType() == null ? "" : result.contentType();
        String requestedType = upload.contentType() == null ? "" : upload.contentType();
        if (verifiedType.isBlank()
                || !verifiedType.toLowerCase(Locale.ROOT).equals(requestedType.toLowerCase(Locale.ROOT))) {
            throw new AttachmentStorageException(AttachmentStorageException.Category.PERMANENT,
                    "content-center upload content type disagrees with the request");
        }
        return new StoredAttachment(result.fileId(), result.size(), verifiedType);
    }

    /** 将 SDK 失败映射为已净化的异常；消息绝不回显 SDK 原文。 */
    private AttachmentStorageException translate(String operation, ContentCenterException e) {
        int status = e.getStatus();
        if (status <= 0) {
            return new AttachmentStorageException(AttachmentStorageException.Category.TRANSIENT,
                    "content-center " + operation + " transport failure", e);
        }
        return new AttachmentStorageException(categoryFor(status),
                "content-center " + operation + " failed (HTTP " + status + ")", e);
    }

    private static AttachmentStorageException.Category categoryFor(int httpStatus) {
        return httpStatus == 429 || httpStatus >= 500
                ? AttachmentStorageException.Category.TRANSIENT
                : AttachmentStorageException.Category.PERMANENT;
    }
}
