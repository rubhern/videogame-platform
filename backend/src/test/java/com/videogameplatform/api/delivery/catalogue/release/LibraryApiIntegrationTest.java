package com.videogameplatform.api.delivery.catalogue.release;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Mis juegos (UC-026, #591): one exclusive private state per user and game. */
@SpringBootTest(
        properties = {
            "management.server.port=0",
            "platform.clock.fixed-instant=2026-08-13T10:00:00Z"
        })
@AutoConfigureMockMvc
class LibraryApiIntegrationTest {
    private static final String DB = PostgreSqlTestDatabase.isolatedDatabaseName("library_api");
    private static final String PATH = "/api/v1/me/library";
    private static final String WISHLIST = "/api/v1/me/wishlist";
    private static final String ALICE = "00000000-0000-4000-8000-0000000000a1";
    private static final String BOB = "00000000-0000-4000-8000-0000000000b1";
    // Released by the fixed evaluation date.
    private static final String ONE = "30000000-0000-4000-8000-000000000001";
    private static final String TWO = "30000000-0000-4000-8000-000000000002";
    private static final String THREE = "30000000-0000-4000-8000-000000000003";
    private static final String RELEASED = "30000000-0000-4000-8000-000000000005";
    // Not released by then: only future, announced or undated releases.
    private static final String UPCOMING = "30000000-0000-4000-8000-00000000000c";
    private static final String UNDATED = "30000000-0000-4000-8000-000000000008";

    @Autowired MockMvc mvc;
    private JdbcTemplate admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
    }

    @BeforeEach
    void resetLibrary() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DB),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.update("DELETE FROM catalogue.game_library_entry");
    }

    @Test
    void enforcesSessionOwnershipCsrfAndValidation() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(
                        put(PATH + "/games/" + ONE)
                                .contentType("application/json")
                                .content(body("playing")))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        put(PATH + "/games/" + ONE)
                                .with(login(ALICE))
                                .contentType("application/json")
                                .content(body("playing")))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(PATH + "/games/" + ONE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content("{\"state\":\"playing\",\"userId\":\"" + BOB + "\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(
                        put(PATH + "/games/" + ONE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content(body("finished")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        put(PATH + "/games/unknown-game")
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content(body("playing")))
                .andExpect(status().isNotFound());
        mvc.perform(get(PATH).with(login(ALICE)).queryParam("state", "finished"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FILTER_INVALID"));
        mvc.perform(get(PATH).with(login(ALICE)).queryParam("userId", BOB))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(
                        delete(PATH + "/games/" + ONE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content(body("playing")))
                .andExpect(status().isBadRequest());

        setState(ALICE, ONE, "playing");
        mvc.perform(get(PATH).with(login(BOB)))
                .andExpect(jsonPath("$.counts.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(PATH + "/states").with(login(BOB)).queryParam("gameIds", ONE))
                .andExpect(jsonPath("$.entries").isEmpty());
        mvc.perform(delete(PATH + "/games/" + ONE).with(login(BOB)).with(csrf().asHeader()))
                .andExpect(status().isNoContent());
        assertThat(state(ONE)).isEqualTo("playing");
    }

    @Test
    void keepsOneEntryPerGameAndRepeatsStatesIdempotentlyUnderConcurrency() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var changes = new ArrayList<Callable<Void>>();
            for (String value : List.of("playing", "completed", "playing", "wished")) {
                changes.add(
                        () -> {
                            setState(ALICE, ONE, value);
                            return null;
                        });
            }
            for (var change : executor.invokeAll(changes)) change.get();
        }
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM catalogue.game_library_entry", Long.class))
                .isEqualTo(1L);

        setState(ALICE, ONE, "completed");
        admin.update(
                "UPDATE catalogue.game_library_entry SET state_changed_at = '2026-01-01T00:00:00Z'");
        setState(ALICE, ONE, "completed");
        mvc.perform(get(PATH).with(login(ALICE)))
                .andExpect(jsonPath("$.items[0].state").value("completed"))
                .andExpect(jsonPath("$.items[0].stateChangedAt").value("2026-01-01T00:00:00Z"));
        setState(ALICE, ONE, "abandoned");
        mvc.perform(get(PATH).with(login(ALICE)))
                .andExpect(jsonPath("$.items[0].state").value("abandoned"))
                .andExpect(jsonPath("$.items[0].stateChangedAt").value("2026-08-13T10:00:00Z"));

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(delete(PATH + "/games/" + ONE).with(login(ALICE)).with(csrf().asHeader()))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get(PATH).with(login(ALICE))).andExpect(jsonPath("$.counts.total").value(0));
    }

    @Test
    void sharesTheEntryWithTheWishlistAndExportsEveryState() throws Exception {
        wish(ALICE, ONE);
        wish(ALICE, TWO);
        setState(ALICE, ONE, "playing");

        // Leaving Deseado takes the game off the wishlist and its scoped discovery.
        mvc.perform(get(WISHLIST).with(login(ALICE)))
                .andExpect(jsonPath("$.page.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].gameId").value(TWO));
        mvc.perform(get(WISHLIST + "/status").with(login(ALICE)).queryParam("gameIds", ONE, TWO))
                .andExpect(jsonPath("$.gameIds.length()").value(1))
                .andExpect(jsonPath("$.gameIds[0]").value(TWO));
        mvc.perform(
                        get(PATH + "/states")
                                .with(login(ALICE))
                                .queryParam("gameIds", ONE, TWO, "unknown-game"))
                .andExpect(jsonPath("$.entries.length()").value(2))
                .andExpect(jsonPath("$.entries[0].gameId").value(ONE))
                .andExpect(jsonPath("$.entries[0].state").value("playing"))
                .andExpect(jsonPath("$.entries[1].state").value("wished"));

        // Removing a wish leaves a game in another state untouched.
        mvc.perform(delete(WISHLIST + "/games/" + ONE).with(login(ALICE)).with(csrf().asHeader()))
                .andExpect(status().isNoContent());
        assertThat(state(ONE)).isEqualTo("playing");

        // Saving it again makes it wished and puts it last.
        wish(ALICE, ONE);
        mvc.perform(get(WISHLIST).with(login(ALICE)))
                .andExpect(jsonPath("$.items[0].gameId").value(TWO))
                .andExpect(jsonPath("$.items[1].gameId").value(ONE));
        setState(ALICE, ONE, "backlog");
        setState(ALICE, ONE, "wished");
        mvc.perform(get(WISHLIST).with(login(ALICE)))
                .andExpect(jsonPath("$.items[1].gameId").value(ONE));

        setState(ALICE, RELEASED, "completed");
        mvc.perform(get("/api/v1/me/data-export").with(login(ALICE)))
                .andExpect(jsonPath("$.wishlist.length()").value(2))
                .andExpect(jsonPath("$.library.length()").value(3))
                .andExpect(jsonPath("$.library[2].gameId").value(RELEASED))
                .andExpect(jsonPath("$.library[2].state").value("completed"))
                .andExpect(jsonPath("$.library[2].stateChangedAt").value("2026-08-13T10:00:00Z"));
        mvc.perform(get("/api/v1/me/data-export").with(login(BOB)))
                .andExpect(jsonPath("$.library").isEmpty());
    }

    @Test
    void pagesMostRecentChangesFirstWithCountsForEveryState() throws Exception {
        setState(ALICE, ONE, "playing");
        setState(ALICE, TWO, "playing");
        setState(ALICE, THREE, "completed");
        wish(ALICE, RELEASED);
        setState(BOB, RELEASED, "abandoned");
        admin.update(
                "UPDATE catalogue.game_library_entry SET state_changed_at = '2026-08-01T00:00:00Z' WHERE game_id = ?::uuid",
                THREE);

        var first =
                mvc.perform(get(PATH).with(login(ALICE)).queryParam("pageSize", "2"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.counts.total").value(4))
                        .andExpect(jsonPath("$.counts.wished").value(1))
                        .andExpect(jsonPath("$.counts.playing").value(2))
                        .andExpect(jsonPath("$.counts.completed").value(1))
                        .andExpect(jsonPath("$.counts.abandoned").value(0))
                        .andExpect(jsonPath("$.page.totalItems").value(4))
                        // Equal change times fall back to the game ID.
                        .andExpect(jsonPath("$.items[0].gameId").value(ONE))
                        .andExpect(jsonPath("$.items[0].released").value(true))
                        .andExpect(jsonPath("$.items[1].gameId").value(TWO))
                        .andExpect(jsonPath("$.items[1].canonicalTitle").isNotEmpty())
                        .andReturn();
        OpenApiResponseContract.load("/me/library")
                .assertJsonResponse(first.getResponse(), 200, "LibraryPage");
        mvc.perform(
                        get(PATH)
                                .with(login(ALICE))
                                .queryParam("pageSize", "2")
                                .queryParam("page", "2"))
                .andExpect(jsonPath("$.items[0].gameId").value(RELEASED))
                .andExpect(jsonPath("$.items[1].gameId").value(THREE));
        mvc.perform(get(PATH).with(login(ALICE)).queryParam("state", "playing"))
                .andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.counts.total").value(4))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].gameId").value(ONE));
        mvc.perform(get(PATH).with(login(ALICE)).queryParam("state", "abandoned"))
                .andExpect(jsonPath("$.page.totalItems").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(PATH).with(login(ALICE)).queryParam("page", "9"))
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.counts.total").value(4));
    }

    @Test
    void offersOnlyDeseadoUntilAReleaseHasOccurred() throws Exception {
        for (String game : List.of(UPCOMING, UNDATED)) {
            for (String value : List.of("backlog", "playing", "completed", "abandoned")) {
                var rejected =
                        mvc.perform(
                                        put(PATH + "/games/" + game)
                                                .with(login(ALICE))
                                                .with(csrf().asHeader())
                                                .contentType("application/json")
                                                .content(body(value)))
                                .andExpect(status().isUnprocessableContent())
                                .andExpect(jsonPath("$.code").value("GAME_NOT_RELEASED"))
                                .andExpect(jsonPath("$.violations[0].pointer").value("/state"))
                                .andReturn();
                OpenApiResponseContract.load("/me/library/games/{gameId}", "put")
                        .assertJsonResponse(rejected.getResponse(), 422, "Problem");
            }
        }
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM catalogue.game_library_entry", Long.class))
                .isZero();

        // Deseado stays open, and an existing wish cannot move on before the release either.
        setState(ALICE, UPCOMING, "wished");
        mvc.perform(
                        put(PATH + "/games/" + UPCOMING)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content(body("playing")))
                .andExpect(status().isUnprocessableContent());
        assertThat(state(UPCOMING)).isEqualTo("wished");
        setState(ALICE, RELEASED, "playing");
        mvc.perform(get(PATH).with(login(ALICE)))
                .andExpect(jsonPath("$.items[0].gameId").value(RELEASED))
                .andExpect(jsonPath("$.items[0].released").value(true))
                .andExpect(jsonPath("$.items[1].gameId").value(UPCOMING))
                .andExpect(jsonPath("$.items[1].released").value(false));
    }

    @Test
    void acceptsStatesExactlyForGamesWhosePageShowsAnOccurredRelease() throws Exception {
        // The SQL rule must match the effective release status the game page presents.
        var games =
                admin.queryForList(
                        """
                        SELECT game_id::text FROM catalogue.game_snapshot
                        WHERE publication_id = (SELECT publication_id
                                                FROM catalogue.catalogue_publication
                                                WHERE is_current)
                        ORDER BY game_id
                        """,
                        String.class);
        assertThat(games).hasSizeGreaterThan(5);
        var outcomes = new ArrayList<Boolean>();
        for (String game : games) {
            String details =
                    mvc.perform(get("/api/v1/games/" + game))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            boolean released = details.contains("\"status\":\"released\"");
            outcomes.add(released);
            mvc.perform(
                            put(PATH + "/games/" + game)
                                    .with(login(ALICE))
                                    .with(csrf().asHeader())
                                    .contentType("application/json")
                                    .content(body("playing")))
                    .andExpect(
                            released ? status().isNoContent() : status().isUnprocessableContent());
        }
        assertThat(outcomes).contains(true, false);
    }

    private String state(String gameId) {
        return admin.queryForObject(
                "SELECT state FROM catalogue.game_library_entry WHERE game_id = ?::uuid",
                String.class,
                gameId);
    }

    private void setState(String subject, String gameId, String value) throws Exception {
        mvc.perform(
                        put(PATH + "/games/" + gameId)
                                .with(login(subject))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content(body(value)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private void wish(String subject, String gameId) throws Exception {
        mvc.perform(put(WISHLIST + "/games/" + gameId).with(login(subject)).with(csrf().asHeader()))
                .andExpect(status().isNoContent());
    }

    private static String body(String value) {
        return "{\"state\":\"" + value + "\"}";
    }

    private static RequestPostProcessor login(String subject) {
        return oidcSession(
                token -> token.issuer("https://identity.example/realms/test").subject(subject));
    }
}
