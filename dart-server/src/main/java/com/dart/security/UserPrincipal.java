package com.dart.security;

import com.dart.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

public class UserPrincipal implements UserDetails {

    @Getter
    private final Long userId;

    private final String username;
    private final String password;
    private final boolean enabled;
    private final boolean accountNonLocked;

    public UserPrincipal(User user) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.enabled = user.getStatus() != User.STATUS_DISABLED;
        this.accountNonLocked = user.getStatus() != User.STATUS_LOCKED;
    }

    @Override
    public String getUsername() { return username; }

    @Override
    public String getPassword() { return password; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public boolean isAccountNonLocked() { return accountNonLocked; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return Collections.emptyList();
    }
}
