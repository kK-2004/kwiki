package com.kwiki.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT signing settings. The secret is a credential: it must come from the
 * KWIKI_JWT_SECRET environment variable and must never have a committed default.
 */
@ConfigurationProperties(prefix = "kwiki.security")
@Validated
public record SecurityProperties(
        @NotBlank
        @Size(min = 32, max = 512, message = "HS256 secret must be at least 32 characters")
        String jwtSecret,
        @NotNull @DefaultValue("12h") Duration tokenTtl) {
}
