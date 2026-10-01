package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ConfirmedReleaseReviewRepairIntegrationTest {
    private JdbcTemplate jdbc;
    private String repair;

    @BeforeEach
    void database() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("review_repair");
        PostgreSqlTestDatabase.createDatabase(database);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration", "classpath:db/dev-seed")
                .load()
                .migrate();
        jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.runtimeUrl(database),
                                PostgreSqlTestDatabase.runtimeUsername(),
                                PostgreSqlTestDatabase.runtimePassword()));
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("scripts/repair-issue-211-confirmed-releases.sql"))) {
            root = root.getParent();
            if (root == null) throw new IllegalStateException("Cannot locate issue 211 repair");
        }
        repair = Files.readString(root.resolve("scripts/repair-issue-211-confirmed-releases.sql"));
        UUID requiem = game("347668");
        release(requiem, "752219", "6", "2026-02-27");
        release(requiem, "752220", "167", "2026-02-27");
        release(requiem, "752221", "169", "2026-02-27");
        release(requiem, "800758", "508", "2026-02-27");
        release(game("416594"), "961960", "6", "2026-09-21");
    }

    @Test
    void repairsOnlyTheExplicitlyAcceptedTuplesAndIsIdempotent() {
        // A genuine review on another provider reference, even for the same known date,
        // stays blocked. The allowlist must never become a blanket reset.
        release(game("999999"), "999999", "6", "2026-02-27");
        var before = evidence();
        var version = version();

        jdbc.execute(repair);

        assertThat(required()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT review_status FROM catalogue.release_snapshot JOIN catalogue.release_external_reference USING(release_id, game_id) WHERE provider = 'IGDB' AND provider_id = '999999'",
                                String.class))
                .isEqualTo("required");
        assertThat(evidence()).isEqualTo(before);
        assertThat(version()).isNotEqualTo(version);
        var repairedVersion = version();
        jdbc.execute(repair);
        assertThat(version()).isEqualTo(repairedVersion);
        assertThat(required()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "release_status = 'cancelled'",
                "release_status = 'delayed'",
                "date_precision = 'unknown', exact_date = NULL",
                "exact_date = DATE '2099-01-01'",
                "verification_level = 'verified', last_verified_at = now()",
                "source_kind = 'product_curated'"
            })
    void changedEvidenceAbortsAndRollsBackPreviouslyMatchedTuples(String change) {
        // 800758 is checked after the first three releases, proving all-or-nothing repair.
        jdbc.update(
                "UPDATE catalogue.release_snapshot SET "
                        + change
                        + " WHERE release_id = (SELECT release_id FROM catalogue.release_external_reference WHERE provider = 'IGDB' AND provider_id = '800758')");
        var version = version();

        assertThatThrownBy(() -> jdbc.execute(repair)).isInstanceOf(DataAccessException.class);

        assertThat(required()).isEqualTo(5);
        assertThat(version()).isEqualTo(version);
    }

    @Test
    void aMissingExpectedIdentityAbortsTheWholeRepair() {
        jdbc.update(
                "DELETE FROM catalogue.release_external_reference WHERE provider = 'IGDB' AND provider_id = '800758'");
        assertThatThrownBy(() -> jdbc.execute(repair)).isInstanceOf(DataAccessException.class);
        assertThat(required()).isEqualTo(5);
    }

    private UUID game(String providerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalogue.game VALUES (?, now())", id);
        jdbc.update(
                """
            INSERT INTO catalogue.game_snapshot(publication_id, game_id, canonical_title, slug,
                cover_reference, cover_source, cover_usage_mode, cover_alternative_text, cover_usage_status)
            SELECT publication_id, ?, 'Repair fixture', ?, '/assets/covers/fallback.svg', 'Product',
                'product_owned', 'Cover unavailable', 'approved'
            FROM catalogue.catalogue_publication WHERE is_current
            """,
                id,
                "repair-" + id);
        jdbc.update(
                "INSERT INTO catalogue.game_external_reference(game_id, provider, provider_entity_type, provider_id) VALUES (?, 'IGDB', 'game', ?)",
                id,
                providerId);
        return id;
    }

    private void release(UUID game, String providerId, String platformId, String date) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalogue.game_release VALUES (?, ?, now())", id, game);
        jdbc.update(
                "INSERT INTO catalogue.release_external_reference VALUES ('IGDB', ?, ?, ?)",
                providerId,
                id,
                game);
        jdbc.update(
                """
            INSERT INTO catalogue.release_snapshot(publication_id, release_id, game_id,
                platform_id, region_id, date_precision, exact_date, release_status,
                source_kind, source_name, source_entity_type, provider_updated_at,
                last_synchronized_at, verification_level, review_status)
            SELECT publication_id, ?, ?, px.platform_id, rx.region_id, 'day', ?, 'announced',
                'external_provider', 'IGDB', 'release_date', timestamptz '2026-09-15 00:00:00Z',
                timestamptz '2026-09-21 18:19:49Z', 'provider_only', 'required'
            FROM catalogue.catalogue_publication
            CROSS JOIN catalogue.platform_external_reference px
            CROSS JOIN catalogue.region_external_reference rx
            WHERE is_current AND px.provider = 'IGDB' AND px.provider_id = ?
                AND rx.provider = 'IGDB' AND rx.provider_id = '8'
            """,
                id,
                game,
                Date.valueOf(date),
                platformId);
    }

    private long required() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM catalogue.release_snapshot WHERE source_name = 'IGDB' AND review_status = 'required'",
                Long.class);
    }

    private String version() {
        return jdbc.queryForObject(
                "SELECT catalogue_version FROM catalogue.catalogue_publication WHERE is_current",
                String.class);
    }

    private Object evidence() {
        return jdbc.queryForList(
                "SELECT release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, provider_updated_at, last_synchronized_at, last_verified_at, verification_level FROM catalogue.release_snapshot ORDER BY release_id");
    }
}
