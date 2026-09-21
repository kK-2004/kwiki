package com.kwiki.indexing.multimodal;

import com.kwiki.indexing.multimodal.SafeExternalImageDownloader.DownloadedImage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 抗 SSRF 下载器契约：仅绝对 HTTPS、无 user-info、端口白名单；
 * loopback/私网/保留/云元数据/CGNAT/组播地址一律拒绝（初始 URL
 * 与每次重定向同策略）；字节上限、图片魔数、像素上限与重定向
 * 次数上限；瞬时（5xx/超时）与永久失败的分类；错误消息不含 URL。
 * 允许路径通过注入"放行回环地址"的测试策略配合 MockWebServer
 * 验证（生产构造使用严格默认策略）。
 */
class SafeExternalImageDownloaderTest {

    private MockWebServer server;
    /** 信任本测试自签名证书的 HTTPS 客户端。 */
    private HttpClient httpsClient;
    /** 放行回环地址的测试实例：验证内容层面的策略。 */
    private SafeExternalImageDownloader permissive;
    /** 严格默认策略实例：验证地址层面（端口/网段/user-info）。 */
    private SafeExternalImageDownloader strict;

    @BeforeEach
    void start() throws Exception {
        // 自签名证书让 MockWebServer 以 HTTPS 服务（下载器只接受 https）
        okhttp3.tls.HeldCertificate certificate = new okhttp3.tls.HeldCertificate.Builder()
                .addSubjectAlternativeName("localhost")
                .build();
        okhttp3.tls.HandshakeCertificates serverCertificates =
                new okhttp3.tls.HandshakeCertificates.Builder()
                        .heldCertificate(certificate)
                        .build();
        server = new MockWebServer();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.start();
        okhttp3.tls.HandshakeCertificates clientCertificates =
                new okhttp3.tls.HandshakeCertificates.Builder()
                        .addTrustedCertificate(certificate.certificate())
                        .build();
        httpsClient = HttpClient.newBuilder()
                .sslContext(clientCertificates.sslContext())
                .build();
        MultimodalMetrics metrics = new MultimodalMetrics(null);
        permissive = new SafeExternalImageDownloader(
                httpsClient, 200_000, 1_000_000, 2,
                Duration.ofSeconds(10), Set.of(server.getPort(), 443), metrics,
                address -> true);
        strict = new SafeExternalImageDownloader(200_000, 1_000_000, 2,
                Duration.ofSeconds(10), Set.of(443), metrics);
    }

    @AfterEach
    void stop() throws Exception {
        server.shutdown();
    }

    private static byte[] pngBytes(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.ORANGE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private URI serverUri(String path) {
        return URI.create(server.url(path).toString());
    }

    private static okio.Buffer body(byte[] bytes) {
        okio.Buffer buffer = new okio.Buffer();
        buffer.write(bytes);
        return buffer;
    }

    @Test
    void publicHttpsImageWithinLimitsIsAccepted() throws Exception {
        byte[] png = pngBytes(20, 20);
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/png")
                .setBody(body(png)));
        DownloadedImage downloaded = permissive.download(serverUri("/img.png"));
        assertThat(downloaded.contentType()).isEqualTo("image/png");
        assertThat(downloaded.bytes()).containsExactly(png);
        assertThat(downloaded.width()).isEqualTo(20);
    }

    @Test
    void redirectsAreFollowedAndEachHopRevalidated() throws Exception {
        byte[] png = pngBytes(10, 10);
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", serverUri("/hop2").toString()));
        server.enqueue(new MockResponse().setResponseCode(301)
                .setHeader("Location", "/hop3"));
        server.enqueue(new MockResponse().setHeader("Content-Type", "image/png")
                .setBody(body(png)));
        assertThat(permissive.download(serverUri("/hop1")).bytes()).containsExactly(png);
        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    void redirectLimitIsPermanentFailure() throws Exception {
        for (int i = 0; i < 4; i++) {
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", serverUri("/loop" + i).toString()));
        }
        assertThatThrownBy(() -> permissive.download(serverUri("/loop-start")))
                .isInstanceOf(ExternalImageFetchException.class)
                .hasFieldOrPropertyWithValue("category",
                        ExternalImageFetchException.Category.PERMANENT)
                .hasMessageContaining("redirect limit");
    }

    @Test
    void nonImageBytesAndOversizeResponsesArePermanentFailures() throws Exception {
        server.enqueue(new MockResponse().setBody(body("not an image at all".getBytes())));
        assertThatThrownBy(() -> permissive.download(serverUri("/raw.txt")))
                .isInstanceOf(ExternalImageFetchException.class)
                .hasMessageContaining("not a supported image type");

        byte[] bigPng = pngBytes(300, 300); // ~超过部分限制的场景由实例参数控制
        SafeExternalImageDownloader tinyByteLimit = new SafeExternalImageDownloader(
                httpsClient, 16, 1_000_000, 2, Duration.ofSeconds(10),
                Set.of(server.getPort(), 443), new MultimodalMetrics(null), address -> true);
        server.enqueue(new MockResponse().setBody(body(bigPng)));
        assertThatThrownBy(() -> tinyByteLimit.download(serverUri("/big.png")))
                .isInstanceOf(ExternalImageFetchException.class)
                .hasMessageContaining("byte limit");

        SafeExternalImageDownloader tinyPixelLimit = new SafeExternalImageDownloader(
                httpsClient, 200_000, 64, 2, Duration.ofSeconds(10),
                Set.of(server.getPort(), 443), new MultimodalMetrics(null), address -> true);
        server.enqueue(new MockResponse().setBody(body(pngBytes(20, 20))));
        assertThatThrownBy(() -> tinyPixelLimit.download(serverUri("/px.png")))
                .isInstanceOf(ExternalImageFetchException.class)
                .hasMessageContaining("pixel limit");
    }

    @Test
    void serverErrorsAreTransientAndClientErrorsPermanent() {
        server.enqueue(new MockResponse().setResponseCode(503));
        assertThatThrownBy(() -> permissive.download(serverUri("/unavailable")))
                .isInstanceOf(ExternalImageFetchException.class)
                .hasFieldOrPropertyWithValue("category",
                        ExternalImageFetchException.Category.TRANSIENT);
        server.enqueue(new MockResponse().setResponseCode(404));
        assertThatThrownBy(() -> permissive.download(serverUri("/missing")))
                .isInstanceOf(ExternalImageFetchException.class)
                .hasFieldOrPropertyWithValue("category",
                        ExternalImageFetchException.Category.PERMANENT);
    }

    @Test
    void schemeUserinfoAndPortPolicyAreEnforcedBeforeAnyRequest() {
        assertThatThrownBy(() -> strict.download(URI.create("http://example.com/img.png")))
                .hasMessageContaining("https");
        assertThatThrownBy(() -> strict.download(
                URI.create("https://user:secret@example.com/img.png")))
                .hasMessageContaining("user info");
        assertThatThrownBy(() -> strict.download(URI.create("https://example.com:8443/img.png")))
                .hasMessageContaining("port");
    }

    @Test
    void deniedNetworkRangesAreRejectedWithoutConnecting() {
        for (String url : new String[] {
                "https://127.0.0.1/img.png",
                "https://localhost/img.png",
                "https://10.1.2.3/img.png",
                "https://172.16.0.9/img.png",
                "https://192.168.1.4/img.png",
                "https://169.254.169.254/latest/meta-data",   // 云元数据
                "https://100.64.0.7/img.png",                  // CGNAT
                "https://198.18.0.5/img.png",                  // benchmark 网段
                "https://240.0.0.1/img.png",                   // 保留
                "https://[::1]/img.png",                       // IPv6 环回地址
                "https://[fe80::1]/img.png",                   // IPv6 链路本地地址
                "https://[fd12::1]/img.png" }) {               // IPv6 ULA
            assertThatThrownBy(() -> strict.download(URI.create(url)))
                    .as(url)
                    .isInstanceOf(ExternalImageFetchException.class)
                    .hasFieldOrPropertyWithValue("category",
                            ExternalImageFetchException.Category.PERMANENT)
                    .hasMessageNotContaining(url);
        }
    }

    @Test
    void addressPolicyClassifiesCanonicalRanges() throws Exception {
        assertThat(SafeExternalImageDownloader.isPublicAddress(
                InetAddress.getByName("93.184.216.34"))).isTrue();
        assertThat(SafeExternalImageDownloader.isPublicAddress(
                InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946"))).isTrue();
        assertThat(SafeExternalImageDownloader.isPublicAddress(
                InetAddress.getByName("192.0.2.9"))).isFalse();
        assertThat(SafeExternalImageDownloader.isPublicAddress(
                InetAddress.getByName("203.0.113.7"))).isFalse();
        assertThat(SafeExternalImageDownloader.isPublicAddress(
                InetAddress.getByName("100.100.100.100"))).isFalse();
    }
}
