package com.supplypilot.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter that inspects incoming requests for a Bearer JWT in the Authorization header.
 *
 * <p>Validates the token using {@link JwtTokenService} and populates the
 * {@link SecurityContextHolder} with an authenticated {@link AuthenticatedUser} principal.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenService jwtTokenService;
    private final UserRepository users;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, UserRepository users) {
        this.jwtTokenService = jwtTokenService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        if (authHeader != null && authHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String token = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!token.isEmpty()) {
                try {
                    AuthenticatedUser tokenUser = jwtTokenService.parseAndValidateToken(token);
                    User current = users.findById(tokenUser.getId())
                            .filter(found -> found.getStatus() == UserStatus.ACTIVE)
                            .filter(found -> found.getUsername().equals(tokenUser.getUsername()))
                            .filter(found -> found.getRole() == tokenUser.getRole())
                            .orElseThrow(() -> new JwtAuthenticationException("Invalid authentication token"));
                    AuthenticatedUser user = new AuthenticatedUser(current.getId(), current.getUsername(),
                            current.getRole(), current.canManageUsers(),
                            current.isMustChangePassword() || tokenUser.isMustChangePassword());
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            user.getAuthorities()
                    );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                } catch (JwtAuthenticationException e) {
                    log.debug("JWT validation failed in filter: {}", e.getMessage());
                    // Clear context so invalid token doesn't leak or reuse any context
                    SecurityContextHolder.clearContext();
                }
            }
        }

        filterChain.doFilter(request, response);
    }
}
