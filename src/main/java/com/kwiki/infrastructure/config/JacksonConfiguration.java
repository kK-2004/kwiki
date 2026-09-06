package com.kwiki.infrastructure.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Global Jackson time handling.
 *
 * <p>Timestamps are persisted as UTC {@link Instant}s; on the wire (JSON responses
 * and request bodies) they are serialized in the application display time zone so
 * consumers see a stable local wall-clock time. The display zone is configurable
 * via {@code kwiki.time.display-zone} and defaults to Asia/Shanghai.
 */
@Configuration
public class JacksonConfiguration {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer kwikiJacksonCustomizer(
            JacksonTimeProperties properties) {
        ZoneId displayZone = properties.displayZone();
        return builder -> builder
                .serializerByType(Instant.class, new InstantSerializer(displayZone))
                .deserializerByType(Instant.class, new InstantDeserializer(displayZone));
    }

    /** Serializes an {@link Instant} as display-zone local wall-clock text. */
    private static final class InstantSerializer extends JsonSerializer<Instant> {

        private final DateTimeFormatter formatter;
        private final ZoneId zone;

        private InstantSerializer(ZoneId zone) {
            this.zone = zone;
            this.formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        }

        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.atZone(zone).format(formatter));
        }

        @Override
        public Class<Instant> handledType() {
            return Instant.class;
        }
    }

    /** Parses offset-bearing or display-zone local wall-clock text into an {@link Instant}. */
    private static final class InstantDeserializer extends JsonDeserializer<Instant> {

        private final ZoneId zone;

        private InstantDeserializer(ZoneId zone) {
            this.zone = zone;
        }

        @Override
        public Instant deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            String text = p.getValueAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            String trimmed = text.trim();
            if (hasOffset(trimmed)) {
                // Offset-bearing input (e.g. ISO_INSTANT "...Z" or "+08:00").
                try {
                    return Instant.parse(trimmed);
                } catch (RuntimeException e) {
                    return OffsetDateTime.parse(trimmed,
                            DateTimeFormatter.ISO_DATE_TIME).toInstant();
                }
            }
            // Bare local wall-clock text: interpret it in the display zone.
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant();
        }

        private static boolean hasOffset(String text) {
            return text.endsWith("Z")
                    || text.indexOf('+') > 10
                    || text.indexOf('-', 10) > 0;
        }
    }
}
