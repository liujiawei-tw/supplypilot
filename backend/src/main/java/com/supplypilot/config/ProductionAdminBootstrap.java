package com.supplypilot.config;

import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.repository.UserRepository;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates the explicitly required first Production administrator without modifying populated databases. */
@Component
@Profile("prod")
public class ProductionAdminBootstrap implements ApplicationRunner {
    private static final String LOCK_NAME = "supplypilot-prod-admin-bootstrap";
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ProductionAdminBootstrap(JdbcTemplate jdbc, UserRepository users, PasswordEncoder passwordEncoder,
                                    Clock clock, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> {
            Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 30)", Integer.class, LOCK_NAME);
            if (!Integer.valueOf(1).equals(acquired)) {
                throw new IllegalStateException("Could not acquire Production Admin bootstrap lock");
            }
            try {
                if (users.count() == 0) {
                    User admin = User.create("admin", passwordEncoder.encode("admin"), "系統管理員",
                            UserRole.ADMIN, clock.instant());
                    admin.requirePasswordChange();
                    users.saveAndFlush(admin);
                }
            } finally {
                jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, LOCK_NAME);
            }
        });
    }
}
