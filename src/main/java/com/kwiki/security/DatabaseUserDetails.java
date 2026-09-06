package com.kwiki.security;

import com.kwiki.wiki.domain.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/** Spring Security view of an app_user row; the password is always a hash. */
public final class DatabaseUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final boolean admin;
    private final boolean active;

    public DatabaseUserDetails(AppUser user) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.passwordHash = user.getPasswordHash();
        this.admin = user.isAdmin();
        this.active = user.isActive();
    }

    public CurrentUser currentUser() {
        return new CurrentUser(id, username, admin);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return admin
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        // A legacy row without credentials can never authenticate successfully.
        return passwordHash == null ? "" : passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}
