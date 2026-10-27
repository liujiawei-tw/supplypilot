package com.supplypilot.agentic.sql;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
interface AgenticSqlConnectionProvider {
    Connection open(AgenticSqlDatabase database) throws SQLException;
}
