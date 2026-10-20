package com.videogameplatform.catalogue.adapter.persistence.preferences;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.preferences.PlatformOption;
import com.videogameplatform.catalogue.application.preferences.PlatformPreferences;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL evidence for preferred platforms: whole-set replacement, the recorded choice that
 * tells a skipped first run from one never made, rejection of platforms outside the taxonomy,
 * per-user isolation and concurrent replacement; and the selectable taxonomy the choices come from.
 */
@Execution(ExecutionMode.SAME_THREAD)
class JdbcPlatformPreferenceStoreIntegrationTest {

    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String SWITCH_2 = "10000000-0000-4000-8000-000000000002";
    private static final String WINDOWS = "10000000-0000-4000-8000-000000000003";
    private static final String XBOX = "10000000-0000-4000-8000-000000000004";
    private static final String UNUSED = "10000000-0000-4000-8000-0000000000aa";
    private static final Instant SAVED = Instant.parse("2026-10-20T10:00:00Z");

    private static JdbcPlatformPreferenceStore store;
    private static JdbcSelectablePlatformsReadAdapter platforms;
    private static JdbcSelectablePlatformsReadAdapter unpublishedPlatforms;
    private static JdbcTemplate admin;

    @BeforeAll
    static void prepareDatabase() throws Exception {
        String database = PostgreSqlTestDatabase.isolatedDatabaseName("platform_preferences");
        DataSource runtime = migrate(database, true);
        TransactionTemplate read = readTransaction(runtime);
        TransactionTemplate write = new TransactionTemplate(new JdbcTransactionManager(runtime));
        write.setTimeout(5);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(runtime);
        store = new JdbcPlatformPreferenceStore(jdbc, read, write);
        platforms = new JdbcSelectablePlatformsReadAdapter(jdbc, read);
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(database),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        // A taxonomy entry no current release uses is never offered.
        admin.update(
                "INSERT INTO catalogue.platform (platform_id, code, display_name)"
                        + " VALUES (CAST(? AS uuid), 'atari-2600', 'Atari 2600')",
                UNUSED);
        DataSource unpublished =
                migrate(
                        PostgreSqlTestDatabase.isolatedDatabaseName("platforms_unpublished"),
                        false);
        unpublishedPlatforms =
                new JdbcSelectablePlatformsReadAdapter(
                        new NamedParameterJdbcTemplate(unpublished), readTransaction(unpublished));
    }

    @Test
    void aUserWhoNeverChoseIsNotConfigured() {
        assertThat(store.find(user())).isEqualTo(PlatformPreferences.notConfigured());
    }

    @Test
    void replacesTheWholeSetAndReadsItBackOrderedByName() {
        String user = user();

        PlatformPreferences first = store.replace(user, Set.of(PS5, SWITCH_2), SAVED).orElseThrow();
        assertThat(first.configured()).isTrue();
        assertThat(first.platforms())
                .containsExactly(
                        new PlatformOption(SWITCH_2, "Nintendo Switch 2"),
                        new PlatformOption(PS5, "PlayStation 5"));

        store.replace(user, Set.of(WINDOWS), SAVED.plusSeconds(1)).orElseThrow();
        assertThat(store.find(user).platforms())
                .extracting(PlatformOption::platformId)
                .containsExactly(WINDOWS);
    }

    @Test
    void anEmptySetClearsEveryPreferenceButStaysConfigured() {
        String user = user();
        store.replace(user, Set.of(PS5, XBOX), SAVED).orElseThrow();

        PlatformPreferences cleared = store.replace(user, Set.of(), SAVED).orElseThrow();

        assertThat(cleared).isEqualTo(new PlatformPreferences(true, List.of()));
        assertThat(store.find(user)).isEqualTo(cleared);
        // A skipped first run is the same recorded, empty choice.
        String skipped = user();
        assertThat(store.replace(skipped, Set.of(), SAVED)).contains(cleared);
    }

    @Test
    void aPlatformOutsideTheTaxonomyChangesNothing() {
        String user = user();
        store.replace(user, Set.of(PS5), SAVED).orElseThrow();

        assertThat(
                        store.replace(
                                user,
                                Set.of(SWITCH_2, "10000000-0000-4000-8000-0000000000ff"),
                                SAVED))
                .isEmpty();
        assertThat(store.replace(user, Set.of("platform_ps5"), SAVED)).isEmpty();

        assertThat(store.find(user).platforms())
                .extracting(PlatformOption::platformId)
                .containsExactly(PS5);
        // A first choice that fails leaves the user unconfigured, so the offer is not lost.
        String newcomer = user();
        assertThat(store.replace(newcomer, Set.of("10000000-0000-4000-8000-0000000000ff"), SAVED))
                .isEmpty();
        assertThat(store.find(newcomer).configured()).isFalse();
    }

    @Test
    void oneUsersChoiceNeverReachesAnother() {
        String alice = user();
        String bob = user();
        store.replace(alice, Set.of(PS5), SAVED).orElseThrow();
        store.replace(bob, Set.of(XBOX, WINDOWS), SAVED).orElseThrow();

        store.replace(alice, Set.of(), SAVED).orElseThrow();

        assertThat(store.find(alice).platforms()).isEmpty();
        assertThat(store.find(bob).platforms())
                .extracting(PlatformOption::platformId)
                .containsExactlyInAnyOrder(XBOX, WINDOWS);
    }

    @Test
    void concurrentReplacementsOfOneUserLeaveOneWholeSet() throws Exception {
        String user = user();
        List<Set<String>> sets = List.of(Set.of(PS5, SWITCH_2), Set.of(WINDOWS, XBOX));
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Object>> replacements = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                Set<String> set = sets.get(index % 2);
                replacements.add(() -> store.replace(user, set, SAVED).orElseThrow());
            }
            for (Future<Object> replacement : executor.invokeAll(replacements)) {
                replacement.get();
            }
        } finally {
            executor.shutdownNow();
        }

        Set<String> stored =
                Set.copyOf(
                        store.find(user).platforms().stream()
                                .map(PlatformOption::platformId)
                                .toList());
        assertThat(sets).contains(stored);
    }

    @Test
    void offersOnlyPlatformsACurrentReleaseUsesOrderedByName() {
        var list = platforms.find(500).orElseThrow();

        assertThat(list.publicationVersion()).isNotBlank();
        assertThat(list.platforms())
                .extracting(PlatformOption::name)
                .doesNotContain("Atari 2600")
                .isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER)
                .contains("PlayStation 5");
        assertThat(platforms.find(1).orElseThrow().platforms()).hasSize(1);
    }

    @Test
    void anUnpublishedCatalogueHasNoSelectablePlatforms() {
        assertThat(unpublishedPlatforms.find(500)).isEmpty();
    }

    private static String user() {
        return UUID.randomUUID().toString();
    }

    private static TransactionTemplate readTransaction(DataSource runtime) {
        TransactionTemplate read = new TransactionTemplate(new JdbcTransactionManager(runtime));
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setTimeout(5);
        return read;
    }

    private static DataSource migrate(String database, boolean seed) throws Exception {
        PostgreSqlTestDatabase.createDatabase(database);
        Flyway.configure()
                .dataSource(
                        PostgreSqlTestDatabase.adminUrl(database),
                        PostgreSqlTestDatabase.migratorUsername(),
                        PostgreSqlTestDatabase.migratorPassword())
                .locations(
                        seed
                                ? new String[] {"classpath:db/migration", "classpath:db/dev-seed"}
                                : new String[] {"classpath:db/migration"})
                .load()
                .migrate();
        return new DriverManagerDataSource(
                PostgreSqlTestDatabase.runtimeUrl(database),
                PostgreSqlTestDatabase.runtimeUsername(),
                PostgreSqlTestDatabase.runtimePassword());
    }
}
