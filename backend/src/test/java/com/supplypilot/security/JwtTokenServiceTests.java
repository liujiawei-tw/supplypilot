package com.supplypilot.security;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import com.supplypilot.user.domain.UserRole;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    private byte[] getSigningKeyBytes() {
        return Base64.getDecoder().decode(TEST_SECRET);
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
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Authentication token is required");
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(""))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Authentication token is required");
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken("   "))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Authentication token is required");
    }

    @Test
    @DisplayName("Rejects expired token")
    void testRejectExpiredToken() {
        AuthenticatedUser user = new AuthenticatedUser(10L, "buyer01", UserRole.PROCUREMENT_STAFF);
        String token = jwtTokenService.generateToken(user);

        // Move clock beyond expiration plus allowed clock skew (3600 + 31 seconds)
        Clock futureClock = Clock.fixed(NOW.plusSeconds(EXPIRATION_SECONDS + 31), ZoneOffset.UTC);
        JwtTokenService verifyingService = new JwtTokenService(jwtProperties, futureClock);

        assertThatThrownBy(() -> verifyingService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Authentication token has expired");
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
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
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
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects malformed token string")
    void testRejectMalformedToken() {
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken("not.a.valid.jwt.token"))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects token with missing iat claim")
    void testRejectMissingIat() {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());
        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", "PROCUREMENT_STAFF")
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects token with missing exp claim")
    void testRejectMissingExp() {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());
        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects token where exp is equal to or before iat")
    void testRejectExpNotAfterIat() {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());

        // exp == iat
        String tokenEqual = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tokenEqual))
                .isInstanceOf(JwtAuthenticationException.class);

        // exp < iat
        String tokenBefore = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.minusSeconds(100)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tokenBefore))
                .isInstanceOf(JwtAuthenticationException.class);
    }

    @Test
    @DisplayName("Rejects token with iat unreasonably in the future beyond allowed clock skew")
    void testRejectFutureIatBeyondClockSkew() {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());
        Instant futureIat = NOW.plusSeconds(300); // 5 minutes in future

        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(futureIat))
                .expiration(Date.from(futureIat.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, -100L})
    @DisplayName("Rejects token with non-positive userId (0, negative)")
    void testRejectNonPositiveUserId(long nonPositiveId) {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());
        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", nonPositiveId)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects token with floating point numeric userId (e.g. 10.9)")
    void testRejectFloatingPointUserId() {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());
        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10.9)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects token with userId exceeding Long range")
    void testRejectUserIdExceedingLong() {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());
        BigInteger tooBig = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(10));

        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", tooBig.toString())
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "10L", "10.5", "10 20", ""})
    @DisplayName("Rejects token with non-numeric or malformed string userId")
    void testRejectNonNumericStringUserId(String invalidUserIdString) {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());
        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", invalidUserIdString)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects token with numeric string userId rather than coercing it to a Long")
    void testRejectNumericStringUserId() {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());
        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", "10")
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects token with role that is not a string or is invalid enum")
    void testRejectInvalidRole() {
        var key = Keys.hmacShaKeyFor(getSigningKeyBytes());

        // Role as integer
        String tokenNumberRole = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", 123)
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tokenNumberRole))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");

        // Role as invalid enum name
        String tokenInvalidEnum = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", "SUPER_ADMIN")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken(tokenInvalidEnum))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Rejects invalid Base64 secret format on startup")
    void testRejectNonBase64Secret() {
        JwtProperties nonBase64Props = new JwtProperties("not-valid-base64!@#$%", EXPIRATION_SECONDS);
        assertThatThrownBy(() -> new JwtTokenService(nonBase64Props, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid Base64-encoded string");
    }

    @Test
    @DisplayName("Rejects secret with insufficient length (< 256 bits) even if valid Base64")
    void testRejectWeakBase64Secret() {
        // "dGVzdA==" is "test" (4 bytes = 32 bits)
        JwtProperties weakProps = new JwtProperties("dGVzdA==", EXPIRATION_SECONDS);
        assertThatThrownBy(() -> new JwtTokenService(weakProps, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 256 bits");
    }

    @Test
    @DisplayName("Rejects a non-positive JWT TTL")
    void testRejectNonPositiveExpiration() {
        JwtProperties invalidTtlProps = new JwtProperties(TEST_SECRET, 0);

        assertThatThrownBy(() -> new JwtTokenService(invalidTtlProps, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive integer");
    }

    @Test
    @DisplayName("Rejects a correctly signed token using an unexpected HMAC algorithm")
    void testRejectUnexpectedAlgorithm() {
        byte[] longKeyBytes = new byte[64];
        java.util.Arrays.fill(longKeyBytes, (byte) 7);
        JwtProperties hs512Properties = new JwtProperties(Base64.getEncoder().encodeToString(longKeyBytes), EXPIRATION_SECONDS);
        JwtTokenService hs512Service = new JwtTokenService(hs512Properties, clock);
        String token = Jwts.builder()
                .subject("buyer01")
                .claim("userId", 10L)
                .claim("role", "PROCUREMENT_STAFF")
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(longKeyBytes), Jwts.SIG.HS512)
                .compact();

        assertThatThrownBy(() -> hs512Service.parseAndValidateToken(token))
                .isInstanceOf(JwtAuthenticationException.class)
                .hasMessage("Invalid authentication token");
    }

    @Test
    @DisplayName("Error messages never leak token or secret contents")
    void testErrorMessagesDoNotLeakSensitiveData() {
        assertThatThrownBy(() -> jwtTokenService.parseAndValidateToken("some.tampered.secrettoken12345"))
                .isInstanceOf(JwtAuthenticationException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain("secrettoken12345");
                    assertThat(e.getMessage()).doesNotContain(TEST_SECRET);
                });
    }
}
