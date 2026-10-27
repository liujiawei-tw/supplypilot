package com.supplypilot.agentic.sql;

/** Safe failure categories for the Agentic SQL tools. Never includes raw SQL or driver messages. */
public final class AgenticSqlException extends RuntimeException {
    private final Code code;

    public AgenticSqlException(Code code) {
        super(code.message());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        INVALID_SQL("The SQL query is invalid or unsupported."),
        UNSAFE_SQL("The SQL query contains a disallowed operation."),
        DATABASE_UNAVAILABLE("The selected database is unavailable."),
        QUERY_TIMEOUT("The database query exceeded its time limit."),
        QUERY_FAILED("The database query could not be completed.");

        private final String message;

        Code(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }
}
