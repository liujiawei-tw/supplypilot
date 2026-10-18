package com.supplypilot.security;

import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Reads the synchronized Demo password file for every baseline-account login. */
@Component
@Profile("demo & !prod")
public class DemoBaselineCredentialAuthenticator {
    private static final Set<String> BASELINE_USERS = Set.of("admin", "manager", "buyer", "warehouse", "planner");

    private final UserRepository users;
    private final Path passwordFile;

    public DemoBaselineCredentialAuthenticator(
            UserRepository users,
            @Value("${supplypilot.demo.password-file:/run/supplypilot-demo-credentials/password}") String passwordFile
    ) {
        this.users = users;
        this.passwordFile = Path.of(passwordFile);
    }

    public boolean handles(String username) {
        return username != null && BASELINE_USERS.contains(username.toLowerCase(Locale.ROOT));
    }

    public Authentication authenticate(String username, String suppliedPassword) {
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        User user = users.findByUsername(normalizedUsername)
                .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed"));
        String configuredPassword = readPassword();
        if (suppliedPassword == null || !MessageDigest.isEqual(
                configuredPassword.getBytes(StandardCharsets.UTF_8), suppliedPassword.getBytes(StandardCharsets.UTF_8))) {
            throw new BadCredentialsException("Authentication failed");
        }
        SecurityUser principal = new SecurityUser(user.getId(), user.getUsername(), user.getPasswordHash(),
                user.getRole(), true);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    private String readPassword() {
        try {
            if (!Files.isRegularFile(passwordFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException();
            }
            String password = Files.readString(passwordFile, StandardCharsets.UTF_8);
            if (password.isBlank() || password.length() < 6 || password.length() > 100
                    || password.contains("\n") || password.contains("\r")) {
                throw new IllegalArgumentException();
            }
            return password;
        } catch (IOException | RuntimeException exception) {
            // Avoid logging path, file contents, or credentials.
            throw new BadCredentialsException("Demo credentials are unavailable", exception);
        }
    }
}
