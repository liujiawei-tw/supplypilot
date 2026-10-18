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
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Reads the mounted Demo environment file for every baseline-account login. */
@Component
@Profile("demo & !prod")
public class DemoBaselineCredentialAuthenticator {
    private static final Set<String> BASELINE_USERS = Set.of("admin", "manager", "buyer", "warehouse", "planner");

    private final UserRepository users;
    private final Path environmentFile;

    public DemoBaselineCredentialAuthenticator(
            UserRepository users,
            @Value("${supplypilot.demo.password-file:/run/secrets/supplypilot-demo-env}") String environmentFile
    ) {
        this.users = users;
        this.environmentFile = Path.of(environmentFile);
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
            if (!Files.isRegularFile(environmentFile)) throw new IllegalArgumentException();
            String contents = Files.readString(environmentFile, StandardCharsets.UTF_8);
            var values = new HashMap<String, String>();
            for (String line : contents.split("\\R", -1)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                int separator = trimmed.indexOf('=');
                if (separator <= 0 || !trimmed.substring(0, separator).matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException();
                }
                String key = trimmed.substring(0, separator);
                String value = trimmed.substring(separator + 1).trim();
                if (value.startsWith("\"") || value.startsWith("'")) {
                    char quote = value.charAt(0);
                    if (value.length() < 2 || value.charAt(value.length() - 1) != quote) {
                        throw new IllegalArgumentException();
                    }
                } else if (value.endsWith("\"") || value.endsWith("'")) {
                    throw new IllegalArgumentException();
                }
                if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException();
            }
            String password = values.get("DEMO_ACCOUNT_PASSWORD");
            if (password != null && password.length() >= 2
                    && ((password.startsWith("\"") && password.endsWith("\""))
                    || (password.startsWith("'") && password.endsWith("'")))) {
                password = password.substring(1, password.length() - 1);
            }
            if (password == null || password.isBlank() || password.length() < 6 || password.length() > 100
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
