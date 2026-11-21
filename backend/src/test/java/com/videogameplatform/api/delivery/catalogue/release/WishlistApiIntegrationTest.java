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
import java.util.List;
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

@SpringBootTest(
        properties = {
            "management.server.port=0",
            "platform.clock.fixed-instant=2026-08-13T10:00:00Z"
        })
@AutoConfigureMockMvc
class WishlistApiIntegrationTest {
    private static final String DB = PostgreSqlTestDatabase.isolatedDatabaseName("wishlist_api");
    private static final String PATH = "/api/v1/me/wishlist";
    private static final String ALICE = "00000000-0000-4000-8000-0000000000a1";
    private static final String BOB = "00000000-0000-4000-8000-0000000000b1";
    private static final String UPCOMING = "30000000-0000-4000-8000-00000000000c";
    private static final String MONTH = "30000000-0000-4000-8000-00000000000a";
    private static final String QUARTER = "30000000-0000-4000-8000-00000000000b";
    private static final String RELEASED = "30000000-0000-4000-8000-000000000005";
    private static final String UNDATED = "30000000-0000-4000-8000-000000000008";

    @Autowired MockMvc mvc;
    private JdbcTemplate admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
    }

    @BeforeEach
    void resetWishes() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DB),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.update("DELETE FROM catalogue.game_library_entry");
    }

    @Test
    void enforcesSessionOwnershipCsrfAndIdempotenceUnderConcurrentSaves() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH + "/games/" + UPCOMING)).andExpect(status().isUnauthorized());
        mvc.perform(put(PATH + "/games/" + UPCOMING).with(login(ALICE)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(PATH + "/games/" + UPCOMING)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(PATH + "/games/" + UPCOMING)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .queryParam("userId", BOB))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(put(PATH + "/games/unknown-game").with(login(ALICE)).with(csrf().asHeader()))
                .andExpect(status().isNotFound());

        mvc.perform(
                        put(PATH + "/games/" + UPCOMING)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content("{\"userId\":\"" + BOB + "\"}"))
                .andExpect(status().isBadRequest());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var saves =
                    executor.invokeAll(
                            List.<java.util.concurrent.Callable<Void>>of(
                                    () -> {
                                        add(ALICE, UPCOMING);
                                        return null;
                                    },
                                    () -> {
                                        add(ALICE, UPCOMING);
                                        return null;
                                    }));
            for (var save : saves) save.get();
        }
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM catalogue.game_library_entry", Long.class))
                .isEqualTo(1L);
        mvc.perform(get(PATH + "/status").with(login(ALICE)).queryParam("gameIds", UPCOMING, MONTH))
                .andExpect(jsonPath("$.gameIds[0]").value(UPCOMING))
                .andExpect(jsonPath("$.gameIds.length()").value(1));
        mvc.perform(get(PATH + "/status").with(login(BOB)).queryParam("gameIds", UPCOMING))
                .andExpect(jsonPath("$.gameIds").isEmpty());
        mvc.perform(delete(PATH + "/games/" + UPCOMING).with(login(BOB)).with(csrf().asHeader()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/me/data-export").with(login(ALICE)))
                .andExpect(jsonPath("$.wishlist.length()").value(1))
                .andExpect(jsonPath("$.wishlist[0].gameId").value(UPCOMING))
                .andExpect(jsonPath("$.wishlist[0].addedAt").value("2026-08-13T10:00:00Z"));
        mvc.perform(get("/api/v1/me/data-export").with(login(BOB)))
                .andExpect(jsonPath("$.wishlist").isEmpty());

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(
                            delete(PATH + "/games/" + UPCOMING)
                                    .with(login(ALICE))
                                    .with(csrf().asHeader()))
                    .andExpect(status().isNoContent())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get(PATH).with(login(ALICE))).andExpect(jsonPath("$.page.totalItems").value(0));
    }

    @Test
    void preservesSavedOrderAndShowsPreciseAndApproximateDatesAcrossPages() throws Exception {
        // This game's original undated release remains valid after cancelling its other date.
        admin.update(
                "UPDATE catalogue.release_snapshot SET release_status = 'cancelled' "
                        + "WHERE release_id = '40000000-0000-4000-8000-000000000013'");
        try {
            for (String game : List.of(UPCOMING, MONTH, QUARTER, RELEASED, UNDATED))
                add(ALICE, game);
            add(BOB, UPCOMING);
            var first =
                    mvc.perform(get(PATH).with(login(ALICE)).queryParam("pageSize", "2"))
                            .andExpect(status().isOk())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.page.totalItems").value(5))
                            .andExpect(jsonPath("$.items[0].gameId").value(UPCOMING))
                            .andExpect(jsonPath("$.items[0].releaseDate.value").value("2026-09-25"))
                            .andExpect(jsonPath("$.items[1].gameId").value(MONTH))
                            .andExpect(jsonPath("$.items[1].releaseDate.precision").value("month"))
                            .andReturn();
            OpenApiResponseContract.load("/me/wishlist")
                    .assertJsonResponse(first.getResponse(), 200, "WishlistPage");
            mvc.perform(
                            get(PATH)
                                    .with(login(ALICE))
                                    .queryParam("pageSize", "2")
                                    .queryParam("page", "2"))
                    .andExpect(jsonPath("$.items[0].gameId").value(QUARTER))
                    .andExpect(jsonPath("$.items[0].releaseDate.precision").value("quarter"))
                    .andExpect(jsonPath("$.items[1].gameId").value(RELEASED))
                    .andExpect(jsonPath("$.items[1].timing").value("released"));
            mvc.perform(
                            get(PATH)
                                    .with(login(ALICE))
                                    .queryParam("pageSize", "2")
                                    .queryParam("page", "3"))
                    .andExpect(jsonPath("$.items[0].gameId").value(UNDATED))
                    .andExpect(jsonPath("$.items[0].timing").value("undated"))
                    .andExpect(jsonPath("$.items[0].releaseDate.precision").value("unknown"))
                    .andExpect(jsonPath("$.items[0].releaseDate.value").isEmpty());
            mvc.perform(get(PATH).with(login(BOB)))
                    .andExpect(jsonPath("$.page.totalItems").value(1));
        } finally {
            admin.update(
                    "UPDATE catalogue.release_snapshot SET release_status = 'announced' "
                            + "WHERE release_id = '40000000-0000-4000-8000-000000000013'");
        }
    }

    @Test
    void scopesCalendarCountsFacetsAndExplorerPagesBeforeReturningData() throws Exception {
        add(ALICE, MONTH);
        add(ALICE, QUARTER);
        add(BOB, UPCOMING);
        mvc.perform(
                        get(PATH + "/releases")
                                .with(login(ALICE))
                                .queryParam("from", "2026-09-01")
                                .queryParam("to", "2026-10-31")
                                .queryParam("includeApproximateDates", "true")
                                .queryParam("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.page.totalItems").value(2))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(
                        get(PATH + "/release-calendar")
                                .with(login(ALICE))
                                .queryParam("month", "2026-09"))
                .andExpect(jsonPath("$.days").isEmpty())
                .andExpect(jsonPath("$.availableFilters.platforms").isEmpty());
        mvc.perform(get(PATH + "/release-calendar").with(login(BOB)).queryParam("month", "2026-09"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.days[0].date").value("2026-09-25"))
                .andExpect(jsonPath("$.days[0].gameCount").value(1));
        mvc.perform(
                        get(PATH + "/releases")
                                .with(login(BOB))
                                .queryParam("from", "2026-09-25")
                                .queryParam("to", "2026-09-25"))
                .andExpect(jsonPath("$.page.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].gameId").value(UPCOMING));
        mvc.perform(get("/api/v1/release-calendar").queryParam("month", "2026-09"))
                .andExpect(status().isOk())
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.days[0].gameCount").value(1));
    }

    @Test
    void movesAnySavedGameRelativeToAnAnchorAcrossPagesAndExportsPositions() throws Exception {
        add(ALICE, UPCOMING);
        add(ALICE, RELEASED);
        add(ALICE, MONTH);
        add(BOB, MONTH);
        assertThat(
                        mvc.perform(
                                        put(PATH + "/games/" + MONTH + "/position")
                                                .with(login(ALICE))
                                                .contentType("application/json")
                                                .content(moveBody(RELEASED, "before")))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(403);
        mvc.perform(
                        put(PATH + "/games/" + MONTH + "/position")
                                .with(login(BOB))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content(moveBody(UPCOMING, "after")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WISHLIST_GAME_NOT_FOUND"));

        move(ALICE, MONTH, UPCOMING, "before");
        move(ALICE, RELEASED, MONTH, "after");
        move(ALICE, RELEASED, MONTH, "after");
        mvc.perform(get(PATH).with(login(ALICE)).queryParam("pageSize", "1"))
                .andExpect(jsonPath("$.items[0].gameId").value(MONTH));
        mvc.perform(
                        get(PATH)
                                .with(login(ALICE))
                                .queryParam("pageSize", "1")
                                .queryParam("page", "2"))
                .andExpect(jsonPath("$.items[0].gameId").value(RELEASED));
        mvc.perform(get(PATH).with(login(BOB)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].gameId").value(MONTH));
        mvc.perform(get("/api/v1/me/data-export").with(login(ALICE)))
                .andExpect(jsonPath("$.wishlist[0].position").value(2))
                .andExpect(jsonPath("$.wishlist[1].position").value(1))
                .andExpect(jsonPath("$.wishlist[2].position").value(3));
    }

    @Test
    void concurrentMovesKeepOneDistinctPositionPerGame() throws Exception {
        for (String game : List.of(UPCOMING, MONTH, RELEASED)) add(ALICE, game);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var operations =
                    executor.invokeAll(
                            List.<java.util.concurrent.Callable<Void>>of(
                                    () -> {
                                        move(ALICE, UPCOMING, MONTH, "after");
                                        return null;
                                    },
                                    () -> {
                                        move(ALICE, RELEASED, MONTH, "before");
                                        return null;
                                    }));
            for (var operation : operations) operation.get();
        }
        mvc.perform(get(PATH).with(login(ALICE)))
                .andExpect(jsonPath("$.items[0].gameId").value(RELEASED))
                .andExpect(jsonPath("$.items[1].gameId").value(MONTH))
                .andExpect(jsonPath("$.items[2].gameId").value(UPCOMING));
    }

    private void move(String subject, String gameId, String anchor, String placement)
            throws Exception {
        mvc.perform(
                        put(PATH + "/games/" + gameId + "/position")
                                .with(login(subject))
                                .with(csrf().asHeader())
                                .contentType("application/json")
                                .content(moveBody(anchor, placement)))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private static String moveBody(String anchor, String placement) {
        return """
                {"anchorGameId":"%s","placement":"%s"}
                """
                .formatted(anchor, placement);
    }

    private void add(String subject, String gameId) throws Exception {
        mvc.perform(put(PATH + "/games/" + gameId).with(login(subject)).with(csrf().asHeader()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private static RequestPostProcessor login(String subject) {
        return oidcSession(
                token -> token.issuer("https://identity.example/realms/test").subject(subject));
    }
}
