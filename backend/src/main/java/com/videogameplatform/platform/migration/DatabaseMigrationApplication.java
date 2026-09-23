package com.videogameplatform.platform.migration;

import java.io.PrintStream;
import java.util.Map;
import org.flywaydb.core.Flyway;

/** One-shot entry point for applying the packaged application's production migrations. */
public final class DatabaseMigrationApplication {

    static final String MIGRATION_VERSION_PREFIX = "VGP_MIGRATION_VERSION=";

    private DatabaseMigrationApplication() {}

    public static void main(String[] args) {
        run(System.getenv(), System.out);
    }

    static String run(Map<String, String> environment, PrintStream output) {
        return migrate(
                requiredEnvironment(environment, "APPLICATION_MIGRATION_DB_URL"),
                requiredEnvironment(environment, "APPLICATION_MIGRATION_DB_USERNAME"),
                requiredEnvironment(environment, "APPLICATION_MIGRATION_DB_PASSWORD"),
                output);
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

    private static String requiredEnvironment(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Required migration configuration is missing: " + name);
        }
        return value;
    }
}
