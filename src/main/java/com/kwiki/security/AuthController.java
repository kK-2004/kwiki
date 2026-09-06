package com.kwiki.security;

import com.kk2004.common.response.TransDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

/** Username/password login boundary; all subsequent API calls use the JWT. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService tokens;
    private final SecurityProperties securityProperties;

    public AuthController(AuthenticationManager authenticationManager,
                          JwtTokenService tokens,
                          SecurityProperties securityProperties) {
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
        this.securityProperties = securityProperties;
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String tokenType, long expiresInSeconds,
                                CurrentUser user) {
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
            // Keep account existence, disabled state, and password validity indistinguishable.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(TransDTO.failure(HttpStatus.UNAUTHORIZED.value(), "invalid_credentials"));
        }
    }
}
