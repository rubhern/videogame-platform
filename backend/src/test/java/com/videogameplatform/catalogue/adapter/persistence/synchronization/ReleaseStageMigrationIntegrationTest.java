package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ReleaseStageMigrationIntegrationTest {
    @Test
    void expandsExistingRowsToUnknownAndAcceptsOldVersionReadsInsertsAndUpdates() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("stage_expand");
        PostgreSqlTestDatabase.createDatabase(database);
        var configuration =
                Flyway.configure()
                        .dataSource(
                                PostgreSqlTestDatabase.adminUrl(database),
                                PostgreSqlTestDatabase.migratorUsername(),
                                PostgreSqlTestDatabase.migratorPassword())
                        .locations("classpath:db/migration", "classpath:db/dev-seed");
        configuration.target("20260922.120000").load().migrate();
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.runtimeUrl(database),
                                PostgreSqlTestDatabase.runtimeUsername(),
                                PostgreSqlTestDatabase.runtimePassword()));
        var before =
                jdbc.queryForList(
                        "SELECT release_id,release_status,date_precision,exact_date FROM catalogue.release_snapshot ORDER BY release_id");
        configuration.target("latest").load().migrate();
        assertThat(
                        jdbc.queryForList(
                                "SELECT release_id,release_status,date_precision,exact_date FROM catalogue.release_snapshot ORDER BY release_id"))
                .isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.release_snapshot WHERE release_stage='unknown'",
                                Integer.class))
                .isEqualTo(before.size());
        String id = "60000000-0000-4000-8000-000000000001";
        jdbc.update(
                "INSERT INTO catalogue.game_release(release_id,game_id,created_at) VALUES (?::uuid,'30000000-0000-4000-8000-000000000001',now())",
                id);
        // This is the previous application's explicit column shape: it has no release_stage.
        jdbc.update(
                """
            INSERT INTO catalogue.release_snapshot(publication_id,release_id,game_id,platform_id,region_id,
                date_precision,exact_date,release_status,source_kind,source_name,source_entity_type,
                last_synchronized_at,verification_level,review_status)
            SELECT publication_id,?::uuid,game_id,platform_id,region_id,'day','2026-10-02'::date,
                release_status,source_kind,source_name,source_entity_type,last_synchronized_at,verification_level,review_status
            FROM catalogue.release_snapshot WHERE release_id='40000000-0000-4000-8000-000000000001'
            """,
                id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT release_stage FROM catalogue.release_snapshot WHERE release_id=?::uuid",
                                String.class,
                                id))
                .isEqualTo("unknown");
        jdbc.update(
                "UPDATE catalogue.release_snapshot SET release_stage='full_release' WHERE release_id=?::uuid",
                id);
        jdbc.update(
                "UPDATE catalogue.release_snapshot SET last_synchronized_at=now() WHERE release_id=?::uuid",
                id);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT release_stage FROM catalogue.release_snapshot WHERE release_id=?::uuid",
                                String.class,
                                id))
                .isEqualTo("full_release");
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE catalogue.release_snapshot SET release_stage='invented' WHERE release_id=?::uuid",
                                        id))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
