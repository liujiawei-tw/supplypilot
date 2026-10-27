package com.supplypilot.agentic.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AgenticSqlStatementValidatorTests {
    private final AgenticSqlStatementValidator validator = new AgenticSqlStatementValidator();

    @Test
    void acceptsSelectAndReadOnlyCteAndAddsBoundedLimitForBothDialects() {
        var mysql = validator.validate(AgenticSqlDatabase.MYSQL,
                "WITH totals AS (SELECT supplier_id, SUM(unit_price) AS total FROM supplier_materials GROUP BY supplier_id) "
                        + "SELECT supplier_id, ROUND(total, 2) AS total FROM totals",
                3);
        var postgres = validator.validate(AgenticSqlDatabase.POSTGRESQL,
                "SELECT id FROM suppliers LIMIT 50",
                3);

        assertThat(mysql.sql()).contains("LIMIT 4");
        assertThat(mysql.tables()).extracting(AgenticSqlTableReference::name).containsExactly("supplier_materials");
        assertThat(postgres.sql()).contains("LIMIT 4");
    }

    @Test
    void preservesSmallerCallerLimitAndOffset() {
        var parsed = validator.validate(AgenticSqlDatabase.POSTGRESQL,
                "SELECT id FROM suppliers LIMIT 2 OFFSET 5", 10);

        assertThat(parsed.sql()).contains("LIMIT 2").contains("OFFSET 5");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE suppliers SET name = 'x'",
            "INSERT INTO suppliers(name) VALUES ('x')",
            "DELETE FROM suppliers",
            "CREATE TABLE unsafe_table(id INT)",
            "DROP TABLE suppliers",
            "CALL do_work()",
            "WITH x AS (SELECT id FROM suppliers) UPDATE suppliers SET name = 'x'",
            "WITH x AS (DELETE FROM suppliers RETURNING id) SELECT id FROM x",
            "SELECT id FROM suppliers FOR UPDATE",
            "SELECT id FROM suppliers FOR UPDATE SKIP LOCKED",
            "SELECT id FROM suppliers INTO OUTFILE '/tmp/suppliers.csv'",
            "SELECT id FROM suppliers INTO DUMPFILE '/tmp/suppliers.bin'",
            "SELECT pg_sleep(2)",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT public.run_side_effect()",
            "SELECT nextval('purchase_order_seq')",
            "SELECT @secret := 'x'",
            "SELECT ? FROM suppliers",
            "SELECT :secret FROM suppliers",
            "SELECT id FROM suppliers; SELECT id FROM users"
    })
    void rejectsWritesLocksFileAccessUnsafeFunctionsAndMultipleStatements(String sql) {
        assertThatThrownBy(() -> validator.validate(AgenticSqlDatabase.MYSQL, sql, 100))
                .isInstanceOf(AgenticSqlException.class)
                .extracting(error -> ((AgenticSqlException) error).code())
                .isEqualTo(AgenticSqlException.Code.UNSAFE_SQL);
    }

    @Test
    void rejectsMalformedOrUnsupportedStatementWithoutEchoingSql() {
        String sql = "SELECT (((";

        assertThatThrownBy(() -> validator.validate(AgenticSqlDatabase.MYSQL, sql, 20))
                .isInstanceOf(AgenticSqlException.class)
                .hasMessageNotContaining(sql);
    }

    @Test
    void rejectsUnknownAndNondeterministicFunctionNames() {
        for (String function : List.of("RAND()", "RANDOM()", "UUID()", "SYSDATE()", "BENCHMARK(100, 1)")) {
            assertThatThrownBy(() -> validator.validate(AgenticSqlDatabase.POSTGRESQL,
                    "SELECT " + function, 10))
                    .as("function %s", function)
                    .isInstanceOf(AgenticSqlException.class)
                    .extracting(error -> ((AgenticSqlException) error).code())
                    .isEqualTo(AgenticSqlException.Code.UNSAFE_SQL);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"password_hash", "api_key", "refresh_token", "secret_value",
            "access_key", "aws_access_key_id", "token_value"})
    void rejectsCredentialColumnReferencesEvenWhenAliasedOrNested(String column) {
        assertThatThrownBy(() -> validator.validate(AgenticSqlDatabase.MYSQL,
                "SELECT " + column + " AS harmless_label FROM suppliers", 10))
                .isInstanceOf(AgenticSqlException.class)
                .extracting(error -> ((AgenticSqlException) error).code())
                .isEqualTo(AgenticSqlException.Code.UNSAFE_SQL);
        assertThatThrownBy(() -> validator.validate(AgenticSqlDatabase.POSTGRESQL,
                "WITH hidden AS (SELECT " + column + " FROM suppliers) SELECT * FROM hidden", 10))
                .isInstanceOf(AgenticSqlException.class)
                .extracting(error -> ((AgenticSqlException) error).code())
                .isEqualTo(AgenticSqlException.Code.UNSAFE_SQL);
    }

    @Test
    void permitsSupplierContactAndSensitiveIdentifierFieldsForOutputMasking() {
        var parsed = validator.validate(AgenticSqlDatabase.MYSQL,
                "SELECT contact_person AS description, bank_account_no AS note FROM suppliers", 10);

        assertThat(parsed.sql()).contains("contact_person").contains("bank_account_no");
        assertThat(parsed.sensitiveProjectionColumns())
                .containsEntry(1, List.of("contact_person"))
                .containsEntry(2, List.of("bank_account_no"));
        assertThat(parsed.maskAllOutput()).isFalse();
    }

    @Test
    void tracksSensitiveAliasesThroughFunctionsAndCasts() {
        var parsed = validator.validate(AgenticSqlDatabase.MYSQL,
                "SELECT CONCAT(bank_account_no) AS note, CAST(contact_person AS CHAR) AS description "
                        + "FROM suppliers", 10);

        assertThat(parsed.sensitiveProjectionColumns())
                .containsEntry(1, List.of("bank_account_no"))
                .containsEntry(2, List.of("contact_person"));
        assertThat(parsed.maskAllOutput()).isFalse();
    }

    @Test
    void failsClosedWhenWildcardExpansionMakesSensitiveProjectionIndexAmbiguous() {
        var parsed = validator.validate(AgenticSqlDatabase.MYSQL,
                "SELECT *, CONCAT(bank_account_no) AS note FROM suppliers", 10);

        assertThat(parsed.sensitiveProjectionColumns()).containsEntry(2, List.of("bank_account_no"));
        assertThat(parsed.maskAllOutput()).isTrue();
    }

    @Test
    void failsClosedWhenCteRenamesSensitiveProjectionBeforeFinalOutput() {
        var parsed = validator.validate(AgenticSqlDatabase.MYSQL,
                "WITH x AS (SELECT supplier_name, bank_account_no AS item FROM suppliers) "
                        + "SELECT item AS note FROM x", 10);

        assertThat(parsed.sensitiveColumns()).contains("bank_account_no");
        assertThat(parsed.sensitiveProjectionColumns()).isEmpty();
        assertThat(parsed.maskAllOutput()).isTrue();
    }

    @Test
    void rejectsCteNamesThatCollideWithExcludedPhysicalTables() {
        assertThatThrownBy(() -> validator.validate(AgenticSqlDatabase.MYSQL,
                "WITH users AS (SELECT id, role FROM users) SELECT id, role FROM users", 10))
                .isInstanceOf(AgenticSqlException.class)
                .extracting(error -> ((AgenticSqlException) error).code())
                .isEqualTo(AgenticSqlException.Code.UNSAFE_SQL);
    }

    @Test
    void tracksAllOutputPolicySensitiveNamesThroughAliasesAndExpressions() {
        for (String column : List.of("account_number", "acct_no", "card_id", "tax_id", "taiwan_id",
                "username", "employee_id", "personal_name", "first_name", "display_name")) {
            var parsed = validator.validate(AgenticSqlDatabase.MYSQL,
                    "SELECT CONCAT(" + column + ") AS note FROM suppliers", 10);

            assertThat(parsed.sensitiveProjectionColumns())
                    .as("sensitive column %s", column)
                    .containsEntry(1, List.of(column));
            assertThat(parsed.maskAllOutput()).as("sensitive column %s", column).isFalse();
        }
        var cast = validator.validate(AgenticSqlDatabase.POSTGRESQL,
                "SELECT CAST(taiwan_id AS CHAR) AS note FROM suppliers", 10);
        assertThat(cast.sensitiveProjectionColumns()).containsEntry(1, List.of("taiwan_id"));
    }

    @Test
    void rejectsOversizedAndEmptyInput() {
        assertThatThrownBy(() -> validator.validate(AgenticSqlDatabase.MYSQL, " ", 10))
                .isInstanceOf(AgenticSqlException.class);
        assertThatThrownBy(() -> validator.validate(AgenticSqlDatabase.MYSQL, "SELECT 1".repeat(3000), 10))
                .isInstanceOf(AgenticSqlException.class);
    }
}
