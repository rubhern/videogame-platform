package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.domain.ReleaseDate;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcReleaseBrowseReadAdapterIntegrationTest {

    private static final String DATABASE_NAME = "release_browse_adapter";
    private static final String PLATFORM_PLAYSTATION_5 = "10000000-0000-4000-8000-000000000001";
    private static final String PLATFORM_WINDOWS_PC = "10000000-0000-4000-8000-000000000003";
    private static final String PLATFORM_XBOX_SERIES = "10000000-0000-4000-8000-000000000004";
    private static final String REGION_WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String REGION_UNKNOWN = "20000000-0000-4000-8000-000000000003";
    private static JdbcTemplate jdbcTemplate;
    private static JdbcTemplate adminJdbcTemplate;
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
        adminJdbcTemplate =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE_NAME),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
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

    @ParameterizedTest(name = "{0} with platform={1} and region={2}")
    @MethodSource("filterCombinations")
    void composesOptionalFiltersWithoutChangingCountOrOrder(
            BrowseReleasesUseCase.View view,
            String platformId,
            String regionId,
            List<String> expectedTitles) {
        var result =
                adapter.findPublishedReleases(criteria(view, 1, 20, platformId, regionId, true))
                        .orElseThrow();

        assertThat(result.totalItems()).isEqualTo(expectedTitles.size());
        assertThat(result.items())
                .extracting(ReleaseBrowseReadPort.Item::canonicalTitle)
                .containsExactlyElementsOf(expectedTitles);
    }

    @Test
    void excludesUnknownUpcomingDatesWhenPolicyRequiresKnownDates() {
        var result =
                adapter.findPublishedReleases(
                                criteria(
                                        BrowseReleasesUseCase.View.UPCOMING,
                                        1,
                                        20,
                                        null,
                                        null,
                                        false))
                        .orElseThrow();

        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.items())
                .extracting(ReleaseBrowseReadPort.Item::canonicalTitle)
                .containsExactly("Fable");
    }

    @Test
    void usesReleaseIdAsTheUniqueFinalTieBreakerAcrossPages() {
        String firstRelease = "50000000-0000-4000-8000-000000000010";
        String secondRelease = "50000000-0000-4000-8000-000000000011";
        insertTiedRelease(firstRelease, "10000000-0000-4000-8000-000000000001");
        insertTiedRelease(secondRelease, "10000000-0000-4000-8000-000000000004");
        try {
            ReleaseBrowseReadPort.Criteria firstPage =
                    criteria(BrowseReleasesUseCase.View.UPCOMING, 1, 1);
            ReleaseBrowseReadPort.Criteria secondPage =
                    criteria(BrowseReleasesUseCase.View.UPCOMING, 2, 1);

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

    @Test
    void rejectsAnUnknownPersistedEnumAsNonRetryableInvalidData() {
        String releaseId = "40000000-0000-4000-8000-000000000006";
        adminJdbcTemplate.execute(
                "ALTER TABLE catalogue.release_snapshot DROP CONSTRAINT ck_release_snapshot_source_kind");
        adminJdbcTemplate.update(
                "UPDATE catalogue.release_snapshot SET source_kind = 'corrupt' WHERE release_id = ?::uuid",
                releaseId);
        try {
            assertThatThrownBy(
                            () ->
                                    adapter.findPublishedReleases(
                                            criteria(BrowseReleasesUseCase.View.RECENT, 1, 20)))
                    .isInstanceOf(CatalogueDataInvalidException.class)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported source kind");
        } finally {
            adminJdbcTemplate.update(
                    "UPDATE catalogue.release_snapshot SET source_kind = 'product_curated' WHERE release_id = ?::uuid",
                    releaseId);
            adminJdbcTemplate.execute(
                    "ALTER TABLE catalogue.release_snapshot ADD CONSTRAINT ck_release_snapshot_source_kind CHECK (source_kind IN ('external_provider', 'product_curated', 'official_source'))");
        }
    }

    private static ReleaseBrowseReadPort.Criteria criteria(
            BrowseReleasesUseCase.View view, int page, int pageSize) {
        return criteria(view, page, pageSize, null, null, true);
    }

    private static ReleaseBrowseReadPort.Criteria criteria(
            BrowseReleasesUseCase.View view,
            int page,
            int pageSize,
            String platformId,
            String regionId,
            boolean includeUnknownUpcomingDates) {
        LocalDate from =
                view == BrowseReleasesUseCase.View.RECENT
                        ? LocalDate.of(2026, 2, 13)
                        : LocalDate.of(2026, 8, 13);
        LocalDate to =
                view == BrowseReleasesUseCase.View.RECENT
                        ? LocalDate.of(2026, 8, 13)
                        : LocalDate.of(2027, 2, 13);
        return new ReleaseBrowseReadPort.Criteria(
                view,
                new ReleaseBrowseReadPort.Window(from, to),
                platformId,
                regionId,
                new ReleaseBrowseReadPort.Pagination(page, pageSize, (long) (page - 1) * pageSize),
                includeUnknownUpcomingDates);
    }

    private static Stream<Arguments> filterCombinations() {
        return Stream.of(
                Arguments.of(
                        BrowseReleasesUseCase.View.RECENT,
                        null,
                        null,
                        List.of("Pragmata", "Resident Evil Requiem")),
                Arguments.of(
                        BrowseReleasesUseCase.View.RECENT,
                        PLATFORM_PLAYSTATION_5,
                        null,
                        List.of("Resident Evil Requiem")),
                Arguments.of(
                        BrowseReleasesUseCase.View.RECENT,
                        null,
                        REGION_WORLDWIDE,
                        List.of("Pragmata")),
                Arguments.of(
                        BrowseReleasesUseCase.View.RECENT,
                        PLATFORM_WINDOWS_PC,
                        REGION_WORLDWIDE,
                        List.of("Pragmata")),
                Arguments.of(
                        BrowseReleasesUseCase.View.UPCOMING,
                        null,
                        null,
                        List.of("Fable", "The Witcher IV")),
                Arguments.of(
                        BrowseReleasesUseCase.View.UPCOMING,
                        PLATFORM_XBOX_SERIES,
                        null,
                        List.of("Fable")),
                Arguments.of(
                        BrowseReleasesUseCase.View.UPCOMING,
                        null,
                        REGION_UNKNOWN,
                        List.of("The Witcher IV")),
                Arguments.of(
                        BrowseReleasesUseCase.View.UPCOMING,
                        PLATFORM_WINDOWS_PC,
                        REGION_UNKNOWN,
                        List.of("The Witcher IV")));
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
