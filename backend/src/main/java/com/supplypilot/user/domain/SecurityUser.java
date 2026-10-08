package com.supplypilot.user.domain;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Internal UserDetails implementation used strictly during AuthenticationManager authentication.
 *
 * <p>Carries password hash and active status needed for BCrypt verification.
 * Note: Once authentication succeeds, the SecurityContext principal is replaced
 * with {@link com.supplypilot.security.AuthenticatedUser}, so this class is never exposed
 * as the long-term SecurityContext principal.
 */
public final class SecurityUser implements UserDetails {

    private final Long id;
    private final String username;
    private final String passwordHash;
    private final UserRole role;
    private final boolean enabled;

    public SecurityUser(Long id, String username, String passwordHash, UserRole role, boolean enabled) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = enabled;
    }

    public Long getId() {
        return id;
    }

    public UserRole getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
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
        return enabled;
    }
}
