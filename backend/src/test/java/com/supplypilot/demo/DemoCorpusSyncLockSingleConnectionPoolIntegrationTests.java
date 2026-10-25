package com.supplypilot.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class DemoCorpusSyncLockSingleConnectionPoolIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("supplypilot_demo_lock_pool_test");

    @Test
    void singleConnectionPoolCanRunWorkWhileNamedLockIsHeldAndFailureReleasesLock() {
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(MYSQL.getJdbcUrl());
        poolConfig.setUsername(MYSQL.getUsername());
        poolConfig.setPassword(MYSQL.getPassword());
        poolConfig.setMaximumPoolSize(1);
        poolConfig.setMinimumIdle(1);
        poolConfig.setConnectionTimeout(1_000);

        try (HikariDataSource applicationPool = new HikariDataSource(poolConfig)) {
            JdbcTemplate applicationJdbc = new JdbcTemplate(applicationPool);
            TransactionTemplate transactions = new TransactionTemplate(
                    new DataSourceTransactionManager(applicationPool));
            DemoCorpusSyncLock lockHolder = new DemoCorpusSyncLock(testConnectionDetails(), 5);
            DemoCorpusSyncLock zeroWaitContender = new DemoCorpusSyncLock(testConnectionDetails(), 0);

            assertThatThrownBy(() -> lockHolder.withLock(() -> {
                Integer value = transactions.execute(
                        transaction -> applicationJdbc.queryForObject("SELECT 1", Integer.class));
                assertThat(value).isEqualTo(1);

                assertThatThrownBy(() -> zeroWaitContender.withLock(() -> "must-not-run"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Timed out waiting for the DEMO corpus synchronization lock");

                throw new IllegalStateException("SIMULATED_CORPUS_SYNC_FAILURE");
            }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("SIMULATED_CORPUS_SYNC_FAILURE");

            assertThat(applicationPool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(zeroWaitContender.withLock(() -> "lock-reacquired")).isEqualTo("lock-reacquired");
        }
    }

    private static JdbcConnectionDetails testConnectionDetails() {
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return MYSQL.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return MYSQL.getUsername();
            }

            @Override
            public String getPassword() {
                return MYSQL.getPassword();
            }
        };
    }
}
