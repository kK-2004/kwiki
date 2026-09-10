package com.kwiki.infrastructure.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证明授权头、API key、密码以及已签名下载链接的
 * 查询参数在消息文本或查询串中经脱敏后绝不残留，并且
 * logback 转换器对真实日志事件施加同样的保证。
 */
class SecretRedactionTest {

    @Test
    void authorizationHeaderValuesAreRedacted() {
        String original = "outbound call failed Authorization: Bearer sk-abc123def456ghi789xyz";
        String redacted = SecretRedaction.redact(original);
        assertThat(redacted).doesNotContain("sk-abc123def456ghi789xyz");
        assertThat(redacted).contains("Authorization: Bearer " + SecretRedaction.REDACTED);
    }

    @Test
    void apiKeyAndPasswordValuesAreRedacted() {
        String original = "request body api_key=\"sk-987654321abcdef\" password=hunter2secret";
        String redacted = SecretRedaction.redact(original);
        assertThat(redacted).doesNotContain("sk-987654321abcdef").doesNotContain("hunter2secret");
        assertThat(redacted).contains("api_key=" + SecretRedaction.REDACTED);
        assertThat(redacted).contains("password=" + SecretRedaction.REDACTED);
    }

    @Test
    void signedDownloadQueryParametersAreRedacted() {
        String original = "GET https://storage.internal/objects/a.docx"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=deadbeef12345678"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20260816%2Fus-east-1%2Fs3%2Faws4_request";
        String redacted = SecretRedaction.redact(original);
        assertThat(redacted)
                .doesNotContain("deadbeef12345678")
                .doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(redacted)
                .contains("X-Amz-Signature=" + SecretRedaction.REDACTED)
                .contains("X-Amz-Credential=" + SecretRedaction.REDACTED);
    }

    @Test
    void contentCenterAppTokenAndSignatureValuesAreRedacted() {
        String original = "content-center call failed app-token=kapp-abcdef123456 "
                + "url=https://storage.internal/f/42?sig=9f8e7d6c5b4a";
        String redacted = SecretRedaction.redact(original);
        assertThat(redacted)
                .doesNotContain("kapp-abcdef123456")
                .doesNotContain("9f8e7d6c5b4a");
        assertThat(redacted)
                .contains("app-token=" + SecretRedaction.REDACTED)
                .contains("sig=" + SecretRedaction.REDACTED);
    }

    @Test
    void redactQueryStringPreservesNonSensitiveParameters() {
        String original = "q=wiki&page=2&X-Amz-Signature=abc123456789012345";
        String redacted = SecretRedaction.redactQueryString(original);
        assertThat(redacted)
                .contains("q=wiki")
                .contains("page=2")
                .contains("X-Amz-Signature=" + SecretRedaction.REDACTED)
                .doesNotContain("abc123456789012345");
    }

    @Test
    void plainTextWithoutSecretsPassesThroughUnchanged() {
        String original = "page 42 published by user kk with note \"weekly report\"";
        assertThat(SecretRedaction.redact(original)).isEqualTo(original);
    }

    @Test
    void logbackConverterRedactsRealLoggingEvents() {
        Logger logger = (Logger) LoggerFactory.getLogger("kwiki.redaction.test");
        LoggingEvent event = new LoggingEvent(
                "com.kwiki.Test", logger, Level.WARN,
                "provider call rejected Authorization: Bearer sk-verysecretvalue42", null, null);
        String converted = new SecretRedactingConverter().convert(event);
        assertThat(converted).doesNotContain("sk-verysecretvalue42");
        assertThat(converted).contains(SecretRedaction.REDACTED);
    }
}
