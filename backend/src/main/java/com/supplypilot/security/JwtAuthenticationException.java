package com.supplypilot.security;

import org.springframework.security.core.AuthenticationException;

/**
 * Exception thrown when JWT validation or extraction fails.
 */
public class JwtAuthenticationException extends AuthenticationException {

    public JwtAuthenticationException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public JwtAuthenticationException(String msg) {
        super(msg);
    }
}
