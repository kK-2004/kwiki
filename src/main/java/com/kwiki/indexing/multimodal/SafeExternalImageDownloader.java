package com.kwiki.indexing.multimodal;

import com.kwiki.wiki.attach.MediaContentSniffer;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 抗 SSRF 的外链图片下载器：只接受绝对 HTTPS URL（无 user-info、
 * 端口在显式白名单内），DNS 解析出的每一个地址都必须是公网地址
 * （拒绝 loopback、link-local、site-local、私网、保留、CGNAT、
 * 云元数据、组播等），每次重定向重新校验地址与端口，限制重定向
 * 次数、响应字节、解码像素与总时长；绝不携带用户 cookie、
 * Authorization 或任何内部请求头。响应字节必须通过图片魔数嗅探
 * 且为受支持的图片类型。所有失败都是脱敏的
 * {@link ExternalImageFetchException}，消息不含 URL。
 */
public class SafeExternalImageDownloader {

    /** 嗅探通过的受支持图片类型（image/jpg 规范化为 image/jpeg）。 */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp");

    public record DownloadedImage(byte[] bytes, String contentType, int width, int height) {
    }

    private final HttpClient client;
    private final int maxExternalImageBytes;
    private final int maxImagePixels;
    private final int maxRedirects;
    private final Duration totalTimeout;
    private final Set<Integer> allowedPorts;
    private final MultimodalMetrics metrics;
    private final java.util.function.Predicate<InetAddress> addressPolicy;

    public SafeExternalImageDownloader(int maxExternalImageBytes, int maxImagePixels,
                                       int maxRedirects, Duration totalTimeout,
                                       Set<Integer> allowedPorts, MultimodalMetrics metrics) {
        this(newBuilderDefaultsClient(), maxExternalImageBytes, maxImagePixels, maxRedirects,
                totalTimeout, allowedPorts, metrics, SafeExternalImageDownloader::isPublicAddress);
    }

    /** 供测试注入自定义 HttpClient 与地址策略（生产构造使用严格默认策略）。 */
    SafeExternalImageDownloader(HttpClient client, int maxExternalImageBytes, int maxImagePixels,
                                int maxRedirects, Duration totalTimeout,
                                Set<Integer> allowedPorts, MultimodalMetrics metrics,
                                java.util.function.Predicate<InetAddress> addressPolicy) {
        this.client = client;
        this.maxExternalImageBytes = maxExternalImageBytes;
        this.maxImagePixels = maxImagePixels;
        this.maxRedirects = maxRedirects;
        this.totalTimeout = totalTimeout;
        this.allowedPorts = Set.copyOf(allowedPorts);
        this.metrics = metrics;
        this.addressPolicy = addressPolicy;
    }

    private static HttpClient newBuilderDefaultsClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public DownloadedImage download(URI url) {
        Instant deadline = Instant.now().plus(totalTimeout);
        URI current = url;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            HttpResponse<InputStream> response = fetchOnce(current, deadline);
            int status = response.statusCode();
            if (isRedirect(status)) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw permanent("redirect without a location");
                }
                URI next = current.resolve(location);
                if (!next.isAbsolute()) {
                    throw permanent("redirect to a non-absolute URL");
                }
                current = next;
                continue;
            }
            if (status / 100 != 2) {
                if (status == 429 || status >= 500) {
                    throw new ExternalImageFetchException(
                            ExternalImageFetchException.Category.TRANSIENT,
                            "external image endpoint unavailable (HTTP " + status + ")");
                }
                throw permanent("external image rejected (HTTP " + status + ")");
            }
            return readBody(response);
        }
        throw permanent("external image exceeded the redirect limit");
    }

    private HttpResponse<InputStream> fetchOnce(URI uri, Instant deadline) {
        validateUri(uri);
        if (Instant.now().isAfter(deadline)) {
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.TRANSIENT,
                    "external image download exceeded the time limit");
        }
        Duration remaining = Duration.between(Instant.now(), deadline);
        Duration requestTimeout = remaining.compareTo(Duration.ofSeconds(30)) > 0
                ? Duration.ofSeconds(30) : remaining;
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", "kwiki-indexer/1.0 (multimodal)")
                .header("Accept", "image/png, image/jpeg, image/gif, image/webp")
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.TRANSIENT,
                    "external image fetch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.TRANSIENT,
                    "external image fetch cancelled");
        } catch (IOException e) {
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.TRANSIENT,
                    "external image fetch transport failure");
        }
    }

    /** URL 策略 + DNS 地址策略；任何一跳失败都立即拒绝。 */
    private void validateUri(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme)) {
            deny("scheme", "external image must use https");
        }
        if (uri.getRawUserInfo() != null || uri.getUserInfo() != null) {
            deny("userinfo", "external image must not carry user info");
        }
        int port = uri.getPort();
        if (port != -1 && !allowedPorts.contains(port)) {
            deny("port", "external image port is not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            deny("host", "external image host is missing");
        }
        try {
            // 解析所有地址并逐一校验；只信任公网地址
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!addressPolicy.test(address)) {
                    deny("address", "external image resolves to a denied network range");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.TRANSIENT,
                    "external image host could not be resolved");
        }
    }

    private static boolean isRedirect(int status) {
        return status >= 300 && status < 400;
    }

    private DownloadedImage readBody(HttpResponse<InputStream> response) {
        byte[] bytes;
        try (InputStream body = response.body()) {
            bytes = readBounded(body, maxExternalImageBytes + 1);
        } catch (IOException e) {
            throw new ExternalImageFetchException(ExternalImageFetchException.Category.TRANSIENT,
                    "external image body could not be read");
        }
        if (bytes.length > maxExternalImageBytes) {
            throw permanent("external image exceeds the byte limit");
        }
        if (bytes.length == 0) {
            throw permanent("external image returned no bytes");
        }
        String sniffed = MediaContentSniffer.sniffImageType(bytes).orElse(null);
        if (sniffed == null || !SUPPORTED_TYPES.contains(sniffed)) {
            throw permanent("external image bytes are not a supported image type");
        }
        BufferedImage decoded = decodeBounded(bytes);
        return new DownloadedImage(bytes,
                "image/jpg".equals(sniffed) ? "image/jpeg" : sniffed,
                decoded.getWidth(), decoded.getHeight());
    }

    private BufferedImage decodeBounded(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) {
                throw permanent("external image bytes could not be decoded");
            }
            if ((long) image.getWidth() * image.getHeight() > maxImagePixels) {
                throw permanent("external image exceeds the pixel limit");
            }
            return image;
        } catch (ExternalImageFetchException e) {
            throw e;
        } catch (IOException e) {
            throw permanent("external image bytes could not be decoded");
        }
    }

    /** 有界读取：超限立即停止（最多多读 1 字节以区分超限）。 */
    private static byte[] readBounded(InputStream body, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = body.read(buffer)) >= 0) {
            total += read;
            if (total > limit) {
                return new byte[limit + 1]; // 超限哨兵
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw.length == 4) {
            int a = raw[0] & 0xFF;
            int b = raw[1] & 0xFF;
            // 100.64.0.0/10 CGNAT（含云元数据服务常见网段邻域）
            if (a == 100 && b >= 64 && b <= 127) return false;
            // 192.0.0.0/24、192.0.2.0/24、198.18.0.0/15、198.51.100.0/24、203.0.113.0/24
            if (a == 192 && (b == 0)) return false;
            if (a == 192 && b == 2) return false;
            if (a == 198 && (b == 18 || b == 19)) return false;
            if (a == 198 && b == 51) return false;
            if (a == 203 && b == 0) return false;
            // 240.0.0.0/4 保留（含 255.255.255.255 广播）
            if ((a & 0xF0) == 0xF0) return false;
            return true;
        }
        if (raw.length == 16) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // fc00::/7 唯一本地、fe80::/10 链路本地（上面已覆盖部分）、64:ff9b::/96 NAT64
            if ((b0 & 0xFE) == 0xFC) return false;
            if (b0 == 0xFE && (b1 & 0xC0) == 0x80) return false;
            if (b0 == 0x00 && b1 == 0x64) return false;
            // ::/128 未指定与 ::ffff: 映射 v4 已由 InetAddress 归一化/上面拒绝
            return true;
        }
        return false;
    }

    private ExternalImageFetchException permanent(String message) {
        return new ExternalImageFetchException(ExternalImageFetchException.Category.PERMANENT,
                message);
    }

    private void deny(String reason, String message) {
        metrics.ssrfDenied(reason);
        throw permanent(message);
    }

    /** 供配置装配校验端口白名单非空。 */
    public static Set<Integer> normalizePorts(Set<Integer> ports) {
        if (ports == null || ports.isEmpty()) {
            return Set.of(443);
        }
        return ports;
    }
}
