package com.supplypilot.security;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration for SupplyPilot.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Stateless session creation.</li>
 *   <li>CSRF disabled for REST Bearer API.</li>
 *   <li>CORS configured strictly from supplypilot.frontend-origin in production, with dev fallback only outside prod.</li>
 *   <li>POST /api/v1/auth/login and Actuator health endpoints are public.</li>
 *   <li>All other /api/v1/** endpoints require authentication.</li>
 *   <li>Custom AuthenticationEntryPoint for 401 error response.</li>
 *   <li>JwtAuthenticationFilter placed before UsernamePasswordAuthenticationFilter.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final CustomUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;
    private final String frontendOrigin;
    private final Environment environment;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            CustomAuthenticationEntryPoint authenticationEntryPoint,
            CustomAccessDeniedHandler accessDeniedHandler,
            CustomUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder,
            @Value("${supplypilot.frontend-origin:}") String frontendOrigin,
            Environment environment
    ) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
        this.frontendOrigin = frontendOrigin;
        this.environment = environment;
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setHideUserNotFoundExceptions(true);
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authenticationProvider(authenticationProvider())
                .authorizeHttpRequests(authorize -> authorize
                        // Public authentication endpoints
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        // Public Actuator health check
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // Protected /me endpoint
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        // All other /api/v1 endpoints require authentication
                        .requestMatchers("/api/v1/**").authenticated()
                        // Any remaining requests require authentication
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        boolean isProd = Arrays.asList(environment.getActiveProfiles()).contains("prod");

        if (frontendOrigin != null && !frontendOrigin.isBlank()) {
            List<String> origins = Arrays.stream(frontendOrigin.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            // Production requires one or more exact origins; wildcard patterns are not allowed with credentials.
            if (isProd && (origins.isEmpty() || origins.stream().anyMatch(origin -> origin.contains("*")))) {
                throw new IllegalStateException(
                        "CORS allowed-origins must contain at least one exact non-wildcard origin in production."
                );
            }

            configuration.setAllowedOrigins(origins);
        } else {
            if (isProd) {
                // Fail-fast in production: Missing FRONTEND_ORIGIN cannot fall back to localhost
                throw new IllegalStateException(
                        "CORS configuration failed: supplypilot.frontend-origin (FRONTEND_ORIGIN) must be configured in production."
                );
            }
            // Safe fallback for local/test development only: allow local frontend dev servers
            configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        }

        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Requested-With"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
