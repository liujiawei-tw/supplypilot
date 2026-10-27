package com.supplypilot.agentic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.agentic.config.AgenticSqlProperties;
import com.supplypilot.agentic.security.AgenticSqlOutputPolicy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies the parser's capped SQL and JDBC read-only behavior against real MySQL and PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class AgenticSqlExecutorIntegrationTests {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4.11"))
            .withDatabaseName("supplypilot_agentic_test")
            .withUsername("agentic_test")
            .withPassword("agentic_test");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("supplypilot_agentic_test")
            .withUsername("agentic_test")
            .withPassword("agentic_test");

    private static final AgenticSqlMetadataPolicy METADATA_POLICY = new AgenticSqlMetadataPolicy();
    private static final AgenticSqlOutputPolicy OUTPUT_POLICY = new AgenticSqlOutputPolicy(new ObjectMapper());

    @BeforeAll
    static void createFixtureSchemas() throws SQLException {
        try (Connection connection = mysqlAdminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE agentic_items ("
                    + "id BIGINT PRIMARY KEY, name VARCHAR(60), contact_person VARCHAR(80),"
                    + "email VARCHAR(120), bank_account_no VARCHAR(40), account_number VARCHAR(40),"
                    + "taiwan_id VARCHAR(20), password_hash VARCHAR(100), access_key VARCHAR(80),"
                    + "token_value VARCHAR(80),"
                    + "amount DECIMAL(18,2), long_text TEXT, INDEX idx_agentic_items_name(name))");
            statement.execute("INSERT INTO agentic_items(id, name, contact_person, email, bank_account_no, account_number,"
                    + "taiwan_id,"
                    + "password_hash, access_key, token_value, amount, long_text) VALUES "
                    + "(1, 'alpha', 'Alice Example', 'alice@example.com', '123456789012', '987654321098',"
                    + "'A123456789', 'secret-hash', 'aws-secret-1', 'token-secret-1', 12.50, '"
                    + "" + "x".repeat(1200) + "'), "
                    + "(2, 'beta', 'Bob Example', 'bob@example.com', '987654321098', '123456789012',"
                    + "'B123456789', 'secret-hash-2', 'aws-secret-2', 'token-secret-2', 25.00, 'short')");
            statement.execute("CREATE TABLE future_business_data (id BIGINT PRIMARY KEY, item_id BIGINT NOT NULL,"
                    + " value_text VARCHAR(120), password_hash VARCHAR(100),"
                    + " CONSTRAINT fk_future_item FOREIGN KEY(item_id) REFERENCES agentic_items(id),"
                    + " INDEX idx_future_item_value(value_text))");
            statement.execute("CREATE TABLE ai_purchase_draft_proposals (id CHAR(36) PRIMARY KEY, payload_json JSON)");
            statement.execute("CREATE TABLE document_chunks (id BIGINT PRIMARY KEY, document_id BIGINT,"
                    + " content TEXT, embedding TEXT)");
            statement.execute("INSERT INTO document_chunks VALUES (41, 7, 'authoritative mysql source', 'hidden-vector')");
        }
        try (Connection connection = postgresAdminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA agentic_data");
            statement.execute("CREATE TABLE agentic_data.future_items ("
                    + "id BIGINT PRIMARY KEY, contact_person VARCHAR(80), email VARCHAR(120),"
                    + "bank_account_no VARCHAR(40), password_hash VARCHAR(100), long_text TEXT)");
            statement.execute("CREATE INDEX idx_future_items_email ON agentic_data.future_items(email)");
            statement.execute("COMMENT ON COLUMN agentic_data.future_items.email IS 'Contact alice@example.com'");
            statement.execute("INSERT INTO agentic_data.future_items VALUES "
                    + "(3, 'Alice Example', 'alice@example.com', '123456789012', 'secret-hash', '"
                    + "" + "y".repeat(1200) + "'), "
                    + "(4, 'Bob Example', 'bob@example.com', '987654321098', 'secret-hash-2', 'short')");
            statement.execute("CREATE TABLE public.ai_purchase_draft_proposals (id UUID, payload_json JSONB)");
        }
    }

    @Test
    void executesBothDialectsAndPreservesPrimaryKeySources() {
        AgenticSqlExecutor executor = executor(10, 10, 2000);

        var mysqlResult = executor.execute("tool-mysql", AgenticSqlDatabase.MYSQL,
                "SELECT id, name FROM agentic_items WHERE id = 1");
        var mysqlWithoutProjectedKey = executor.execute("tool-mysql-no-pk", AgenticSqlDatabase.MYSQL,
                "SELECT name FROM agentic_items WHERE id = 1");
        var unmatchedLeftJoin = executor.execute("tool-mysql-left-join", AgenticSqlDatabase.MYSQL,
                "SELECT i.id, f.id AS future_id FROM agentic_items i "
                        + "LEFT JOIN future_business_data f ON f.item_id = i.id WHERE i.id = 2");
        var postgresResult = executor.execute("tool-postgres", AgenticSqlDatabase.POSTGRESQL,
                "SELECT id, email FROM agentic_data.future_items WHERE id = 3");

        assertThat(mysqlResult.toolCallId()).isEqualTo("tool-mysql");
        assertThat(mysqlResult.tables()).anyMatch(table -> table.endsWith("agentic_items"));
        assertThat(mysqlResult.rows()).singleElement().satisfies(row -> assertThat(row).containsEntry("name", "alpha"));
        assertThat(mysqlResult.sourceRows()).singleElement().satisfies(source -> {
            assertThat(source.resultRowIndex()).isZero();
            assertThat(source.table()).isEqualToIgnoringCase("agentic_items");
            assertThat(source.primaryKeyValues()).containsEntry("id", "1");
        });
        assertThat(postgresResult.database()).isEqualTo(AgenticSqlDatabase.POSTGRESQL);
        assertThat(postgresResult.rows()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("id", "3"));
        assertThat(mysqlWithoutProjectedKey.sourceRows()).isEmpty();
        assertThat(unmatchedLeftJoin.sourceRows()).singleElement().satisfies(source -> {
            assertThat(source.table()).isEqualToIgnoringCase("agentic_items");
            assertThat(source.primaryKeyValues()).containsEntry("id", "2");
        });
        assertThat(unmatchedLeftJoin.sourceRows())
                .noneMatch(source -> source.table().equalsIgnoreCase("future_business_data"));
    }

    @Test
    void appliesRowColumnAndCharacterCapsWithoutReturningLongValuePrefixes() {
        AgenticSqlExecutor executor = executor(1, 2, 180);

        var rowLimited = executor.execute("rows", AgenticSqlDatabase.MYSQL,
                "SELECT id, name FROM agentic_items ORDER BY id");
        var columnLimited = executor.execute("columns", AgenticSqlDatabase.MYSQL,
                "SELECT id, name, amount FROM agentic_items WHERE id = 1");
        var characterLimited = executor.execute("characters", AgenticSqlDatabase.MYSQL,
                "SELECT long_text FROM agentic_items WHERE id = 1");

        assertThat(rowLimited.rows()).hasSize(1);
        assertThat(rowLimited.truncated()).isTrue();
        assertThat(columnLimited.rows().getFirst()).containsOnlyKeys("id", "name");
        assertThat(columnLimited.truncated()).isTrue();
        assertThat(characterLimited.truncated()).isTrue();
        assertThat(characterLimited.rows().getFirst().get("long_text")).isEqualTo("[VALUE_TRUNCATED]");
        assertThat(characterLimited.rows().getFirst().get("long_text").toString()).doesNotContain("x");
    }

    @Test
    void appliesRowColumnAndCharacterCapsAgainstPostgres() {
        AgenticSqlExecutor rowExecutor = executor(1, 10, 2000);
        AgenticSqlExecutor columnExecutor = executor(10, 1, 2000);
        AgenticSqlExecutor characterExecutor = executor(10, 10, 100);

        var rowLimited = rowExecutor.execute("postgres-rows", AgenticSqlDatabase.POSTGRESQL,
                "SELECT id FROM agentic_data.future_items ORDER BY id");
        var columnLimited = columnExecutor.execute("postgres-columns", AgenticSqlDatabase.POSTGRESQL,
                "SELECT id, email, bank_account_no FROM agentic_data.future_items WHERE id = 3");
        var characterLimited = characterExecutor.execute("postgres-characters", AgenticSqlDatabase.POSTGRESQL,
                "SELECT long_text FROM agentic_data.future_items WHERE id = 3");

        assertThat(rowLimited.rows()).hasSize(1);
        assertThat(rowLimited.truncated()).isTrue();
        assertThat(columnLimited.rows().getFirst()).containsOnlyKeys("id");
        assertThat(columnLimited.truncated()).isTrue();
        assertThat(characterLimited.truncated()).isTrue();
        assertThat(characterLimited.rows().getFirst().get("long_text")).isEqualTo("[VALUE_TRUNCATED]");
    }

    @Test
    void masksPhysicalBankAndPersonalColumnsEvenWhenAliasedAndScansNumericValues() {
        AgenticSqlExecutor executor = executor(10, 10, 2000);

        var bank = executor.execute("bank", AgenticSqlDatabase.MYSQL,
                "SELECT bank_account_no AS note FROM agentic_items WHERE id = 1");
        var accountFunction = executor.execute("account-function", AgenticSqlDatabase.MYSQL,
                "SELECT CONCAT(account_number) AS note FROM agentic_items WHERE id = 1");
        var identityCast = executor.execute("identity-cast", AgenticSqlDatabase.MYSQL,
                "SELECT CAST(taiwan_id AS CHAR) AS note FROM agentic_items WHERE id = 1");
        var nestedCte = executor.execute("nested-cte", AgenticSqlDatabase.MYSQL,
                "WITH x AS (SELECT name, bank_account_no AS item FROM agentic_items WHERE id = 1) "
                        + "SELECT name AS label, item AS note FROM x");
        var reorderedCte = executor.execute("reordered-cte", AgenticSqlDatabase.MYSQL,
                "WITH x AS (SELECT bank_account_no AS item, name AS label FROM agentic_items WHERE id = 1) "
                        + "SELECT label AS ordinary, item AS note FROM x");
        var derivedJoin = executor.execute("derived-join", AgenticSqlDatabase.MYSQL,
                "SELECT q.label AS ordinary, q.item AS note FROM "
                        + "(SELECT bank_account_no AS item, name AS label FROM agentic_items WHERE id = 1) q "
                        + "JOIN agentic_items i ON i.id = 1");
        var contact = executor.execute("contact", AgenticSqlDatabase.MYSQL,
                "SELECT contact_person AS description FROM agentic_items WHERE id = 1");
        var bankFunction = executor.execute("bank-function", AgenticSqlDatabase.MYSQL,
                "SELECT CONCAT(bank_account_no) AS note FROM agentic_items WHERE id = 1");
        var bankCast = executor.execute("bank-cast", AgenticSqlDatabase.MYSQL,
                "SELECT CAST(bank_account_no AS CHAR) AS note FROM agentic_items WHERE id = 1");
        var bankWithWildcard = executor.execute("bank-star", AgenticSqlDatabase.MYSQL,
                "SELECT *, CONCAT(bank_account_no) AS note FROM agentic_items WHERE id = 1");
        var email = executor.execute("email", AgenticSqlDatabase.POSTGRESQL,
                "SELECT email AS description FROM agentic_data.future_items WHERE id = 3");
        var number = executor.execute("number", AgenticSqlDatabase.POSTGRESQL,
                "SELECT 1234567890123456 AS identifier");
        var tokenText = executor.execute("token-text", AgenticSqlDatabase.MYSQL,
                "SELECT 'token=must-not-send' AS note");

        assertThat(bank.rows().getFirst().get("note")).isEqualTo("[REDACTED_IDENTIFIER]");
        assertThat(accountFunction.rows().getFirst().get("note")).isEqualTo("[REDACTED_IDENTIFIER]");
        assertThat(identityCast.rows().getFirst().get("note")).isEqualTo("[REDACTED_IDENTIFIER]");
        assertThat(nestedCte.rows().getFirst().values())
                .containsOnly("[SENSITIVE_DATA_REDACTED]");
        assertThat(reorderedCte.rows().getFirst().values())
                .containsOnly("[SENSITIVE_DATA_REDACTED]");
        assertThat(derivedJoin.rows().getFirst().values())
                .containsOnly("[SENSITIVE_DATA_REDACTED]");
        assertThat(contact.rows().getFirst().get("description")).isEqualTo("[REDACTED_IDENTIFIER]");
        assertThat(bankFunction.rows().getFirst().get("note")).isEqualTo("[REDACTED_IDENTIFIER]");
        assertThat(bankCast.rows().getFirst().get("note")).isEqualTo("[REDACTED_IDENTIFIER]");
        assertThat(bankWithWildcard.rows().getFirst().values())
                .allSatisfy(value -> assertThat(value).isNotEqualTo("123456789012"));
        assertThat(bankFunction.rows().toString()).doesNotContain("123456789012");
        assertThat(bankCast.rows().toString()).doesNotContain("123456789012");
        assertThat(nestedCte.rows().toString()).doesNotContain("123456789012");
        assertThat(reorderedCte.rows().toString()).doesNotContain("123456789012");
        assertThat(derivedJoin.rows().toString()).doesNotContain("123456789012");
        assertThat(contact.rows().toString()).doesNotContain("Alice Example");
        assertThat(tokenText.rows().toString()).doesNotContain("must-not-send");
        assertThat(email.rows().getFirst().get("description").toString()).doesNotContain("alice@example.com");
        assertThat(number.rows().getFirst().get("identifier").toString()).doesNotContain("1234567890123456");
    }

    @Test
    void neverReturnsPrefixOfLongNumericValueWhenCharacterBudgetIsShorterThanIdentifier() {
        AgenticSqlExecutor executor = executor(10, 10, 14);

        var result = executor.execute("numeric-truncation", AgenticSqlDatabase.MYSQL,
                "SELECT 4111111111111111 AS n");

        assertThat(result.truncated()).isTrue();
        assertThat(result.rows().getFirst().get("n")).isEqualTo("[VALUE_TRUNC");
        assertThat(result.rows().toString()).doesNotContain("4111111111111111", "411111111111");
    }

    @Test
    void handlesSqlNullValuesAndHidesCredentialColumnsFromWildcardAndSchema() {
        AgenticSqlExecutor executor = executor(10, 10, 2000);
        AgenticSqlSchemaInspector inspector = inspector(10, 10, 2000);

        var nullResult = executor.execute("null", AgenticSqlDatabase.MYSQL,
                "SELECT NULL AS nullable_value");
        var wildcard = executor.execute("wildcard", AgenticSqlDatabase.MYSQL,
                "SELECT * FROM agentic_items WHERE id = 1");
        var schema = inspector.inspectSchema("schema", AgenticSqlDatabase.MYSQL, null, "agentic_items");

        assertThat(nullResult.rows().getFirst()).containsKey("nullable_value");
        assertThat(nullResult.rows().getFirst().get("nullable_value")).isNull();
        assertThat(wildcard.rows().getFirst()).doesNotContainKey("password_hash");
        assertThat(wildcard.rows().getFirst()).doesNotContainKeys("access_key", "token_value");
        assertThat(schema.tables()).singleElement().satisfies(table -> {
            assertThat(table.columns()).extracting(AgenticSqlColumnSchema::name).contains("contact_person", "email")
                    .doesNotContain("password_hash", "access_key", "token_value");
            assertThat(table.indexes()).extracting(AgenticSqlIndexSchema::name).contains("idx_agentic_items_name");
        });
    }

    @Test
    void discoversFuturePostgresSchemaAndMarksSemanticChunksAsDerived() throws SQLException {
        try (Connection connection = postgresAdminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE public.semantic_chunks (document_id BIGINT, document_version VARCHAR(50),"
                    + " generation_key VARCHAR(150), chunk_index INT, content TEXT, embedding TEXT,"
                    + " PRIMARY KEY(document_id, document_version, generation_key, chunk_index))");
        }
        AgenticSqlSchemaInspector inspector = inspector(10, 10, 2000);

        var discoveredFutureTable = inspector.inspectSchema("future", AgenticSqlDatabase.POSTGRESQL,
                "agentic_data", "future_items");
        var derivedIndex = inspector.inspectSchema("derived", AgenticSqlDatabase.POSTGRESQL, "public", "semantic_chunks");
        var hiddenSystemSchema = inspector.inspectSchema("system", AgenticSqlDatabase.POSTGRESQL,
                "pg_catalog", null);

        assertThat(discoveredFutureTable.tables()).singleElement().satisfies(table -> {
            assertThat(table.name()).isEqualTo("future_items");
            assertThat(table.columns()).extracting(AgenticSqlColumnSchema::name).contains("contact_person")
                    .doesNotContain("password_hash");
            assertThat(table.columns().stream().map(AgenticSqlColumnSchema::remarks).filter(java.util.Objects::nonNull))
                    .noneMatch(remark -> remark.contains("alice@example.com"));
            assertThat(table.indexes()).extracting(AgenticSqlIndexSchema::name).contains("idx_future_items_email");
        });
        assertThat(derivedIndex.tables()).singleElement().satisfies(table -> {
            assertThat(table.classification()).isEqualTo("DERIVED_INDEX");
            assertThat(table.authoritativeSource()).isEqualTo("MYSQL document_chunks");
            assertThat(table.warning()).contains("missing or stale");
            assertThat(table.columns()).extracting(AgenticSqlColumnSchema::name).doesNotContain("embedding");
        });
        assertThat(hiddenSystemSchema.tables()).isEmpty();
    }

    @Test
    void missingPostgresIndexDoesNotPreventReadingAuthoritativeMysqlDocumentChunks() throws SQLException {
        try (Connection connection = postgresAdminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS public.semantic_chunks");
        }
        AgenticSqlSchemaInspector inspector = inspector(10, 10, 2000);
        AgenticSqlExecutor executor = executor(10, 10, 2000);

        assertThat(inspector.inspectSchema("missing", AgenticSqlDatabase.POSTGRESQL, "public", "semantic_chunks")
                .tables()).isEmpty();
        var mysqlSource = executor.execute("source", AgenticSqlDatabase.MYSQL,
                "SELECT id, content FROM document_chunks WHERE id = 41");

        assertThat(mysqlSource.rows()).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("content", "authoritative mysql source"));
    }

    @Test
    void usesDatabaseReadOnlyTransactionsForBothEngines() throws SQLException {
        assertUpdateDenied(AgenticSqlDatabase.MYSQL);
        assertUpdateDenied(AgenticSqlDatabase.POSTGRESQL);
    }

    @Test
    void cancelsLongRunningPostgresRecursiveQueryAtStatementTimeout() {
        AgenticSqlExecutor executor = executor(10, 10, 2000, Duration.ofMillis(150));

        assertThatThrownBy(() -> executor.execute("timeout", AgenticSqlDatabase.POSTGRESQL,
                "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM t) "
                        + "SELECT SUM(n) AS total FROM t"))
                .isInstanceOf(AgenticSqlException.class)
                .extracting(error -> ((AgenticSqlException) error).code())
                .isEqualTo(AgenticSqlException.Code.QUERY_TIMEOUT);
    }

    @Test
    void rejectsCredentialsAndUnsupportedSideEffectFunctionsBeforeDatabaseExecution() {
        AgenticSqlExecutor executor = executor(10, 10, 2000);

        assertThatThrownBy(() -> executor.execute("credential", AgenticSqlDatabase.MYSQL,
                "SELECT password_hash AS harmless_label FROM agentic_items"))
                .isInstanceOf(AgenticSqlException.class)
                .extracting(error -> ((AgenticSqlException) error).code())
                .isEqualTo(AgenticSqlException.Code.UNSAFE_SQL);
        assertThatThrownBy(() -> executor.execute("function", AgenticSqlDatabase.POSTGRESQL,
                "SELECT pg_sleep(2)"))
                .isInstanceOf(AgenticSqlException.class)
                .extracting(error -> ((AgenticSqlException) error).code())
                .isEqualTo(AgenticSqlException.Code.UNSAFE_SQL);
    }

    private void assertUpdateDenied(AgenticSqlDatabase database) throws SQLException {
        try (Connection connection = connection(database)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (database == AgenticSqlDatabase.MYSQL) {
                    statement.execute("SET SESSION max_execution_time = 5000");
                }
                statement.execute("SET TRANSACTION READ ONLY");
                if (database == AgenticSqlDatabase.MYSQL) {
                    assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE agentic_items SET amount = 99 WHERE id = 1"))
                            .isInstanceOf(SQLException.class);
                } else {
                    assertThatThrownBy(() -> statement.executeUpdate(
                            "UPDATE agentic_data.future_items SET email = 'changed' WHERE id = 3"))
                            .isInstanceOf(SQLException.class);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private AgenticSqlExecutor executor(int maxRows, int maxColumns, int maxChars) {
        return executor(maxRows, maxColumns, maxChars, Duration.ofSeconds(5));
    }

    private AgenticSqlExecutor executor(int maxRows, int maxColumns, int maxChars, Duration statementTimeout) {
        AgenticSqlProperties properties = properties(maxRows, maxColumns, maxChars, statementTimeout);
        AgenticSqlSchemaInspector inspector = new AgenticSqlSchemaInspector(
                this::connection, properties, METADATA_POLICY, OUTPUT_POLICY);
        return new AgenticSqlExecutor(this::connection, properties, new AgenticSqlStatementValidator(),
                inspector, METADATA_POLICY, OUTPUT_POLICY);
    }

    private AgenticSqlSchemaInspector inspector(int maxRows, int maxColumns, int maxChars) {
        AgenticSqlProperties properties = properties(maxRows, maxColumns, maxChars);
        return new AgenticSqlSchemaInspector(this::connection, properties, METADATA_POLICY, OUTPUT_POLICY);
    }

    private AgenticSqlProperties properties(int maxRows, int maxColumns, int maxChars) {
        return properties(maxRows, maxColumns, maxChars, Duration.ofSeconds(5));
    }

    private AgenticSqlProperties properties(int maxRows, int maxColumns, int maxChars, Duration statementTimeout) {
        return new AgenticSqlProperties(true, Duration.ofSeconds(30), Duration.ofSeconds(5), statementTimeout,
                10, 10, maxRows, maxColumns, maxChars, 1000,
                new AgenticSqlProperties.Database(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()),
                new AgenticSqlProperties.Database(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private Connection connection(AgenticSqlDatabase database) throws SQLException {
        return database == AgenticSqlDatabase.MYSQL ? mysqlAdminConnection() : postgresAdminConnection();
    }

    private static Connection mysqlAdminConnection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Connection postgresAdminConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
