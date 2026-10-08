package com.supplypilot.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import com.supplypilot.user.domain.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenServiceTests {

    private static final String TEST_SECRET = "dGVzdC1zZWNyZXQtZm9yLXVuaXQtdGVzdHMtb25seS1hdC1sZWFzdC0yNTYtYml0cw==";
    private static final long EXPIRATION_SECONDS = 3600;
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    private Clock clock;
    private JwtProperties jwtProperties;
    private JwtTokenService jwtTokenService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        jwtProperties = new JwtProperties(TEST_SECRET, EXPIRATION_SECONDS);
        jwtTokenService = new JwtTokenService(jwtProperties, clock);
    }

    @Test
    @DisplayName("Successfully generates and validates HS256 JWT")
    void testGenerateAndValidateTokenSuccess() {
        AuthenticatedUser user = new AuthenticatedUser(10L, "buyer01", UserRole.PROCUREMENT_STAFF);

        String token = jwtTokenService.generateToken(user);
        assertThat(token).isNotBlank();

        AuthenticatedUser parsedUser = jwtTokenService.parseAndValidateToken(token);
        assertThat(parsedUser.getId()).isEqualTo(10L);
        assertThat(parsedUser.getUsername()).isEqualTo("buyer01");
        assertThat(parsedUser.getRole()).isEqualTo(UserRole.PROCUREMENT_STAFF);
    }

    @Test
    @DisplayName("Rejects null or blank token")
    void testRejectNullOrBlankToken() {
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(null))
                .isInstanceOf(JwtAuthenticationException.class);
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(""))
                .isInstanceOf(JwtAuthenticationException.class);
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken("   "))
                .isInstanceOf(JwtAuthenticationException.class);
    }

    @Test
    @DisplayName("Rejects expired token")
    void testRejectExpiredToken() {
        AuthenticatedUser user = new AuthenticatedUser(10L, "buyer01", UserRole.PROCUREMENT_STAFF);
        String token = jwtTokenService.generateToken(user);

        // Move clock 3601 seconds into future
        Clock futureClock = Clock.fixed(NOW.plusSeconds(3601), ZoneOffset.UTC);
        JwtTokenService verifyingService = new JwtTokenService(jwtProperties, futureClock);

        assertThatThrownBy(() -> verifyingService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("Rejects tampered token payload or signature")
    void testRejectTamperedToken() {
        AuthenticatedUser user = new AuthenticatedUser(10L, "buyer01", UserRole.PROCUREMENT_STAFF);
        String token = jwtTokenService.generateToken(user);

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        // Tamper with payload
        String tamperedPayload = Base64.getUrlEncoder().encodeToString("{\"sub\":\"hacker\"}".getBytes());
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tamperedToken))
                .isInstanceOf(JwtAuthenticationException.class);
    }

    @Test
    @DisplayName("Rejects token signed with different secret")
    void testRejectInvalidSignature() {
        AuthenticatedUser user = new AuthenticatedUser(10L, "buyer01", UserRole.PROCUREMENT_STAFF);
        String token = jwtTokenService.generateToken(user);

        // Another service with different secret key
        String differentSecret = "YW5vdGhlci1kaWZmZXJlbnQtc2VjcmV0LWtleS1hdC1sZWFzdC0yNTYtYml0cyE=";
        JwtTokenService otherService = new JwtTokenService(
                new JwtProperties(differentSecret, EXPIRATION_SECONDS),
                clock
        );

        assertThatThrownBy(() -> otherService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class);
    }

    @Test
    @DisplayName("Rejects malformed token string")
    void testRejectMalformedToken() {
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken("not.a.valid.jwt.token"))
                .isInstanceOf(JwtAuthenticationException.class);
    }

    @Test
    @DisplayName("Rejects token with missing required claims")
    void testRejectMissingRequiredClaims() {
        byte[] keyBytes = Base64.getDecoder().decode(TEST_SECRET);
        var key = Keys.hmacShaKeyFor(keyBytes);

        // Missing userId
        String tokenNoUserId = Jwts.builder()
                .subject("buyer01")
                .claim("role", "PROCUREMENT_STAFF")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tokenNoUserId))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessageContaining("userId");

        // Missing role
        String tokenNoRole = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tokenNoRole))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessageContaining("role");

        // Invalid role enum value
        String tokenInvalidRole = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", "SUPER_GOD")
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tokenInvalidRole))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("Rejects secret with insufficient length (< 256 bits)")
    void testRejectWeakSecret() {
        JwtProperties weakProps = new JwtProperties("weak-secret-too-short", EXPIRATION_SECONDS);
        assertThatThrownBy(() -> new JwtTokenService(weakProps, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 256 bits");
    }
}
