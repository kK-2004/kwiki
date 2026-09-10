package com.kwiki.infrastructure.contentcenter;

import com.kk.sdk.ContentCenterClient;
import com.kk.sdk.ContentCenterException;
import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.wiki.attach.AttachmentStorage;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.attach.AttachmentUpload;
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

/**
 * Content-center-backed attachment storage. Uploads stream through the SDK's
 * init/presigned-PUT/complete flow and only the returned file id survives into
 * kwiki; downloads and indexing reads always address content by that id through
 * freshly issued short-lived links. Every failure surfaces as a sanitized
 * {@link AttachmentStorageException} whose message contains neither the app token
 * nor any signed URL, while the retry category is preserved for the worker.
 */
@Component
public class ContentCenterAttachmentStorage implements AttachmentStorage {

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
            // No expiresIn: ask the deployment's CDN policy for its most durable form.
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
        // Fresh link per read: never reuse a possibly expired URL across attempts.
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

    /** Strict validation: an inconsistent or incomplete result never becomes STORED. */
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

    /** Maps SDK failures into sanitized exceptions; messages never echo SDK text. */
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
