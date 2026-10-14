package com.supplypilot.demo;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.flywaydb.core.api.FlywayException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Makes the physical demo-target check precede Flyway when a demo process starts.
 * This protects a mistakenly selected database from even receiving Flyway history
 * or application tables; normal and production profiles keep Boot's normal migration path.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo & !prod")
@EnableConfigurationProperties(DemoProperties.class)
class DemoFlywayPreflightConfiguration {

    @Bean
    FlywayMigrationStrategy demoFlywayMigrationStrategy(
            DataSource dataSource,
            DemoProperties properties,
            @Value("${supplypilot.document-storage.path:./data/demo-documents}") String documentStoragePath) {
        Path storageRoot = Path.of(documentStoragePath).toAbsolutePath().normalize();
        return flyway -> {
            DemoTargetGuard.requirePrimaryTargets(currentDatabase(dataSource), properties.getAllowedDatabase(), storageRoot);
            flyway.migrate();
        };
    }

    private static String currentDatabase(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT DATABASE()")) {
            return result.next() ? result.getString(1) : null;
        } catch (SQLException exception) {
            throw new FlywayException("Unable to verify the physical MySQL database before demo migration", exception);
        }
    }
}
