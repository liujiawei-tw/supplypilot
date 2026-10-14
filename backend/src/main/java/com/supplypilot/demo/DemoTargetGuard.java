package com.supplypilot.demo;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared, fail-closed checks for the physical targets used by the SP-901 demo.
 *
 * <p>This class intentionally has no Spring, JPA, or Flyway dependency so it can
 * be used before Flyway is allowed to create its history table.</p>
 */
final class DemoTargetGuard {
    private static final Pattern DEMO_TARGET_MARKER = Pattern.compile(
            "(^|[-_.])demo($|[-_.])", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARED_OR_PROD_MARKER = Pattern.compile(
            "(^|[-_.])(prod|production|shared|common)($|[-_.])", Pattern.CASE_INSENSITIVE);

    private DemoTargetGuard() { }

    static void requirePrimaryTargets(String actualDatabase, String allowedDatabase, Path storageRoot) {
        requireAllowedDatabase(allowedDatabase);
        if (actualDatabase == null || !actualDatabase.equals(allowedDatabase)) {
            throw new IllegalStateException("Refusing demo initialization for unexpected MySQL database: "
                    + actualDatabase);
        }
        if (!isDemoStorageRoot(storageRoot)) {
            throw new IllegalStateException(
                    "Refusing demo initialization for a document storage path not identified as demo");
        }
    }

    static void requireFlywayTargets(
            String actualDatabase, String allowedDatabase, String defaultSchema, String[] schemas) {
        requireAllowedDatabase(allowedDatabase);
        if (actualDatabase == null || !actualDatabase.equals(allowedDatabase)) {
            throw new IllegalStateException("Refusing Flyway migration for unexpected MySQL database: "
                    + actualDatabase);
        }
        requireFlywaySchema("default schema", defaultSchema, allowedDatabase, true);
        if (schemas != null) {
            for (String schema : schemas) {
                requireFlywaySchema("schema", schema, allowedDatabase, false);
            }
        }
    }

    private static void requireAllowedDatabase(String allowedDatabase) {
        if (!isExplicitDemoTargetName(allowedDatabase)) {
            throw new IllegalStateException("The allowed MySQL database is not explicitly demo-isolated");
        }
    }

    private static void requireFlywaySchema(
            String settingName, String schema, String allowedDatabase, boolean allowUnset) {
        if (allowUnset && (schema == null || schema.isBlank())) return;
        if (schema == null || schema.isBlank()
                || !isExplicitDemoTargetName(schema)
                || !schema.equals(allowedDatabase)) {
            throw new IllegalStateException("Refusing Flyway migration because configured " + settingName
                    + " is not the allowed demo database: " + schema);
        }
    }

    static boolean isExplicitDemoTargetName(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return DEMO_TARGET_MARKER.matcher(normalized).find()
                && !SHARED_OR_PROD_MARKER.matcher(normalized).find();
    }

    static boolean isDemoStorageRoot(Path path) {
        Path rootName = path == null ? null : path.getFileName();
        return rootName != null && isExplicitDemoTargetName(rootName.toString());
    }
}
