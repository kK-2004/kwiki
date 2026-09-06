package com.kwiki.infrastructure.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback %redactedMsg converter: every formatted message passes through
 * {@link SecretRedaction} before it reaches any appender, so credential values
 * cannot leak into captured logs regardless of call site.
 */
public class SecretRedactingConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretRedaction.redact(event.getFormattedMessage());
    }
}
