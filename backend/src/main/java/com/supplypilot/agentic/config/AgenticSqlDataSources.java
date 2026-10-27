package com.supplypilot.agentic.config;

import java.sql.Connection;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariDataSource;

/** Named connection access for Agentic SQL adapters without registering extra application DataSources. */
public final class AgenticSqlDataSources implements AutoCloseable {
    private final HikariDataSource mysql;
    private final HikariDataSource postgres;

    AgenticSqlDataSources(HikariDataSource mysql, HikariDataSource postgres) {
        this.mysql = mysql;
        this.postgres = postgres;
    }

    public Connection getMysqlConnection() throws SQLException {
        return mysql.getConnection();
    }

    public Connection getPostgresConnection() throws SQLException {
        return postgres.getConnection();
    }

    @Override
    public void close() {
        postgres.close();
        mysql.close();
    }
}
