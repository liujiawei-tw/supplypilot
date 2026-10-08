package com.supplypilot.security;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

import javax.crypto.SecretKey;

import com.supplypilot.user.domain.UserRole;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwsHeader;
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
 *   <li>Secret key length: At least 256 bits (32 bytes).</li>
 *   <li>Claims: sub (username), userId (Long), role (String), iat, exp.</li>
 *   <li>Clock: Injected Clock for deterministic testing.</li>
 * </ul>
 */
@Service
public class JwtTokenService {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenService.class);

    private final SecretKey signingKey;
    private final long expirationSeconds;
    private final Clock clock;

    public JwtTokenService(JwtProperties jwtProperties, Clock clock) {
        Objects.requireNonNull(jwtProperties, "jwtProperties must not be null");
        Objects.requireNonNull(jwtProperties.secret(), "jwtProperties.secret must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.expirationSeconds = jwtProperties.expirationSeconds();
        this.signingKey = initSigningKey(jwtProperties.secret());
    }

    private SecretKey initSigningKey(String rawSecret) {
        if (rawSecret == null || rawSecret.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT secret cannot be null or empty.");
        }

        byte[] keyBytes;
        try {
            // Attempt Base64 decoding first
            keyBytes = Decoders.BASE64.decode(rawSecret.trim());
        } catch (DecodingException | IllegalArgumentException e) {
            // Fallback to raw UTF-8 bytes
            keyBytes = rawSecret.trim().getBytes(StandardCharsets.UTF_8);
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
            throw new JwtAuthenticationException("JWT token is empty or null");
        }

        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .clock(() -> Date.from(Instant.now(clock)))
                    .build()
                    .parseSignedClaims(token.trim());

            // Validate Algorithm: Must be HS256 explicitly
            String algorithm = jws.getHeader().getAlgorithm();
            if (!"HS256".equalsIgnoreCase(algorithm)) {
                log.warn("Rejected JWT with unexpected algorithm: {}", algorithm);
                throw new JwtAuthenticationException("Unexpected JWT signing algorithm: " + algorithm);
            }

            Claims claims = jws.getPayload();

            // Validate Subject
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank()) {
                throw new JwtAuthenticationException("JWT subject claim is missing or empty");
            }

            // Validate userId
            Object userIdClaim = claims.get("userId");
            if (userIdClaim == null) {
                throw new JwtAuthenticationException("JWT userId claim is missing");
            }
            Long userId;
            if (userIdClaim instanceof Number number) {
                userId = number.longValue();
            } else {
                try {
                    userId = Long.parseLong(userIdClaim.toString());
                } catch (NumberFormatException e) {
                    throw new JwtAuthenticationException("JWT userId claim is invalid format: " + userIdClaim);
                }
            }

            // Validate role
            Object roleClaim = claims.get("role");
            if (roleClaim == null) {
                throw new JwtAuthenticationException("JWT role claim is missing");
            }
            UserRole role;
            try {
                role = UserRole.valueOf(roleClaim.toString());
            } catch (IllegalArgumentException e) {
                throw new JwtAuthenticationException("JWT role claim is invalid: " + roleClaim);
            }

            return new AuthenticatedUser(userId, subject, role);

        } catch (ExpiredJwtException e) {
            log.debug("JWT token has expired: {}", e.getMessage());
            throw new JwtAuthenticationException("JWT token has expired", e);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT token validation failed: {}", e.getMessage());
            throw new JwtAuthenticationException("Invalid JWT token: " + e.getMessage(), e);
        }
    }
}
