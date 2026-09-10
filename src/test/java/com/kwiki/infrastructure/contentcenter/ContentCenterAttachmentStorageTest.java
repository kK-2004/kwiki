package com.kwiki.infrastructure.contentcenter;

import com.kwiki.infrastructure.config.ExternalServicesProperties;
import com.kwiki.wiki.attach.AttachmentStorageException;
import com.kwiki.wiki.attach.AttachmentUpload;
import com.kwiki.wiki.attach.StoredAttachment;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通过适配器驱动真实 SDK 访问本地 MockWebServer 端点：
 * 初始化/预签名 PUT/完成的上传流程、严格的结果校验、按 file id
 * 生成下载链接、有界的内容拉取、错误/过期处理，并证明
 * token 与签名 URL 绝不会泄漏进异常消息。
 */
class ContentCenterAttachmentStorageTest {

    private static final String TOKEN = "kapp-super-secret-token";
    private static final String DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private MockWebServer server;
    private ContentCenterAttachmentStorage storage;
    private ExternalServicesProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = properties(1024);
        storage = new ContentCenterAttachmentStorage(
                com.kk.sdk.ContentCenterClient.builder()
                        .baseUrl(server.url("/").toString())
                        .appToken(TOKEN)
                        .connectTimeout(Duration.ofSeconds(5))
                        .requestTimeout(Duration.ofSeconds(10))
                        .build(),
                properties, 1024, Duration.ofSeconds(120));
    }

    private ExternalServicesProperties properties(long maxBytes) {
        return new ExternalServicesProperties(
                new ExternalServicesProperties.ContentCenter(
                        server.url("/").toString(), TOKEN, "kwiki-src", "kwiki-path",
                        Duration.ofSeconds(5), Duration.ofSeconds(10)),
                new ExternalServicesProperties.Elasticsearch(null, null, null),
                new ExternalServicesProperties.AnswerLlm("http://l/v1", "k", "m",
                        Duration.ofSeconds(10)),
                new ExternalServicesProperties.QwenEmbedding("http://q/v1", "k",
                        "text-embedding-v4", 4, Duration.ofSeconds(10)));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static AttachmentUpload uploadOf(int size) {
        return new AttachmentUpload("spec.docx", DOCX,
                new ByteArrayInputStream(new byte[size]), size);
    }

    private void enqueueInitPutComplete(long fileId, long size) {
        String putPath = "/presigned/put";
        server.enqueue(new MockResponse().setBody("""
                {"storageKey":"k/kwiki/att.docx","source":"mock","putUrl":"%s",
                 "expiresIn":600,"fileId":null}""".formatted(server.url(putPath)))
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setBody("""
                {"fileId":%d,"name":"spec.docx","size":%d,"contentType":"%s"}"""
                        .formatted(fileId, size, DOCX))
                .addHeader("Content-Type", "application/json"));
    }

    @Test
    void uploadRunsInitPutCompleteAndReturnsValidatedIdentity() throws Exception {
        enqueueInitPutComplete(42L, 100);

        StoredAttachment stored = storage.store(uploadOf(100));

        assertThat(stored.contentCenterFileId()).isEqualTo(42L);
        assertThat(stored.verifiedByteSize()).isEqualTo(100L);
        assertThat(stored.verifiedContentType()).isEqualToIgnoringCase(DOCX);

        RecordedRequest init = server.takeRequest();
        assertThat(init.getPath()).isEqualTo("/api/open/uploads");
        assertThat(init.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        String initBody = init.getBody().readUtf8();
        assertThat(initBody).contains("spec.docx", DOCX, "\"size\":100",
                "\"source\":\"kwiki-src\"", "\"path\":\"kwiki-path\"");

        RecordedRequest put = server.takeRequest();
        assertThat(put.getMethod()).isEqualTo("PUT");
        assertThat(put.getHeader("Content-Type")).isEqualTo(DOCX);
        assertThat(put.getBody().readByteArray()).hasSize(100);

        RecordedRequest complete = server.takeRequest();
        assertThat(complete.getPath()).isEqualTo("/api/open/uploads/complete");
        String completeBody = complete.getBody().readUtf8();
        assertThat(completeBody).contains("k/kwiki/att.docx", "mock");
    }

    @Test
    void uploadRejectsResultWithoutUsableFileId() {
        String putPath = "/presigned/put";
        server.enqueue(new MockResponse().setBody("""
                {"storageKey":"k/att.docx","source":"mock","putUrl":"%s","expiresIn":600}"""
                .formatted(server.url(putPath))).addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setBody("{\"name\":\"spec.docx\",\"size\":10}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> storage.store(uploadOf(10)))
                .isInstanceOf(AttachmentStorageException.class)
                .satisfies(e -> assertThat(
                        ((AttachmentStorageException) e).getCategory())
                        .isEqualTo(AttachmentStorageException.Category.PERMANENT))
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void uploadRejectsSizeDisagreement() {
        enqueueInitPutComplete(42L, 999);

        assertThatThrownBy(() -> storage.store(uploadOf(100)))
                .isInstanceOf(AttachmentStorageException.class)
                .hasMessageContaining("size disagrees");
    }

    @Test
    void uploadRejectsContentTypeDisagreement() {
        server.enqueue(new MockResponse().setBody("""
                {"storageKey":"k/att.docx","source":"mock",
                 "putUrl":"%s","expiresIn":600}""".formatted(server.url("/presigned/put")))
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(200));
        server.enqueue(new MockResponse().setBody(
                        "{\"fileId\":7,\"name\":\"spec.docx\",\"size\":10,\"contentType\":\"text/plain\"}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> storage.store(uploadOf(10)))
                .isInstanceOf(AttachmentStorageException.class)
                .hasMessageContaining("content type disagrees");
    }

    @Test
    void uploadClassifiesRemoteServerErrorsAsTransient() {
        server.enqueue(new MockResponse().setResponseCode(503)
                .setBody("{\"message\":\"overloaded\"}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> storage.store(uploadOf(10)))
                .isInstanceOf(AttachmentStorageException.class)
                .satisfies(e -> assertThat(
                        ((AttachmentStorageException) e).getCategory())
                        .isEqualTo(AttachmentStorageException.Category.TRANSIENT))
                .hasMessageContaining("HTTP 503")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void uploadClassifiesAuthenticationErrorsAsPermanent() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody("{\"message\":\"bad app token\"}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> storage.store(uploadOf(10)))
                .isInstanceOf(AttachmentStorageException.class)
                .satisfies(e -> assertThat(
                        ((AttachmentStorageException) e).getCategory())
                        .isEqualTo(AttachmentStorageException.Category.PERMANENT))
                .hasMessageContaining("HTTP 401")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void downloadLinkRequestsByFileIdWithConfiguredFilenameAndTtl() throws Exception {
        String signed = server.url("/signed-download?X-Amz-Signature=abc&sig=def").toString();
        server.enqueue(new MockResponse().setBody(
                        "{\"url\":\"" + signed + "\",\"expiresIn\":120}")
                .addHeader("Content-Type", "application/json"));

        String url = storage.downloadLink(42L, "spec.docx", Duration.ofSeconds(120));

        assertThat(url).contains("X-Amz-Signature=abc");
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/open/download-links");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + TOKEN);
        String body = request.getBody().readUtf8().toLowerCase(Locale.ROOT);
        assertThat(body).contains("\"fileid\":42", "\"filename\":\"spec.docx\"",
                "\"expiresin\":120");
    }

    @Test
    void readContentFetchesFreshLinkWithBoundedBytes() {
        server.enqueue(new MockResponse().setBody(
                        "{\"url\":\"" + server.url("/content") + "\",\"expiresIn\":120}")
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setBody(new String(new byte[]{'a', 'b', 'c'}, StandardCharsets.ISO_8859_1)));

        byte[] bytes = storage.readContent(42L);

        assertThat(bytes).hasSize(3);
    }

    @Test
    void readContentFailsWhenResponseExceedsConfiguredLimit() {
        storage = new ContentCenterAttachmentStorage(
                com.kk.sdk.ContentCenterClient.builder()
                        .baseUrl(server.url("/").toString())
                        .appToken(TOKEN)
                        .connectTimeout(Duration.ofSeconds(5))
                        .requestTimeout(Duration.ofSeconds(10))
                        .build(),
                properties(2), 2, Duration.ofSeconds(120));
        server.enqueue(new MockResponse().setBody(
                        "{\"url\":\"" + server.url("/content") + "\",\"expiresIn\":120}")
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(200).setBody("abcdef"));

        assertThatThrownBy(() -> storage.readContent(42L))
                .isInstanceOf(AttachmentStorageException.class)
                .satisfies(e -> assertThat(
                        ((AttachmentStorageException) e).getCategory())
                        .isEqualTo(AttachmentStorageException.Category.PERMANENT))
                .hasMessageContaining("size limit");
    }

    @Test
    void readContentClassifiesExpiredOrMissingContentAsPermanent() {
        server.enqueue(new MockResponse().setBody(
                        "{\"url\":\"" + server.url("/content") + "\",\"expiresIn\":120}")
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThatThrownBy(() -> storage.readContent(42L))
                .isInstanceOf(AttachmentStorageException.class)
                .satisfies(e -> assertThat(
                        ((AttachmentStorageException) e).getCategory())
                        .isEqualTo(AttachmentStorageException.Category.PERMANENT))
                .hasMessageContaining("HTTP 404");
    }

    @Test
    void readContentClassifiesRemoteOutageAsTransient() {
        server.enqueue(new MockResponse().setBody(
                        "{\"url\":\"" + server.url("/content") + "\",\"expiresIn\":120}")
                .addHeader("Content-Type", "application/json"));
        server.enqueue(new MockResponse().setResponseCode(502));

        assertThatThrownBy(() -> storage.readContent(42L))
                .isInstanceOf(AttachmentStorageException.class)
                .satisfies(e -> assertThat(
                        ((AttachmentStorageException) e).getCategory())
                        .isEqualTo(AttachmentStorageException.Category.TRANSIENT));
    }

    @Test
    void linkFailureForMissingFileIdIsPermanentWithoutNetwork() {
        assertThatThrownBy(() -> storage.downloadLink(0L, "spec.docx", Duration.ofSeconds(60)))
                .isInstanceOf(AttachmentStorageException.class)
                .hasMessageContaining("no content-center file id");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void sdkErrorMessagesNeverLeakTokenOrSignedUrl() {
        // 服务端错误响应体刻意内嵌了完整的签名 URL 与 token。
        server.enqueue(new MockResponse().setResponseCode(400)
                .setBody("{\"message\":\"rejected for " + server.url("/signed?sig=secret-sig")
                        + " bearer " + TOKEN + "\"}")
                .addHeader("Content-Type", "application/json"));

        assertThatThrownBy(() -> storage.store(uploadOf(10)))
                .isInstanceOf(AttachmentStorageException.class)
                .hasMessageNotContaining(TOKEN)
                .hasMessageNotContaining("sig=secret-sig")
                .hasMessageNotContaining("localhost");
    }
}
