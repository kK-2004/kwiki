package com.kwiki.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/** Configures the display time zone used for JSON serialization of timestamps. */
@ConfigurationProperties(prefix = "kwiki.time")
public class JacksonTimeProperties {

    /** Application display time zone; defaults to Asia/Shanghai. */
    private String displayZone = "Asia/Shanghai";

    public String getDisplayZone() {
        return displayZone;
    }

    public void setDisplayZone(String displayZone) {
        this.displayZone = displayZone;
    }

    public ZoneId displayZone() {
        return ZoneId.of(displayZone);
    }
}
