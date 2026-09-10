package com.kwiki.security;

import com.kwiki.wiki.persistence.AppUserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import java.util.Locale;

/** 从 app_user 表加载登录凭据，而非 Boot 自动生成的用户。 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return users.findByUsername(normalized)
                .map(DatabaseUserDetails::new)
                .orElseThrow(() -> new UsernameNotFoundException("user not found"));
    }
}
