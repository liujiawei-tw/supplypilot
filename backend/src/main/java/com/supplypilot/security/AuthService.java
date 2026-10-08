package com.supplypilot.security;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.dto.request.LoginRequest;
import com.supplypilot.user.dto.response.AuthenticatedUserResponse;
import com.supplypilot.user.dto.response.LoginResponse;
import com.supplypilot.user.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for user authentication and current user retrieval.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthenticationManager authenticationManager;
    private final JwtTokenService jwtTokenService;
    private final UserRepository userRepository;

    public AuthService(
            AuthenticationManager authenticationManager,
            JwtTokenService jwtTokenService,
            UserRepository userRepository
    ) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
    }

    /**
     * Authenticates credentials using AuthenticationManager, verifies ACTIVE status,
     * and issues a signed HS256 JWT access token.
     *
     * @param request Login credentials
     * @return LoginResponse with JWT and user metadata
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        String username = request.username().trim();

        Authentication authResult;
        try {
            authResult = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password())
            );
        } catch (BadCredentialsException | UsernameNotFoundException | DisabledException | LockedException e) {
            log.warn("Authentication failed for user '{}': {}", username, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        } catch (AuthenticationException e) {
            log.warn("Authentication rejected for user '{}': {}", username, e.getMessage());
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        SecurityUser securityUser = (SecurityUser) authResult.getPrincipal();
        AuthenticatedUser authenticatedUser = new AuthenticatedUser(
                securityUser.getId(),
                securityUser.getUsername(),
                securityUser.getRole()
        );

        String token = jwtTokenService.generateToken(authenticatedUser);

        // Fetch displayName safely from database by authenticated ID
        User user = userRepository.findById(authenticatedUser.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        AuthenticatedUserResponse userResponse = new AuthenticatedUserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );

        log.info("User '{}' authenticated successfully", username);
        return LoginResponse.of(token, userResponse);
    }

    /**
     * Resolves the current authenticated user's metadata from their SecurityContext principal.
     *
     * @param principal Currently authenticated principal
     * @return User metadata (excluding any credentials)
     */
    @Transactional(readOnly = true)
    public AuthenticatedUserResponse getCurrentUser(AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        return new AuthenticatedUserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole()
        );
    }
}
