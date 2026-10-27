package com.supplypilot.agentic.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/** Creates isolated, read-only Agentic SQL pools only after explicit opt-in. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgenticSqlProperties.class)
public class AgenticSqlDataSourceConfiguration {
    private static final int MAXIMUM_POOL_SIZE = 2;

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "supplypilot.agentic.sql", name = "enabled", havingValue = "true")
    public AgenticSqlDataSources agenticSqlDataSources(
            AgenticSqlProperties properties,
            @Value("${spring.datasource.username:}") String primaryMysqlUsername,
            @Value("${supplypilot.semantic.username:}") String semanticPostgresUsername
    ) {
        properties.requireEnabledDatabaseConfiguration(primaryMysqlUsername, semanticPostgresUsername);

        HikariDataSource mysql = createDataSource("agentic-mysql", properties.mysql());
        try {
            HikariDataSource postgres = createDataSource("agentic-postgres", properties.postgres());
            return new AgenticSqlDataSources(mysql, postgres);
        } catch (RuntimeException exception) {
            mysql.close();
            throw exception;
        }
    }

    private HikariDataSource createDataSource(String poolName, AgenticSqlProperties.Database database) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setJdbcUrl(database.jdbcUrl());
        config.setUsername(database.username());
        config.setPassword(database.password());
        config.setReadOnly(true);
        config.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        config.setValidationTimeout(1000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
}
