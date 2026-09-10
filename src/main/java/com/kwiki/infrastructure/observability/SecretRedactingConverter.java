package com.kwiki.infrastructure.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback 的 %redactedMsg 转换器（converter）：每条格式化消息在到达任何
 * appender 之前都会经过 {@link SecretRedaction}，因此凭据值
 * 无论调用位置如何都不会泄漏到捕获的日志中。
 */
public class SecretRedactingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedaction.redact(event.getFormattedMessage());
    }
}
