package com.kwiki.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT 签名配置。密钥属于凭据：必须来自
 * KWIKI_JWT_SECRET 环境变量，绝不能提交默认值。
 */
@ConfigurationProperties(prefix = "kwiki.security")
@Validated
public record SecurityProperties(
        @NotBlank
        @Size(min = 32, max = 512, message = "HS256 secret must be at least 32 characters")
        String jwtSecret,
        @NotNull @DefaultValue("7d") Duration tokenTtl,
        @NotNull @DefaultValue("5d") Duration tokenRenewalWindow) {
}
