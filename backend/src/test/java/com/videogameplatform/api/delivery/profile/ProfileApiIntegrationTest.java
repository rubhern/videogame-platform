package com.videogameplatform.api.delivery.profile;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.identity.application.AccountDeletionStore;
import com.videogameplatform.identity.application.DeletionExecutorAvailability;
import com.videogameplatform.identity.domain.UserId;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
    static final String DELETE = "/api/v1/me/account-deletion";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountDeletionStore store;
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
                                .claim("name", "Cuenta privada"));
    }

    static String owner(String subject) {
        return UserId.fromIssuerAndSubject(ISSUER, subject).value();
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
        mvc.perform(get(PROFILE)).andExpect(status().isUnauthorized());
        mvc.perform(get(PROFILE).with(oidcSession(token -> token.issuer(ISSUER).subject(ALICE))))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist());
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
