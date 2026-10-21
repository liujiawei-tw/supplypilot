package com.supplypilot.user.service;

import java.time.Clock;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.dto.request.CreateUserRequest;
import com.supplypilot.user.dto.request.UpdateUserRequest;
import com.supplypilot.user.dto.response.UserResponse;
import com.supplypilot.user.dto.response.AssignableUserResponse;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.mapper.UserMapper;
import com.supplypilot.user.repository.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final UserMapper userMapper;

    public UserService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            Clock clock,
            UserMapper userMapper
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.userMapper = userMapper;
    }

    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGEMENT')")
    public UserResponse createUser(CreateUserRequest request) {
        String normalizedUsername = request.username().trim();
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new BusinessException(ErrorCode.DUPLICATE_USERNAME);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        if (request.role() != null && request.role() != com.supplypilot.user.domain.UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        User user = User.create(
                normalizedUsername,
                passwordHash,
                request.displayName(),
                com.supplypilot.user.domain.UserRole.ADMIN,
                request.canManageUsers(),
                clock.instant()
        );

        try {
            User savedUser = userRepository.saveAndFlush(user);
            return userMapper.toResponse(savedUser);
        } catch (DataIntegrityViolationException e) {
            if (isUsernameUniqueConstraintViolation(e)) {
                throw new BusinessException(ErrorCode.DUPLICATE_USERNAME);
            }
            throw e;
        }
    }

    /**
     * Returns true only when the exception is confirmed to be a violation of
     * the {@code uk_users_username} unique constraint, via Hibernate's structured
     * {@link org.hibernate.exception.ConstraintViolationException#getConstraintName()}.
     * Avoids misclassifying other integrity failures (e.g. FK violations, NOT NULL)
     * as duplicate-username errors.
     */
    private boolean isUsernameUniqueConstraintViolation(DataIntegrityViolationException e) {
        Throwable cause = e.getCause();
        return cause instanceof org.hibernate.exception.ConstraintViolationException hcve
                && "uk_users_username".equalsIgnoreCase(hcve.getConstraintName());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('USER_MANAGEMENT')")
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return userMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('USER_MANAGEMENT')")
    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        Page<User> userPage = userRepository.findAll(pageable);
        return PageResponse.from(userPage.map(userMapper::toResponse));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<AssignableUserResponse> listAssignableUsers() {
        return userRepository.findByRoleAndStatusOrderByDisplayNameAsc(UserRole.ADMIN, UserStatus.ACTIVE)
                .stream().map(user -> new AssignableUserResponse(user.getId(), user.getUsername(), user.getDisplayName()))
                .toList();
    }

    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGEMENT')")
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        if (request.displayName() != null && request.displayName().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        // A locking current read serializes all capability/status changes against
        // the active manager set. A plain COUNT under MySQL REPEATABLE READ would
        // let two concurrent requests both observe the same pre-update count.
        List<User> activeManagers = userRepository.findActiveManagersForUpdate(UserStatus.ACTIVE);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        if (request.role() != null && request.role() != com.supplypilot.user.domain.UserRole.ADMIN) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        UserStatus resultingStatus = request.status() == null ? user.getStatus() : request.status();
        boolean resultingCapability = request.canManageUsers() == null
                ? user.canManageUsers() : request.canManageUsers();
        if (user.canManageUsers() && user.getStatus() == UserStatus.ACTIVE
                && (!resultingCapability || resultingStatus != UserStatus.ACTIVE)
                && activeManagers.size() <= 1) {
            throw new AccessDeniedException("The last active user manager cannot be removed.");
        }

        user.update(
                request.displayName(),
                request.role(),
                request.status(),
                request.canManageUsers(),
                clock.instant()
        );

        User savedUser = userRepository.save(user);
        return userMapper.toResponse(savedUser);
    }

    @Transactional
    @PreAuthorize("hasAuthority('USER_MANAGEMENT')")
    public void deleteUser(Long id) {
        List<User> activeManagers = userRepository.findActiveManagersForUpdate(UserStatus.ACTIVE);
        User user = userRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.canManageUsers() && user.getStatus() == UserStatus.ACTIVE && activeManagers.size() <= 1) {
            throw new AccessDeniedException("The last active user manager cannot be removed.");
        }
        try {
            userRepository.delete(user);
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.DATA_INTEGRITY_VIOLATION);
        }
    }
}
