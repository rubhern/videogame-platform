package com.videogameplatform.platform.migration;

import java.io.PrintStream;
import org.flywaydb.core.Flyway;

/** One-shot entry point for applying the packaged application's production migrations. */
public final class DatabaseMigrationApplication {

    static final String MIGRATION_VERSION_PREFIX = "VGP_MIGRATION_VERSION=";

    private DatabaseMigrationApplication() {}

    public static void main(String[] args) {
        migrate(
                requiredEnvironment("APPLICATION_MIGRATION_DB_URL"),
                requiredEnvironment("APPLICATION_MIGRATION_DB_USERNAME"),
                requiredEnvironment("APPLICATION_MIGRATION_DB_PASSWORD"),
                System.out);
    }

    static String migrate(String url, String username, String password, PrintStream output) {
        Flyway flyway =
                Flyway.configure()
                        .dataSource(url, username, password)
                        .locations("classpath:db/migration")
                        .cleanDisabled(true)
                        .validateMigrationNaming(true)
                        .validateOnMigrate(true)
                        .load();

        flyway.migrate();
        flyway.validate();

        var current = flyway.info().current();
        if (current == null || current.getVersion() == null) {
            throw new IllegalStateException("Flyway completed without a current migration version");
        }

        String migrationVersion = current.getVersion().toString();
        output.println(MIGRATION_VERSION_PREFIX + migrationVersion);
        return migrationVersion;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required migration configuration is missing: " + name);
        }
        return value;
    }
}
