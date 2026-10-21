package com.supplypilot.security;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

import javax.crypto.SecretKey;

import com.supplypilot.user.domain.UserRole;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.io.DecodingException;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for issuing and validating HS256 JWT access tokens.
 *
 * <p>Requirements:
 * <ul>
 *   <li>Algorithm: HS256 only.</li>
 *   <li>Secret key length: At least 256 bits (32 bytes), Base64 encoded.</li>
 *   <li>Claims: sub (username), userId (Long), role (String), iat, exp.</li>
 *   <li>Clock: Injected Clock for deterministic testing.</li>
 * </ul>
 */
@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    /** Allowed clock skew in seconds for clock drift tolerance between systems. */
    public static final long ALLOWED_CLOCK_SKEW_SECONDS = 30L;

    private final SecretKey signingKey;
    private final long expirationSeconds;
    private final Clock clock;

    public JwtTokenService(JwtProperties jwtProperties, Clock clock) {
        Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
        Objects.requireNonNull(jwtProperties.secret(), "jwtProperties.secret must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.expirationSeconds = jwtProperties.expirationSeconds();
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("JWT expiration-seconds must be a positive integer.");
        }
        this.signingKey = initSigningKey(jwtProperties.secret());
    }

    private SecretKey initSigningKey(String rawSecret) {
        if (rawSecret == null || rawSecret.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT secret cannot be null or empty.");
        }

        byte[] keyBytes;
        try {
            // Must strictly be Base64-encoded
            keyBytes = Decoders.BASE64.decode(rawSecret.trim());
        } catch (DecodingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "JWT secret must be a valid Base64-encoded string.", e
            );
        }

        // HS256 requires at least 256 bits (32 bytes)
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret key must be at least 256 bits (32 bytes) for HS256. Found " + (keyBytes.length * 8) + " bits."
            );
        }

        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Issues an HS256 JWT access token for an authenticated user.
     *
     * @param user Authenticated user principal
     * @return Signed compact JWT string
     */
    public String generateToken(AuthenticatedUser user) {
        Objects.requireNonNull(user, "User must not be null");
        Instant now = Instant.now(clock);
        Instant expiration = now.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role", user.getRole().name())
                .claim("mustChangePassword", user.isMustChangePassword())
                .claim("canManageUsers", user.canManageUsers())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiration))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validates a JWT string and extracts the {@link AuthenticatedUser} principal.
     *
     * @param token Compact JWT string
     * @return Validated AuthenticatedUser principal
     * @throws JwtAuthenticationException if validation fails for any reason
     */
    public AuthenticatedUser parseAndValidateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtAuthenticationException("Authentication token is required");
        }

        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(Instant.now(clock)))
                    .clockSkewSeconds(ALLOWED_CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(token.trim());

            // Validate Algorithm: Must be HS256 explicitly
            String algorithm = jws.getHeader().getAlgorithm();
            if (!"HS256".equalsIgnoreCase(algorithm)) {
                log.warn("Rejected JWT with unexpected algorithm: {}", algorithm);
                throw new JwtAuthenticationException("Invalid authentication token");
            }

            Claims claims = jws.getPayload();

            // Validate Subject
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                log.debug("JWT subject claim is missing or empty");
                throw new JwtAuthenticationException("Invalid authentication token");
            }

            // Validate iat (issuedAt)
            Date issuedAt = claims.getIssuedAt();
            if (issuedAt == null) {
                log.debug("JWT iat claim is missing");
                throw new JwtAuthenticationException("Invalid authentication token");
            }

            // Validate exp (expiration)
            Date expiration = claims.getExpiration();
            if (expiration == null) {
                log.debug("JWT exp claim is missing");
                throw new JwtAuthenticationException("Invalid authentication token");
            }

            // Validate temporal order: exp must be strictly after iat
            if (!expiration.after(issuedAt)) {
                log.debug("JWT exp ({}) is not after iat ({})", expiration, issuedAt);
                throw new JwtAuthenticationException("Invalid authentication token");
            }

            // Validate against excessive future issuedAt beyond allowed clock skew
            Instant now = Instant.now(clock);
            if (issuedAt.toInstant().isAfter(now.plusSeconds(ALLOWED_CLOCK_SKEW_SECONDS))) {
                log.debug("JWT issued in the future beyond allowed clock skew: iat={}, now={}", issuedAt, now);
                throw new JwtAuthenticationException("Invalid authentication token");
            }

            // Validate userId
            Long userId = parseAndValidateUserId(claims.get("userId"));

            // Validate role
            UserRole role = parseAndValidateRole(claims.get("role"));

            Boolean mustChangePassword = claims.get("mustChangePassword", Boolean.class);
            // The capability claim is validated for token compatibility only. The filter
            // always reloads current authorization from the database before accepting it.
            Object canManageUsers = claims.get("canManageUsers");
            if (canManageUsers != null && !(canManageUsers instanceof Boolean)) {
                throw new JwtAuthenticationException("Invalid authentication token");
            }
            return new AuthenticatedUser(userId, subject, role, false, Boolean.TRUE.equals(mustChangePassword));

        } catch (ExpiredJwtException e) {
            log.debug("JWT token has expired");
            throw new JwtAuthenticationException("Authentication token has expired", e);
        } catch (JwtAuthenticationException e) {
            throw e;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT token validation failed: {}", e.getClass().getSimpleName());
            throw new JwtAuthenticationException("Invalid authentication token", e);
        }
    }

    private Long parseAndValidateUserId(Object userIdClaim) {
        if (userIdClaim == null) {
            log.debug("JWT userId claim is missing");
            throw new JwtAuthenticationException("Invalid authentication token");
        }

        Long userId;
        if (userIdClaim instanceof Byte || userIdClaim instanceof Short
                || userIdClaim instanceof Integer || userIdClaim instanceof Long) {
            userId = ((Number) userIdClaim).longValue();
        } else if (userIdClaim instanceof BigInteger bi) {
            try {
                userId = bi.longValueExact();
            } catch (ArithmeticException e) {
                log.debug("JWT userId exceeds Long range: {}", bi);
                throw new JwtAuthenticationException("Invalid authentication token");
            }
        } else {
            log.debug("JWT userId claim is invalid type: {}", userIdClaim.getClass().getName());
            throw new JwtAuthenticationException("Invalid authentication token");
        }

        if (userId <= 0) {
            log.debug("JWT userId must be a positive integer, got: {}", userId);
            throw new JwtAuthenticationException("Invalid authentication token");
        }

        return userId;
    }

    private UserRole parseAndValidateRole(Object roleClaim) {
        if (roleClaim == null) {
            log.debug("JWT role claim is missing");
            throw new JwtAuthenticationException("Invalid authentication token");
        }

        if (!(roleClaim instanceof String roleString)) {
            log.debug("JWT role claim is not a String: {}", roleClaim.getClass().getName());
            throw new JwtAuthenticationException("Invalid authentication token");
        }

        try {
            return UserRole.valueOf(roleString);
        } catch (IllegalArgumentException e) {
            log.debug("JWT role claim is not a valid UserRole: {}", roleString);
            throw new JwtAuthenticationException("Invalid authentication token");
        }
    }
}
