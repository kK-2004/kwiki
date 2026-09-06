package com.kwiki.security;

/**
 * Authenticated caller resolved from a validated JWT. Immutable value carried as
 * the Spring Security principal for the whole request.
 */
public record CurrentUser(Long id, String username, boolean admin) {

    public boolean isAdmin() {
        return admin;
    }
}
