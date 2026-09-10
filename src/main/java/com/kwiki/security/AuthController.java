package com.kwiki.security;

import com.kk2004.common.response.TransDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.time.Instant;

/** 用户名/密码登录边界；后续所有 API 调用均使用 JWT。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokens;
    private final SecurityProperties securityProperties;
    private final com.kwiki.wiki.persistence.AppUserRepository users;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenService tokens,
                          SecurityProperties securityProperties,
                          com.kwiki.wiki.persistence.AppUserRepository users,
                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
        this.securityProperties = securityProperties;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record RegisterRequest(@NotBlank String username, @NotBlank String displayName,
                                  @Email String email, @NotBlank String password) {
    }

    public record LoginResponse(String token, String tokenType, long expiresInSeconds,
                                CurrentUser user) {
    }

    public record RefreshResponse(String token, String tokenType, long expiresInSeconds,
                                  boolean renewed, Instant expiresAt, CurrentUser user) {
    }

    @PostMapping("/login")
    ResponseEntity<TransDTO<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            request.username(), request.password()));
            if (!(authentication.getPrincipal() instanceof DatabaseUserDetails details)) {
                throw new AuthenticationServiceException("unexpected authentication principal");
            }
            CurrentUser user = details.currentUser();
            return ResponseEntity.ok(TransDTO.success(new LoginResponse(
                    tokens.issue(user),
                    "Bearer",
                    securityProperties.tokenTtl().toSeconds(),
                    user)));
        } catch (AuthenticationException e) {
            // 使账号是否存在、是否被禁用、密码是否正确三者无法区分。
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(TransDTO.failure(HttpStatus.UNAUTHORIZED.value(), "invalid_credentials"));
        }
    }

    @PostMapping("/register")
    ResponseEntity<TransDTO<LoginResponse>> register(@Valid @RequestBody RegisterRequest request) {
        String username = normalizeUsername(request.username());
        String email = request.email() == null || request.email().isBlank()
                ? null : request.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByUsername(username).isPresent()
                || (email != null && users.findByEmail(email).isPresent())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(TransDTO.failure(HttpStatus.CONFLICT.value(), "account_exists"));
        }
        try {
            com.kwiki.wiki.domain.AppUser saved = users.save(new com.kwiki.wiki.domain.AppUser(
                    username,
                    request.displayName().trim(),
                    email,
                    false,
                    passwordEncoder.encode(request.password())));
            CurrentUser user = new CurrentUser(saved.getId(), saved.getUsername(), false);
            return ResponseEntity.status(HttpStatus.CREATED).body(TransDTO.success(
                    new LoginResponse(tokens.issue(user), "Bearer", securityProperties.tokenTtl().toSeconds(), user)));
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(TransDTO.failure(HttpStatus.CONFLICT.value(), "account_exists"));
        }
    }

    @org.springframework.web.bind.annotation.GetMapping("/me")
    TransDTO<CurrentUser> me(@org.springframework.security.core.annotation.AuthenticationPrincipal CurrentUser user) {
        return TransDTO.success(user);
    }

    @PostMapping("/refresh")
    ResponseEntity<TransDTO<RefreshResponse>> refresh(
            @org.springframework.security.core.annotation.AuthenticationPrincipal CurrentUser user,
            jakarta.servlet.http.HttpServletRequest request) {
        String header = request.getHeader(org.springframework.http.HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(TransDTO.failure(HttpStatus.UNAUTHORIZED.value(), "unauthenticated"));
        }
        JwtTokenService.TokenIdentity identity;
        try {
            identity = tokens.parse(header.substring(7).trim());
        } catch (RuntimeException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(TransDTO.failure(HttpStatus.UNAUTHORIZED.value(), "unauthenticated"));
        }
        String renewed = tokens.renewIfEligible(identity);
        Instant expiresAt = identity.expiresAt();
        if (renewed != null) {
            JwtTokenService.TokenIdentity next = tokens.parse(renewed);
            expiresAt = next.expiresAt();
        }
        return ResponseEntity.ok(TransDTO.success(new RefreshResponse(
                renewed == null ? header.substring(7).trim() : renewed,
                "Bearer", tokens.ttl().toSeconds(), renewed != null, expiresAt, user)));
    }

    private String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
