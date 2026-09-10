package com.kwiki.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/** 配置时间戳 JSON 序列化所使用的展示时区。 */
@ConfigurationProperties(prefix = "kwiki.time")
public class JacksonTimeProperties {

    /** 应用显示时区；默认值为 Asia/Shanghai。 */
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
