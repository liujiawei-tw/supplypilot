package com.supplypilot.config;

import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import com.supplypilot.user.domain.UserStatus;
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

/** Performs the one-time Production Admin decision in a durable database transaction. */
@Component
@Profile("prod")
public class ProductionAdminBootstrap implements ApplicationRunner {
    private static final String BOOTSTRAP_KEY = "production-admin-bootstrap-v1";
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
        BootstrapOutcome outcome = transactions.execute(status -> bootstrapInTransaction());
        if (outcome == BootstrapOutcome.ADMIN_RECOVERY_REQUIRED) {
            throw new IllegalStateException(
                    "Production database has no active user manager. Restore an approved administrator using the runbook.");
        }
    }

    private BootstrapOutcome bootstrapInTransaction() {
        int inserted = jdbc.update("""
                INSERT IGNORE INTO system_initialization (initialization_key, initialized_at)
                VALUES (?, CURRENT_TIMESTAMP(6))
                """, BOOTSTRAP_KEY);

        boolean activeManagerExists = users.existsByCanManageUsersTrueAndStatus(UserStatus.ACTIVE);
        if (inserted == 1) {
            if (users.count() == 0) {
                User admin = User.create("admin", passwordEncoder.encode("admin"), "系統管理員",
                        UserRole.ADMIN, true, clock.instant());
                admin.requirePasswordChange();
                users.saveAndFlush(admin);
                return BootstrapOutcome.INITIALIZED;
            }
            return activeManagerExists ? BootstrapOutcome.ALREADY_INITIALIZED
                    : BootstrapOutcome.ADMIN_RECOVERY_REQUIRED;
        }

        return activeManagerExists ? BootstrapOutcome.ALREADY_INITIALIZED
                : BootstrapOutcome.ADMIN_RECOVERY_REQUIRED;
    }

    private enum BootstrapOutcome {
        INITIALIZED,
        ALREADY_INITIALIZED,
        ADMIN_RECOVERY_REQUIRED
    }
}
