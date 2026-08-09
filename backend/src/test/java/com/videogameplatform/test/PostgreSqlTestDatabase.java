package com.videogameplatform.test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.testcontainers.postgresql.PostgreSQLContainer;

public final class PostgreSqlTestDatabase {

    private static final String ADMIN_USERNAME = "postgres";
    private static final String ADMIN_PASSWORD = "test-admin-password";
    private static final String RUNTIME_USERNAME = "videogame_app";
    private static final String RUNTIME_PASSWORD = "test-runtime-password";
    private static final String MIGRATOR_USERNAME = "videogame_app_migrator";
    private static final String MIGRATOR_PASSWORD = "test-migrator-password";
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer("postgres:18.4-bookworm")
                    .withDatabaseName("backend_startup")
                    .withUsername(ADMIN_USERNAME)
                    .withPassword(ADMIN_PASSWORD);

    static {
        POSTGRESQL.start();
        createApplicationRoles();
        changeDatabaseOwner("backend_startup", MIGRATOR_USERNAME);
    }

    private PostgreSqlTestDatabase() {}

    public static String adminUrl(String databaseName) {
        return jdbcUrl(databaseName);
    }

    public static String adminUsername() {
        return ADMIN_USERNAME;
    }

    public static String adminPassword() {
        return ADMIN_PASSWORD;
    }

    public static String runtimeUrl(String databaseName) {
        return jdbcUrl(databaseName);
    }

    public static String runtimeUsername() {
        return RUNTIME_USERNAME;
    }

    public static String runtimePassword() {
        return RUNTIME_PASSWORD;
    }

    public static String migratorUsername() {
        return MIGRATOR_USERNAME;
    }

    public static String migratorPassword() {
        return MIGRATOR_PASSWORD;
    }

    public static void createDatabase(String databaseName) throws SQLException {
        if (!databaseName.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe test database name: " + databaseName);
        }

        try (Connection connection = adminConnection("backend_startup");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName + " OWNER " + MIGRATOR_USERNAME);
        }
    }

    public static Connection adminConnection(String databaseName) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(databaseName), ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    public static Connection runtimeConnection(String databaseName) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(databaseName), RUNTIME_USERNAME, RUNTIME_PASSWORD);
    }

    private static String jdbcUrl(String databaseName) {
        return "jdbc:postgresql://%s:%d/%s"
                .formatted(POSTGRESQL.getHost(), POSTGRESQL.getFirstMappedPort(), databaseName);
    }

    private static void createApplicationRoles() {
        try (Connection connection = adminConnection("backend_startup");
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE ROLE %s LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE"
                            .formatted(RUNTIME_USERNAME, RUNTIME_PASSWORD));
            statement.execute(
                    "CREATE ROLE %s LOGIN PASSWORD '%s' NOSUPERUSER NOCREATEDB NOCREATEROLE"
                            .formatted(MIGRATOR_USERNAME, MIGRATOR_PASSWORD));
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void changeDatabaseOwner(String databaseName, String owner) {
        try (Connection connection = adminConnection(databaseName);
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER DATABASE " + databaseName + " OWNER TO " + owner);
        } catch (SQLException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
