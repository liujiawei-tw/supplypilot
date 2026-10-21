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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final DemoBaselineCredentialAuthenticator demoAuthenticator;

    public AuthService(
            AuthenticationManager authenticationManager,
            JwtTokenService jwtTokenService,
            UserRepository userRepository
    ) {
        this(authenticationManager, jwtTokenService, userRepository, (DemoBaselineCredentialAuthenticator) null);
    }

    @Autowired
    public AuthService(
            AuthenticationManager authenticationManager,
            JwtTokenService jwtTokenService,
            UserRepository userRepository,
            ObjectProvider<DemoBaselineCredentialAuthenticator> demoAuthenticator
    ) {
        this(authenticationManager, jwtTokenService, userRepository,
                demoAuthenticator == null ? null : demoAuthenticator.getIfAvailable());
    }

    private AuthService(AuthenticationManager authenticationManager, JwtTokenService jwtTokenService,
                        UserRepository userRepository, DemoBaselineCredentialAuthenticator demoAuthenticator) {
        this.authenticationManager = authenticationManager;
        this.jwtTokenService = jwtTokenService;
        this.userRepository = userRepository;
        this.demoAuthenticator = demoAuthenticator;
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
            authResult = demoAuthenticator != null && demoAuthenticator.handles(username)
                    ? demoAuthenticator.authenticate(username, request.password())
                    : authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, request.password()));
        } catch (BadCredentialsException | UsernameNotFoundException | DisabledException | LockedException e) {
            log.warn("Authentication failed for user '{}': {}", username, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        } catch (AuthenticationException e) {
            log.warn("Authentication rejected for user '{}': {}", username, e.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        SecurityUser securityUser = (SecurityUser) authResult.getPrincipal();
        // Read authoritative state after password verification. Roles and management
        // capability are not trusted from the authentication request or JWT.
        User user = userRepository.findById(securityUser.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        AuthenticatedUser authenticatedUser = new AuthenticatedUser(
                securityUser.getId(), securityUser.getUsername(), user.getRole(),
                user.canManageUsers(), user.isMustChangePassword());
        String token = jwtTokenService.generateToken(authenticatedUser);

        AuthenticatedUserResponse userResponse = new AuthenticatedUserResponse(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getRole(),
                user.canManageUsers(),
                user.isMustChangePassword()
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
                user.getRole(),
                user.canManageUsers(),
                user.isMustChangePassword()
        );
    }

    @Transactional
    public void changePassword(AuthenticatedUser principal, ChangePasswordRequest request,
                               org.springframework.security.crypto.password.PasswordEncoder encoder,
                               java.time.Clock clock) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (!encoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        user.changePassword(encoder.encode(request.newPassword()), clock.instant());
        userRepository.save(user);
    }
}
