package com.supplypilot.security;

import java.util.Collection;
import java.util.List;

import com.supplypilot.user.domain.UserRole;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Immutable Security Principal stored in the {@link org.springframework.security.core.context.SecurityContext}.
 *
 * <p>This type is intentionally decoupled from the JPA {@code User} entity to avoid
 * lazy-loading issues, entity lifecycle coupling, and serialisation problems.
 * It carries no password or password hash.
 */
public final class AuthenticatedUser implements UserDetails {

    private final Long id;
    private final String username;
    private final UserRole role;
    private final boolean mustChangePassword;

    public AuthenticatedUser(Long id, String username, UserRole role) {
        this(id, username, role, false);
    }

    public AuthenticatedUser(Long id, String username, UserRole role, boolean mustChangePassword) {
        this.id = id;
        this.username = username;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
    }

    public Long getId() {
        return id;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    /**
     * Returns a single authority derived from the user's role.
     * Spring Security convention uses {@code ROLE_} prefix.
     */
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (mustChangePassword) {
            return List.of(new SimpleGrantedAuthority("ROLE_PASSWORD_CHANGE_REQUIRED"));
        }
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /** Returns {@code null} — password is never carried in the security principal. */
    @Override
    public String getPassword() {
        return null;
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
        return true;
    }
}
