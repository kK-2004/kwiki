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
 * 全局 Jackson 时间处理。
 *
 * <p>时间戳以 UTC {@link Instant} 持久化；在传输层（JSON 响应
 * 与请求体）则按应用的展示时区序列化，以便
 * 消费方看到稳定的本地墙上时钟时间。展示时区可通过
 * {@code kwiki.time.display-zone} 配置，默认值为 Asia/Shanghai。
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

    /** 将 {@link Instant} 序列化为展示时区的本地墙上时钟文本。 */
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

    /** 将带偏移量或展示时区本地墙上时钟的文本解析为 {@link Instant}。 */
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
                // 带偏移量的输入（如 ISO_INSTANT 的 "...Z" 或 "+08:00"）。
                try {
                    return Instant.parse(trimmed);
                } catch (RuntimeException e) {
                    return OffsetDateTime.parse(trimmed,
                            DateTimeFormatter.ISO_DATE_TIME).toInstant();
                }
            }
            // 不带时区的本地墙上时钟文本：按展示时区解释。
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
