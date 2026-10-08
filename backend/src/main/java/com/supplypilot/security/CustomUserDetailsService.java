package com.supplypilot.security;

import com.supplypilot.user.domain.SecurityUser;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserStatus;
import com.supplypilot.user.repository.UserRepository;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads user details for Spring Security's AuthenticationManager.
 *
 * <p>Retrieves user by username from {@link UserRepository}.
 * Does not expose JPA entity directly and ensures password hashes are not logged.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("User not found");
        }

        User user = userRepository.findByUsername(username.trim())
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        boolean isEnabled = user.getStatus() == UserStatus.ACTIVE;

        return new SecurityUser(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getRole(),
                isEnabled
        );
    }
}
