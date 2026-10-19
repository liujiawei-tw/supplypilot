package com.supplypilot.security;

import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Authenticates Demo baseline accounts against the Demo profile's configured password. */
@Component
@Profile("demo & !prod")
public class DemoBaselineCredentialAuthenticator {
    private static final Set<String> BASELINE_USERS = Set.of("admin", "manager", "buyer", "warehouse", "planner");
    private final UserRepository users;
    private final byte[] configuredPassword;

    public DemoBaselineCredentialAuthenticator(UserRepository users,
            @Value("${supplypilot.demo.account-password}") String configuredPassword) {
        this.users = users;
        if (configuredPassword == null || configuredPassword.length() < 6 || configuredPassword.length() > 100) {
            throw new IllegalStateException("supplypilot.demo.account-password must contain 6 to 100 characters");
        }
        this.configuredPassword = configuredPassword.getBytes(StandardCharsets.UTF_8);
    }

    public boolean handles(String username) {
        return username != null && BASELINE_USERS.contains(username.toLowerCase(Locale.ROOT));
    }

    public Authentication authenticate(String username, String suppliedPassword) {
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        User user = users.findByUsername(normalizedUsername)
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (suppliedPassword == null || !MessageDigest.isEqual(configuredPassword,
                suppliedPassword.getBytes(StandardCharsets.UTF_8))) {
            throw new BadCredentialsException("Authentication failed");
        }
        SecurityUser principal = new SecurityUser(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getRole(), true);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

}
