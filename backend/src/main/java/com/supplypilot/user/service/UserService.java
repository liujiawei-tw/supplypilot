package com.supplypilot.user.service;

import java.time.Clock;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.dto.request.CreateUserRequest;
import com.supplypilot.user.dto.request.UpdateUserRequest;
import com.supplypilot.user.dto.response.UserResponse;
import com.supplypilot.user.mapper.UserMapper;
import com.supplypilot.user.repository.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    public UserResponse createUser(CreateUserRequest request) {
        String normalizedUsername = request.username().trim();
        if (userRepository.existsByUsername(normalizedUsername)) {
            throw new BusinessException(ErrorCode.DUPLICATE_USERNAME);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.create(
                normalizedUsername,
                passwordHash,
                request.displayName(),
                request.role(),
                clock.instant()
        );

        try {
            User savedUser = userRepository.saveAndFlush(user);
            return userMapper.toResponse(savedUser);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.DUPLICATE_USERNAME);
        }
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return userMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> listUsers(Pageable pageable) {
        Page<User> userPage = userRepository.findAll(pageable);
        return PageResponse.from(userPage.map(userMapper::toResponse));
    }

    @Transactional
    public UserResponse updateUser(Long id, UpdateUserRequest request) {
        if (request.displayName() != null && request.displayName().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        user.update(
                request.displayName(),
                request.role(),
                request.status(),
                clock.instant()
        );

        User savedUser = userRepository.save(user);
        return userMapper.toResponse(savedUser);
    }
}
