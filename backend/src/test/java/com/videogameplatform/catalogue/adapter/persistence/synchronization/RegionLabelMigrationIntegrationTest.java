package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.domain.RegionLabel;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** The #213 repair changes region presentation only, with the rule runtime acquisition applies. */
class RegionLabelMigrationIntegrationTest {

    private static final String IDENTITY =
            "SELECT r.region_id, r.code, x.provider, x.provider_id FROM catalogue.region r"
                    + " LEFT JOIN catalogue.region_external_reference x USING (region_id)"
                    + " ORDER BY r.region_id";
    private static final String RELEASE_REGIONS =
            "SELECT release_id, region_id FROM catalogue.release_snapshot ORDER BY release_id";

    @Test
    void repairsStoredLabelsWithoutChangingRegionIdentityReferencesOrReleases() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("region_labels");
        PostgreSqlTestDatabase.createDatabase(database);
        var configuration =
                Flyway.configure()
                        .dataSource(
                                PostgreSqlTestDatabase.adminUrl(database),
                                PostgreSqlTestDatabase.migratorUsername(),
                                PostgreSqlTestDatabase.migratorPassword())
                        .locations("classpath:db/migration", "classpath:db/dev-seed");
        configuration.target("20261002.120000").load().migrate();
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.runtimeUrl(database),
                                PostgreSqlTestDatabase.runtimeUsername(),
                                PostgreSqlTestDatabase.runtimePassword()));
        // Regions as the real IGDB-synchronized catalogue stored them before the label rule, plus
        // an unmapped technical descriptor and a name a provider already wrote for people.
        acquire(jdbc, "41000000-0000-4000-8000-000000000007", "asia", "asia", "7");
        acquire(jdbc, "41000000-0000-4000-8000-000000000003", "australia", "australia", "3");
        acquire(jdbc, "41000000-0000-4000-8000-000000000006", "china", "china", "6");
        acquire(jdbc, "41000000-0000-4000-8000-00000000000a", "brazil", "brazil", "10");
        acquire(jdbc, "41000000-0000-4000-8000-000000000009", "korea", "korea", "9");
        acquire(jdbc, "41000000-0000-4000-8000-000000000004", "new-zealand", "new_zealand", "4");
        acquire(jdbc, "41000000-0000-4000-8000-000000009001", "middle-east", "middle_east", "9001");
        acquire(
                jdbc,
                "41000000-0000-4000-8000-000000009002",
                "asia-pacific",
                "Asia-Pacific",
                "9002");
        Map<String, String> descriptors = labels(jdbc);
        var identity = jdbc.queryForList(IDENTITY);
        var releaseRegions = jdbc.queryForList(RELEASE_REGIONS);
        assertThat(releaseRegions).isNotEmpty();

        configuration.target("latest").load().migrate();

        Map<String, String> repaired = labels(jdbc);
        assertThat(repaired)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.ofEntries(
                                Map.entry("worldwide", "Mundial"),
                                Map.entry("europe", "Europa"),
                                Map.entry("north-america", "Norteamérica"),
                                Map.entry("japan", "Japón"),
                                Map.entry("unknown", "Sin región confirmada"),
                                Map.entry("asia", "Asia"),
                                Map.entry("australia", "Australia"),
                                Map.entry("china", "China"),
                                Map.entry("brazil", "Brasil"),
                                Map.entry("korea", "Corea"),
                                Map.entry("new-zealand", "Nueva Zelanda"),
                                Map.entry("middle-east", "Middle East"),
                                Map.entry("asia-pacific", "Asia-Pacific")));
        // The repair and runtime acquisition apply one rule to the descriptor a region came with.
        descriptors.remove("unknown");
        descriptors.forEach(
                (code, descriptor) ->
                        assertThat(repaired.get(code))
                                .as(code)
                                .isEqualTo(RegionLabel.fromDescriptor(descriptor).value()));
        assertThat(jdbc.queryForList(IDENTITY)).isEqualTo(identity);
        assertThat(jdbc.queryForList(RELEASE_REGIONS)).isEqualTo(releaseRegions);
    }

    private static void acquire(
            JdbcTemplate jdbc, String regionId, String code, String descriptor, String igdbId) {
        jdbc.update(
                "INSERT INTO catalogue.region (region_id, code, display_name) VALUES (?::uuid, ?, ?)",
                regionId,
                code,
                descriptor);
        jdbc.update(
                "INSERT INTO catalogue.region_external_reference (provider, provider_id, region_id)"
                        + " VALUES ('IGDB', ?, ?::uuid)",
                igdbId,
                regionId);
    }

    private static Map<String, String> labels(JdbcTemplate jdbc) {
        Map<String, String> labels = new LinkedHashMap<>();
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT code, display_name FROM catalogue.region ORDER BY code");
        rows.forEach(row -> labels.put((String) row.get("code"), (String) row.get("display_name")));
        return labels;
    }
}
