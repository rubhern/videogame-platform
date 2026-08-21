package com.videogameplatform.catalogue.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

class JdbcReleaseBrowseReadAdapterIntegrationTest {

    private static final String DATABASE_NAME = "release_browse_adapter";
    private static JdbcTemplate jdbcTemplate;
    private static JdbcReleaseBrowseReadAdapter adapter;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        PostgreSqlTestDatabase.createDatabase(DATABASE_NAME);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations("classpath:db/migration", "classpath:db/dev-seed")
                .load()
                .migrate();
        DataSource dataSource =
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        adapter =
                new JdbcReleaseBrowseReadAdapter(
                        jdbcTemplate, new JdbcTransactionManager(dataSource));
    }

    @Test
    void filtersCountsOrdersAndPagesInPostgreSql() {
        var firstPage =
                adapter.findPublishedReleases(criteria(BrowseReleasesUseCase.View.RECENT, 1, 1))
                        .orElseThrow();
        var secondPage =
                adapter.findPublishedReleases(criteria(BrowseReleasesUseCase.View.RECENT, 2, 1))
                        .orElseThrow();

        assertThat(firstPage.publicationVersion()).isEqualTo("prototype-catalogue-v1");
        assertThat(firstPage.totalItems()).isEqualTo(2);
        assertThat(firstPage.items()).singleElement();
        assertThat(firstPage.items().getFirst().canonicalTitle()).isEqualTo("Pragmata");
        assertThat(secondPage.items()).singleElement();
        assertThat(secondPage.items().getFirst().canonicalTitle())
                .isEqualTo("Resident Evil Requiem");
        assertThat(firstPage.items())
                .allSatisfy(
                        item ->
                                assertThat(item.releaseDate())
                                        .isNotInstanceOf(ReleaseDate.Unknown.class));
    }

    @Test
    void keepsUnknownUpcomingDatesExplicitAndLast() {
        var result =
                adapter.findPublishedReleases(criteria(BrowseReleasesUseCase.View.UPCOMING, 1, 20))
                        .orElseThrow();

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().releaseDate())
                .isInstanceOf(ReleaseDate.YearOnly.class);
        assertThat(result.items().getLast().releaseDate()).isInstanceOf(ReleaseDate.Unknown.class);
    }

    @Test
    void usesReleaseIdAsTheUniqueFinalTieBreakerAcrossPages() {
        String firstRelease = "50000000-0000-4000-8000-000000000010";
        String secondRelease = "50000000-0000-4000-8000-000000000011";
        insertTiedRelease(firstRelease, "10000000-0000-4000-8000-000000000001");
        insertTiedRelease(secondRelease, "10000000-0000-4000-8000-000000000004");
        try {
            ReleaseBrowseReadPort.Criteria firstPage =
                    criteria(
                            BrowseReleasesUseCase.View.UPCOMING,
                            1,
                            1,
                            "30000000-0000-4000-8000-000000000001");
            ReleaseBrowseReadPort.Criteria secondPage =
                    criteria(
                            BrowseReleasesUseCase.View.UPCOMING,
                            2,
                            1,
                            "30000000-0000-4000-8000-000000000001");

            assertThat(adapter.findPublishedReleases(firstPage).orElseThrow().items())
                    .extracting(ReleaseBrowseReadPort.Item::releaseId)
                    .containsExactly(firstRelease);
            assertThat(adapter.findPublishedReleases(secondPage).orElseThrow().items())
                    .extracting(ReleaseBrowseReadPort.Item::releaseId)
                    .containsExactly(secondRelease);
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM catalogue.release_snapshot WHERE release_id IN (?::uuid, ?::uuid)",
                    firstRelease,
                    secondRelease);
            jdbcTemplate.update(
                    "DELETE FROM catalogue.game_release WHERE release_id IN (?::uuid, ?::uuid)",
                    firstRelease,
                    secondRelease);
        }
    }

    private static ReleaseBrowseReadPort.Criteria criteria(
            BrowseReleasesUseCase.View view, int page, int pageSize) {
        return criteria(view, page, pageSize, null);
    }

    private static ReleaseBrowseReadPort.Criteria criteria(
            BrowseReleasesUseCase.View view, int page, int pageSize, String gameId) {
        LocalDate from =
                view == BrowseReleasesUseCase.View.RECENT
                        ? LocalDate.of(2026, 2, 13)
                        : LocalDate.of(2026, 8, 13);
        LocalDate to =
                view == BrowseReleasesUseCase.View.RECENT
                        ? LocalDate.of(2026, 8, 13)
                        : LocalDate.of(2027, 2, 13);
        String platformId = null;
        String regionId = null;
        // The public criteria deliberately has no game filter. Tied fixtures use a unique
        // earliest date so they occupy the first two pages without changing the port.
        return new ReleaseBrowseReadPort.Criteria(
                view,
                new ReleaseBrowseReadPort.Window(from, to),
                platformId,
                regionId,
                new ReleaseBrowseReadPort.Pagination(page, pageSize, (long) (page - 1) * pageSize),
                true);
    }

    private static void insertTiedRelease(String releaseId, String platformId) {
        jdbcTemplate.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES (?::uuid, '30000000-0000-4000-8000-000000000001', now())",
                releaseId);
        jdbcTemplate.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id, platform_id, region_id, date_precision, exact_date, release_status, source_kind, source_name, source_entity_type, last_synchronized_at, verification_level, review_status) VALUES ('00000000-0000-4000-8000-000000000001', ?::uuid, '30000000-0000-4000-8000-000000000001', ?::uuid, '20000000-0000-4000-8000-000000000002', 'day', DATE '2026-08-14', 'scheduled', 'product_curated', 'tie test', 'release', now(), 'verified', 'not_required')",
                releaseId,
                platformId);
    }
}
