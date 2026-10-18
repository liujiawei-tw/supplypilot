package com.supplypilot.security;

import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Synchronously asks the mTLS Host authority to verify every baseline login. */
@Component
@Profile("demo & !prod")
public class DemoBaselineCredentialAuthenticator {
    private static final Set<String> BASELINE_USERS = Set.of("admin", "manager", "buyer", "warehouse", "planner");
    private final UserRepository users;
    private final DemoHostCredentialVerifier verifier;

    public DemoBaselineCredentialAuthenticator(UserRepository users, DemoHostCredentialVerifier verifier) {
        this.users = users;
        this.verifier = verifier;
    }

    public boolean handles(String username) {
        return username != null && BASELINE_USERS.contains(username.toLowerCase(Locale.ROOT));
    }

    public Authentication authenticate(String username, String suppliedPassword) {
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        User user = users.findByUsername(normalizedUsername)
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        if (suppliedPassword == null || !verifier.verify(normalizedUsername, suppliedPassword)) {
            throw new BadCredentialsException("Authentication failed");
        }
        SecurityUser principal = new SecurityUser(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getRole(), true);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

}
