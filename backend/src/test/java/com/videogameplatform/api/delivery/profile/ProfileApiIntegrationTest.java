package com.videogameplatform.api.delivery.profile;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static com.videogameplatform.test.RegisteredOidcSession.oidcSessionWithUserInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.catalogue.application.details.GameVisitsChanged;
import com.videogameplatform.identity.application.AccountDeletionStore;
import com.videogameplatform.identity.application.DeletionExecutorAvailability;
import com.videogameplatform.identity.domain.UserId;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real Flyway/PostgreSQL, BFF authorization and generated HTTP contracts, no live identity service. */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class ProfileApiIntegrationTest {
    static final String DB = PostgreSqlTestDatabase.isolatedDatabaseName("profile_api");
    static final String ISSUER = "https://identity.example/realms/test";
    static final String ALICE = "00000000-0000-4000-8000-0000000000a1";
    static final String BOB = "00000000-0000-4000-8000-0000000000b1";
    static final String PROFILE = "/api/v1/me/profile";
    static final String SHARING = "/api/v1/me/public-profile";
    static final String PUBLIC = "/api/v1/profiles/";
    static final String DELETE = "/api/v1/me/account-deletion";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountDeletionStore store;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean DeletionExecutorAvailability erasure;

    JdbcTemplate admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
    }

    @BeforeEach
    void resetData() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DB),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.update("DELETE FROM identity.account_deletion");
        admin.update("DELETE FROM identity.player_alias");
        admin.update("DELETE FROM catalogue.game_library_entry");
        admin.update("DELETE FROM ratings.rating");
        admin.update("DELETE FROM catalogue.platform_preference_choice");
        when(erasure.available()).thenReturn(true);
    }

    static RequestPostProcessor login(String subject) {
        return oidcSession(
                token ->
                        token.issuer(ISSUER)
                                .subject(subject)
                                .claim("email", "private@example.test")
                                .claim("email_verified", false)
                                .claim("name", "Cuenta privada"));
    }

    static String owner(String subject) {
        return UserId.fromIssuerAndSubject(ISSUER, subject).value();
    }

    @Test
    void optedInSectionsShareOnlyPublicFieldsAndRevocationAppliesToTheNextRead() throws Exception {
        var games =
                admin.queryForList(
                        "SELECT game_id::text FROM catalogue.game ORDER BY game_id LIMIT 2",
                        String.class);
        admin.update(
                """
                INSERT INTO ratings.game_listing (game_id, slug, canonical_title, normalized_title, cover_kind)
                SELECT s.game_id, s.slug, s.canonical_title, lower(s.canonical_title), 'unavailable'
                FROM catalogue.game_snapshot s
                JOIN catalogue.catalogue_publication p ON p.publication_id = s.publication_id AND p.is_current
                WHERE s.game_id IN (?::uuid, ?::uuid) ON CONFLICT (game_id) DO NOTHING
                """,
                games.getFirst(),
                games.getLast());
        assertThat(games).hasSize(2);
        var first = games.getFirst();
        var second = games.getLast();
        rating(ALICE, first, 8);
        rating(ALICE, second, 7);
        rating(BOB, first, 1);
        admin.update(
                "UPDATE ratings.rating SET updated_at = '2026-11-20T12:00:00Z', "
                        + "created_at = '2026-11-20T12:00:00Z'");
        for (String game : List.of(second, first)) {
            mvc.perform(
                            put("/api/v1/me/wishlist/games/" + game)
                                    .with(login(ALICE))
                                    .with(csrf().asHeader()))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(get(SHARING)).andExpect(status().isUnauthorized());
        mvc.perform(get(SHARING).with(login(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.alias").doesNotExist());
        mvc.perform(get(PUBLIC + "luna")).andExpect(status().isNotFound());

        mvc.perform(
                        put(SHARING)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sharingBody("luna", null, true, false, false)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PUBLIC_PROFILE_SECTION_REQUIRED"));
        mvc.perform(get(SHARING).with(login(ALICE))).andExpect(jsonPath("$.alias").doesNotExist());
        var revision = saveSharing(ALICE, "LuNa", null, false, true, true);
        mvc.perform(get(SHARING).with(login(ALICE)))
                .andExpect(jsonPath("$.shareRatings").value(false))
                .andExpect(jsonPath("$.shareWishlist").value(false));
        mvc.perform(get(PUBLIC + "luna").with(login(ALICE))).andExpect(status().isNotFound());
        mvc.perform(
                        put(SHARING)
                                .with(login(ALICE))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sharingBody("luna", revision, true, true, true)))
                .andExpect(status().isForbidden());
        mvc.perform(
                        put(SHARING)
                                .with(login(BOB))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .queryParam("userId", owner(ALICE))
                                .content(sharingBody("luna", revision, true, true, true)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(
                        put(SHARING)
                                .with(login(BOB))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sharingBody("luna", revision, true, true, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLIC_PROFILE_REVISION_CONFLICT"));

        revision = saveSharing(ALICE, "luna", revision, true, true, true);
        var header =
                mvc.perform(get(PUBLIC + "LUNA"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.alias").value("luna"))
                        .andExpect(jsonPath("$.sections.length()").value(2))
                        .andExpect(jsonPath("$.sections[0]").value("ratings"))
                        .andExpect(jsonPath("$.sections[1]").value("wishlist"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn()
                        .getResponse();
        OpenApiResponseContract.load("/profiles/{alias}")
                .assertJsonResponse(header, 200, "PublicPlayerProfile");
        admin.update(
                "UPDATE ratings.game_listing SET first_release_on = DATE '2021-01-01' WHERE game_id = ?::uuid",
                first);
        admin.update(
                "UPDATE ratings.game_listing SET first_release_on = DATE '2025-01-01' WHERE game_id = ?::uuid",
                second);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                events.publishEvent(
                                        new GameVisitsChanged(
                                                List.of(
                                                        new GameVisitsChanged.Sample(
                                                                UUID.fromString(first), 0.1),
                                                        new GameVisitsChanged.Sample(
                                                                UUID.fromString(second), 0.4)))));
        for (String sort : List.of("score", "releaseDate", "interest")) {
            mvc.perform(
                            get(PUBLIC + "luna/sections/ratings")
                                    .param("sort", sort)
                                    .param("pageSize", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].gameId").value(second));
        }
        mvc.perform(
                        get(PUBLIC + "luna/sections/ratings")
                                .param("sort", "ratingDate")
                                .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].gameId").value(first));
        mvc.perform(get(PUBLIC + "luna/sections/wishlist").param("sort", "interest"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SORT_INVALID"));

        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status ->
                                events.publishEvent(
                                        new GameVisitsChanged(
                                                List.of(
                                                        new GameVisitsChanged.Sample(
                                                                UUID.fromString(second), null)))));
        mvc.perform(
                        get(PUBLIC + "luna/sections/ratings")
                                .param("sort", "interest")
                                .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].gameId").value(first));

        var statistics =
                mvc.perform(get(PUBLIC + "luna/statistics"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.ratings.total").value(2))
                        .andExpect(jsonPath("$.ratings.mean").value(7.5))
                        .andExpect(jsonPath("$.ratings.lowest").value(7))
                        .andExpect(jsonPath("$.ratings.highest").value(8))
                        .andExpect(jsonPath("$.ratings.distribution.length()").value(10))
                        .andExpect(jsonPath("$.ratings.distribution[6]").value(1))
                        .andExpect(jsonPath("$.ratings.distribution[7]").value(1))
                        .andExpect(jsonPath("$.wishlistCount").value(2))
                        .andReturn()
                        .getResponse();
        OpenApiResponseContract.load("/profiles/{alias}/statistics")
                .assertJsonResponse(statistics, 200, "PublicProfileStatistics");
        var ratingPage =
                mvc.perform(
                                get(PUBLIC + "luna/sections/ratings")
                                        .with(login(BOB))
                                        .param("pageSize", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.page.totalItems").value(2))
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andExpect(jsonPath("$.items[0].gameId").value(first))
                        .andExpect(jsonPath("$.items[0].score").value(8))
                        .andReturn()
                        .getResponse();
        OpenApiResponseContract.load("/profiles/{alias}/sections/{section}")
                .assertJsonResponse(ratingPage, 200, "PublicProfileGamePage");
        assertThat(ratingPage.getContentAsString())
                .doesNotContain(
                        "private@example.test",
                        owner(ALICE),
                        owner(BOB),
                        ALICE,
                        "email",
                        "userId",
                        "subject",
                        "versionToken",
                        "revision",
                        "updatedAt",
                        "platforms");
        mvc.perform(get(PUBLIC + "luna/sections/ratings").param("page", "2").param("pageSize", "1"))
                .andExpect(jsonPath("$.items[0].gameId").value(second));
        mvc.perform(get(PUBLIC + "luna/sections/wishlist").param("pageSize", "1"))
                .andExpect(jsonPath("$.items[0].gameId").value(second))
                .andExpect(jsonPath("$.items[0].score").doesNotExist());

        mvc.perform(get("/api/v1/me/data-export").with(login(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicProfile.alias").value("luna"))
                .andExpect(jsonPath("$.publicProfile.shareRatings").value(true));
        mvc.perform(get("/api/v1/me/data-export").with(login(BOB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicProfile").doesNotExist());

        var updateScore =
                "UPDATE ratings.rating SET value=? WHERE user_id=?::uuid AND game_id=?::uuid";
        admin.update(updateScore, 9, owner(ALICE), second);
        mvc.perform(get(PUBLIC + "luna/sections/ratings").param("sort", "scoreDesc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].gameId").value(second))
                .andExpect(jsonPath("$.items[1].gameId").value(first))
                .andExpect(jsonPath("$.items[0].score").value(9));
        admin.update(updateScore, 8, owner(ALICE), second);
        for (int page = 1; page <= games.size(); page++) {
            mvc.perform(
                            get(PUBLIC + "luna/sections/ratings")
                                    .param("sort", "scoreDesc")
                                    .param("page", Integer.toString(page))
                                    .param("pageSize", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].gameId").value(games.get(page - 1)));
        }

        revision = saveSharing(ALICE, "luna", revision, true, false, true);
        mvc.perform(get(PUBLIC + "luna/sections/ratings").header("If-None-Match", "*"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PUBLIC_PROFILE_NOT_FOUND"));
        mvc.perform(get(PUBLIC + "luna/sections/wishlist")).andExpect(status().isOk());
        mvc.perform(get(PUBLIC + "luna/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratings").doesNotExist())
                .andExpect(jsonPath("$.wishlistCount").value(2));
        saveSharing(ALICE, "luna", revision, false, false, true);
        mvc.perform(get(SHARING).with(login(ALICE)))
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.shareRatings").value(false))
                .andExpect(jsonPath("$.shareWishlist").value(false));
        mvc.perform(get(PUBLIC + "luna")).andExpect(status().isNotFound());
        mvc.perform(get(PUBLIC + "luna/sections/wishlist")).andExpect(status().isNotFound());
        mvc.perform(get(PUBLIC + "luna/statistics")).andExpect(status().isNotFound());
    }

    @Test
    void caseInsensitiveClaimRenameResetAndErasurePreserveAliasOwnership() throws Exception {
        for (String alias : List.of("Gameometro", "a--b", "niño", "ab", "23-")) {
            mvc.perform(
                            put(SHARING)
                                    .with(login(ALICE))
                                    .with(csrf().asHeader())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(sharingBody(alias, null, false, false, false)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PUBLIC_ALIAS_INVALID"));
        }
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var claims =
                    executor.invokeAll(
                            List.<Callable<Integer>>of(
                                    () -> claimStatus(ALICE, "Noche"),
                                    () -> claimStatus(BOB, "noche")));
            assertThat(List.of(claims.getFirst().get(), claims.getLast().get()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        String moderatorFixture = "00000000-0000-4000-8000-0000000000c1";
        var revision = saveSharing(moderatorFixture, "estrella", null, true, true, false);
        mvc.perform(
                        put(SHARING)
                                .with(login(moderatorFixture))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sharingBody("nueva", revision, true, true, false)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLIC_ALIAS_CHANGE_TOO_SOON"));
        admin.update(
                "UPDATE identity.player_alias SET chosen_at = now() - interval '31 days' WHERE alias = 'estrella'");
        saveSharing(moderatorFixture, "nueva", revision, true, true, false);
        mvc.perform(get(PUBLIC + "estrella")).andExpect(status().isNotFound());
        assertThat(claimStatus(UUID.randomUUID().toString(), "Estrella")).isEqualTo(409);

        assertThat(
                        store.admit(
                                owner(moderatorFixture),
                                moderatorFixture,
                                UUID.randomUUID().toString(),
                                "a".repeat(64),
                                Instant.now()))
                .isTrue();
        mvc.perform(get(PUBLIC + "nueva")).andExpect(status().isNotFound());
        assertThat(
                        admin.queryForObject(
                                "SELECT identity.retire_player_alias(?::uuid, NULL)",
                                Boolean.class,
                                owner(moderatorFixture)))
                .isTrue();
        assertThat(
                        admin.queryForObject(
                                "SELECT identity.retire_player_alias(?::uuid, NULL)",
                                Boolean.class,
                                owner(moderatorFixture)))
                .isFalse();
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM identity.player_alias WHERE alias IN ('estrella','nueva') "
                                        + "AND user_id IS NULL AND chosen_at IS NULL AND revision IS NULL",
                                Long.class))
                .isEqualTo(2L);
        assertThat(claimStatus(UUID.randomUUID().toString(), "NUEVA")).isEqualTo(409);
    }

    @Test
    void operatorResetWithdrawsTheAliasAndInvalidatesOlderSettings() throws Exception {
        var revision = saveSharing(ALICE, "reportado", null, true, true, true);
        assertThat(
                        admin.queryForObject(
                                "SELECT identity.retire_player_alias(NULL, ?)",
                                Boolean.class,
                                "reportado"))
                .isTrue();
        mvc.perform(get(PUBLIC + "reportado")).andExpect(status().isNotFound());
        mvc.perform(
                        put(SHARING)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sharingBody("reportado", revision, true, true, true)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLIC_PROFILE_REVISION_CONFLICT"));
        assertThat(claimStatus(BOB, "REPORTADO")).isEqualTo(409);
        saveSharing(ALICE, "nuevo-alias", null, false, false, false);
        mvc.perform(get(PUBLIC + "nuevo-alias")).andExpect(status().isNotFound());
    }

    private int claimStatus(String subject, String alias) throws Exception {
        return mvc.perform(
                        put(SHARING)
                                .with(login(subject))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(sharingBody(alias, null, false, false, false)))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private String saveSharing(
            String subject,
            String alias,
            String revision,
            boolean published,
            boolean ratings,
            boolean wishlist)
            throws Exception {
        var response =
                mvc.perform(
                                put(SHARING)
                                        .with(login(subject))
                                        .with(csrf().asHeader())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                sharingBody(
                                                        alias, revision, published, ratings,
                                                        wishlist)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();
        return json.readTree(response.getContentAsString()).path("revision").asText();
    }

    private String sharingBody(
            String alias, String revision, boolean published, boolean ratings, boolean wishlist) {
        var command =
                json.createObjectNode()
                        .put("alias", alias)
                        .put("published", published)
                        .put("shareRatings", ratings)
                        .put("shareWishlist", wishlist);
        if (revision != null) command.put("revision", revision);
        return command.toString();
    }

    @Test
    void onlyValidatedPrincipalFieldsAndNoStore() throws Exception {
        var response =
                mvc.perform(get(PROFILE).with(login(ALICE)))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.email").value("private@example.test"))
                        .andExpect(jsonPath("$.name").value("Cuenta privada"))
                        .andExpect(jsonPath("$.userId").doesNotExist())
                        .andReturn();
        OpenApiResponseContract.load("/me/profile")
                .assertJsonResponse(response.getResponse(), 200, "PrivateProfile");
        mvc.perform(get(PROFILE).with(login(ALICE)).queryParam("userId", BOB))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get(PROFILE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(get("/api/v1/session").with(login(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(get(PROFILE).with(oidcSession(token -> token.issuer(ISSUER).subject(ALICE))))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void projectsAccountEmailFromValidatedUserInfo() throws Exception {
        var session =
                oidcSessionWithUserInfo(
                        token -> token.issuer(ISSUER).subject(ALICE),
                        info ->
                                info.subject(ALICE)
                                        .email("userinfo@example.test")
                                        .emailVerified(false));
        mvc.perform(get(PROFILE).with(session))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.email").value("userinfo@example.test"))
                .andExpect(jsonPath("$.emailVerified").doesNotExist());
        mvc.perform(get("/api/v1/session").with(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void completeExportScopesRatingsPlatformScoresAndSavedPreferences() throws Exception {
        String game =
                admin.queryForObject(
                        "SELECT game_id::text FROM catalogue.game ORDER BY game_id LIMIT 1",
                        String.class);
        String platform =
                admin.queryForObject(
                        "SELECT platform_id::text FROM catalogue.platform ORDER BY platform_id LIMIT 1",
                        String.class);
        rating(ALICE, game, 8);
        rating(BOB, game, 1);
        String directGame =
                admin.queryForObject(
                        "SELECT game_id::text FROM catalogue.game ORDER BY game_id OFFSET 1 LIMIT 1",
                        String.class);
        rating(ALICE, directGame, 8);
        admin.update(
                "UPDATE ratings.rating SET value=NULL, platform_score_count=1, platform_score_sum=7 WHERE user_id=?::uuid AND game_id=?::uuid",
                owner(ALICE),
                game);
        admin.update(
                "INSERT INTO ratings.platform_label(platform_id, display_name) VALUES (?::uuid,'Plataforma propia') ON CONFLICT DO NOTHING",
                platform);
        admin.update(
                "INSERT INTO ratings.rating_platform(user_id,game_id,platform_id,value) VALUES (?::uuid,?::uuid,?::uuid,7)",
                owner(ALICE),
                game,
                platform);
        admin.update(
                "INSERT INTO catalogue.platform_preference_choice VALUES (?::uuid,now())",
                owner(ALICE));
        admin.update(
                "INSERT INTO catalogue.platform_preference VALUES (?::uuid,?::uuid)",
                owner(ALICE),
                platform);
        var result =
                mvc.perform(get("/api/v1/me/data-export").with(login(ALICE)))
                        .andDo(
                                checked -> {
                                    if (checked.getResponse().getStatus() == 503)
                                        throw new AssertionError(
                                                "Synthetic export failed",
                                                checked.getResolvedException());
                                })
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                "attachment; filename=\"gameometro-datos.json\""))
                        .andExpect(jsonPath("$.version").value(1))
                        .andExpect(jsonPath("$.ratings.length()").value(2))
                        .andExpect(jsonPath("$.ratings[0].value").isEmpty())
                        .andExpect(jsonPath("$.ratings[0].platforms[0].value").value(7))
                        .andExpect(jsonPath("$.preferredPlatforms.savedAt").exists())
                        .andExpect(jsonPath("$.preferredPlatforms.platforms.length()").value(1))
                        .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("csrf", "id-token", "password", "versionToken", owner(BOB));
        mvc.perform(get("/api/v1/me/data-export").with(login(UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ratings").isEmpty())
                .andExpect(jsonPath("$.preferredPlatforms.configured").value(false));
    }

    @Test
    void recentAuthCsrfConfirmationAndOriginPrecedeAdmission() throws Exception {
        mvc.perform(
                        post(DELETE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"confirmation\":\"ELIMINAR\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(
                        post(DELETE)
                                .with(login(ALICE))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"confirmation\":\"ELIMINAR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(DELETE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header("Origin", "https://attacker.example")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"confirmation\":\"ELIMINAR\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post(DELETE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"confirmation\":\"NO\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post(DELETE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"confirmation\":\"ELIMINAR\",\"userId\":\""
                                                + BOB
                                                + "\"}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(
                        post(DELETE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .queryParam("userId", BOB)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"confirmation\":\"ELIMINAR\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PARAMETER_UNKNOWN"));
        for (Consumer<OidcIdToken.Builder> claims :
                java.util.List.<Consumer<OidcIdToken.Builder>>of(
                        token -> token.authTime(Instant.now().minusSeconds(301)),
                        token -> token.claims(map -> map.remove("auth_time")),
                        token -> token.authTime(Instant.now().plusSeconds(60)))) {
            mvc.perform(
                            post(DELETE)
                                    .with(
                                            oidcSession(
                                                    token -> {
                                                        token.issuer(ISSUER).subject(ALICE);
                                                        claims.accept(token);
                                                    }))
                                    .with(csrf().asHeader())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"confirmation\":\"ELIMINAR\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("RECENT_AUTHENTICATION_REQUIRED"));
        }
        assertThat(store.blocked(owner(ALICE))).isFalse();
    }

    @Test
    void durablePendingBlocksEverySessionAndReceiptOnlyReportsVerifiedCompletion()
            throws Exception {
        var old = mvc.perform(get(PROFILE).with(login(ALICE))).andReturn();
        var session = (MockHttpSession) old.getRequest().getSession(false);
        var accepted =
                mvc.perform(
                                post(DELETE)
                                        .with(login(ALICE))
                                        .with(csrf().asHeader())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"confirmation\":\"ELIMINAR\"}"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.state").value("pending"))
                        .andReturn();
        var cookie = accepted.getResponse().getCookie("gameometro_deletion");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(store.blocked(owner(ALICE))).isTrue();
        mvc.perform(get(PROFILE).session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get(PROFILE).with(login(ALICE))).andExpect(status().isUnauthorized());
        mvc.perform(get(PROFILE).with(login(BOB))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/session").with(login(ALICE)))
                .andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(get("/api/v1/account-deletion")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/api/v1/account-deletion")
                                .cookie(new Cookie("gameometro_deletion", "x".repeat(43))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/account-deletion").cookie(cookie))
                .andExpect(jsonPath("$.state").value("pending"));
        var work = store.claim(Instant.now()).orElseThrow();
        store.finish(work, false, Instant.now());
        assertThat(store.blocked(owner(ALICE))).isTrue();
        assertThat(store.claim(Instant.now())).isEmpty();
        work = store.claim(Instant.now().plusSeconds(301)).orElseThrow();
        store.acknowledge(work, true, "a".repeat(64), "b".repeat(64), Instant.now());
        mvc.perform(get("/api/v1/account-deletion").cookie(cookie))
                .andExpect(jsonPath("$.state").value("complete"));
        mvc.perform(
                        post(DELETE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"confirmation\":\"ELIMINAR\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM identity.account_deletion", Integer.class))
                .isEqualTo(1);
        assertThat(store.status("x".repeat(64), Instant.now())).isEmpty();
        assertThat(
                        store.status(
                                admin.queryForObject(
                                        "SELECT receipt_hash FROM identity.account_deletion",
                                        String.class),
                                Instant.now().plusSeconds(86401)))
                .isEmpty();
    }

    @Test
    void operationalBatchReprojectsContributionsAndCascadesOnlyTheOwner() throws Exception {
        String game =
                admin.queryForObject(
                        "SELECT game_id::text FROM catalogue.game ORDER BY game_id LIMIT 1",
                        String.class);
        rating(ALICE, game, 1);
        rating(BOB, game, 9);
        admin.update(
                "INSERT INTO catalogue.game_community_score(game_id,mean,exact_mean,rating_count) VALUES (?::uuid,5,5,2) ON CONFLICT (game_id) DO UPDATE SET mean=5, exact_mean=5, rating_count=2",
                game);
        String sql =
                Files.readString(Path.of("../deploy/private-dev/lib/erase-rating-batch.sql"))
                        .replace(":'user_id'", "'" + owner(ALICE) + "'");
        admin.execute(sql);
        assertThat(
                        admin.queryForObject(
                                "SELECT rating_count FROM catalogue.game_community_score WHERE game_id=?::uuid",
                                Integer.class,
                                game))
                .isEqualTo(1);
        assertThat(
                        admin.queryForObject(
                                "SELECT exact_mean FROM catalogue.game_community_score WHERE game_id=?::uuid",
                                java.math.BigDecimal.class,
                                game))
                .isEqualByComparingTo("9");
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM ratings.rating WHERE user_id=?::uuid",
                                Integer.class,
                                owner(ALICE)))
                .isZero();
        admin.execute(sql); // Interrupted/repeated erasure leaves the remaining user's vote intact.
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM ratings.rating WHERE user_id=?::uuid",
                                Integer.class,
                                owner(BOB)))
                .isEqualTo(1);
    }

    @Test
    void concurrentAdmissionAndExpiredClaimsCannotDuplicateOrFalselyCompleteWork()
            throws Exception {
        String receipt = "a".repeat(64);
        String request = UUID.randomUUID().toString();
        var now = Instant.now();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var attempts =
                    java.util.stream.IntStream.range(0, 2)
                            .mapToObj(
                                    index ->
                                            executor.submit(
                                                    () -> {
                                                        start.await();
                                                        return store.admit(
                                                                owner(ALICE),
                                                                ALICE,
                                                                request,
                                                                receipt,
                                                                now);
                                                    }))
                            .toList();
            start.countDown();
            int accepted = 0;
            for (var attempt : attempts) if (attempt.get()) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        var first = store.claim(now.plusSeconds(1)).orElseThrow();
        assertThat(store.claim(now.plusSeconds(2))).isEmpty();
        var successor = store.claim(now.plusSeconds(902)).orElseThrow();
        assertThat(successor.requestId()).isEqualTo(first.requestId());
        store.acknowledge(first, true, "a".repeat(64), "b".repeat(64), now.plusSeconds(903));
        assertThat(store.status(receipt, now.plusSeconds(903))).contains("pending");
        store.acknowledge(successor, true, "a".repeat(64), "b".repeat(64), now.plusSeconds(904));
        assertThat(store.status(receipt, now.plusSeconds(904))).contains("complete");
    }

    @Test
    void anUnavailableAccessFenceNeverRevealsTheProfileOrSession() throws Exception {
        admin.execute("ALTER TABLE identity.account_deletion RENAME TO deletion_unavailable");
        try {
            mvc.perform(get(PROFILE).with(login(ALICE)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("SERVICE_OVERLOADED"));
            mvc.perform(get("/api/v1/session").with(login(ALICE)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.email").doesNotExist());
        } finally {
            admin.execute("ALTER TABLE identity.deletion_unavailable RENAME TO account_deletion");
        }
    }

    @Test
    void inconsistentSourceDataFailsBeforeAnySuccessfulAttachment() throws Exception {
        String game =
                admin.queryForObject(
                        "SELECT game_id::text FROM catalogue.game ORDER BY game_id LIMIT 1",
                        String.class);
        String platform =
                admin.queryForObject(
                        "SELECT platform_id::text FROM catalogue.platform ORDER BY platform_id LIMIT 1",
                        String.class);
        rating(ALICE, game, 8);
        admin.update(
                "INSERT INTO ratings.platform_label(platform_id,display_name) VALUES (?::uuid,'Test platform') ON CONFLICT DO NOTHING",
                platform);
        admin.update(
                "INSERT INTO ratings.rating_platform(user_id,game_id,platform_id,value) VALUES (?::uuid,?::uuid,?::uuid,7)",
                owner(ALICE),
                game,
                platform);
        mvc.perform(get("/api/v1/me/data-export").with(login(ALICE)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().doesNotExist("Content-Disposition"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_OPERATION_UNAVAILABLE"));
    }

    @Test
    void missingExecutorRefusesExplicitlyWithoutBlockingTheAccount() throws Exception {
        when(erasure.available()).thenReturn(false);
        mvc.perform(get(PROFILE).with(login(ALICE)))
                .andExpect(jsonPath("$.deletionAvailable").value(false));
        mvc.perform(
                        post(DELETE)
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"confirmation\":\"ELIMINAR\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_OPERATION_UNAVAILABLE"));
        assertThat(store.blocked(owner(ALICE))).isFalse();
    }

    void rating(String subject, String game, int value) {
        admin.update(
                "INSERT INTO ratings.rating(user_id,game_id,value,created_at,updated_at,version_token) VALUES (?::uuid,?::uuid,?,now(),now(),?::uuid)",
                owner(subject),
                game,
                value,
                UUID.randomUUID().toString());
    }
}
