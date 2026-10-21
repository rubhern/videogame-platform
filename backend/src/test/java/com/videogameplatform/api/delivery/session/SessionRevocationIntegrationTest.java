package com.videogameplatform.api.delivery.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.identity.application.CurrentUser;
import com.videogameplatform.identity.domain.UserId;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Administrative revocation of every BFF session of one account through the private management
 * port, with real PostgreSQL rating writes (#274, ADR-0027).
 *
 * <p>Sessions are built as OIDC sign-in leaves them: the validated principal in the HTTP session
 * and the session registered with Spring Security's registry. Two of them share an account, as two
 * devices would.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@AutoConfigureMockMvc
@ActiveProfiles("structured")
@ExtendWith(OutputCaptureExtension.class)
@Import(SessionRevocationIntegrationTest.FixedClock.class)
@Execution(ExecutionMode.SAME_THREAD)
class SessionRevocationIntegrationTest {

    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("session_revocation");
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final Instant NOW = Instant.parse("2026-08-13T10:00:00Z");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort private int port;
    @LocalManagementPort private int managementPort;
    @Autowired private MockMvc mockMvc;
    @Autowired private SessionRegistry sessionRegistry;
    @MockitoSpyBean private PersonalRatingStore ratingStore;
    @MockitoSpyBean private CurrentUser currentUser;

    private final ExecutorService browsers = Executors.newCachedThreadPool();
    // The registry is application state shared by every test in this context.
    private final String alice = "alice-" + UUID.randomUUID();
    private final String bob = "bob-" + UUID.randomUUID();
    private JdbcTemplate admin;
    private UUID game;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, true);
    }

    @BeforeEach
    void createEligibleGame() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        game = UUID.randomUUID();
        admin.update("INSERT INTO catalogue.game(game_id, created_at) VALUES (?, now())", game);
        admin.update(
                """
                INSERT INTO catalogue.game_snapshot(
                    publication_id, game_id, canonical_title, slug, cover_reference,
                    cover_source, cover_usage_mode, cover_alternative_text, cover_usage_status)
                SELECT publication_id, ?, 'Revocation game', ?, '/assets/covers/fallback.svg',
                    'Product', 'product_owned', 'Cover unavailable', 'approved'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                game,
                "revocation-game-" + game);
        UUID release = UUID.randomUUID();
        admin.update(
                "INSERT INTO catalogue.game_release(release_id, game_id, created_at) VALUES (?, ?, now())",
                release,
                game);
        admin.update(
                """
                INSERT INTO catalogue.release_snapshot(
                    publication_id, release_id, game_id, platform_id, region_id,
                    date_precision, exact_date, release_status, source_kind, source_name,
                    source_entity_type, last_synchronized_at, last_verified_at,
                    verification_level, review_status)
                SELECT publication_id, ?, ?, '10000000-0000-4000-8000-000000000001',
                    '20000000-0000-4000-8000-000000000002', 'day', date '2026-08-13',
                    'released', 'official_source', 'Publisher', 'release', now(), now(),
                    'verified', 'not_required'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                release,
                game);
    }

    @AfterEach
    void stopBrowsers() {
        browsers.shutdownNow();
    }

    @Test
    void revokesBothSessionsOfOneAccountAndNoOtherAccountsSession(CapturedOutput output)
            throws Exception {
        MockHttpSession aliceLaptop = signIn(alice);
        MockHttpSession alicePhone = signIn(alice);
        MockHttpSession bobLaptop = signIn(bob);
        rate(aliceLaptop, 9).andExpect(status().isCreated());

        HttpResponse<String> revoked = revoke(userId(alice));

        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(revoked.body()).isEqualTo("{\"revokedSessions\":2}");
        for (MockHttpSession revokedSession : List.of(aliceLaptop, alicePhone)) {
            mockMvc.perform(get("/api/v1/session").session(revokedSession))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{\"authenticated\":false}", JsonCompareMode.STRICT));
        }
        rate(alicePhone, 3)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/me/ratings").session(aliceLaptop))
                .andExpect(status().isUnauthorized());
        assertThat(ratingsOf(alice)).isEqualTo(1);
        assertThat(
                        admin.queryForObject(
                                "SELECT value FROM ratings.rating WHERE user_id = ?::uuid",
                                Integer.class,
                                userId(alice)))
                .isEqualTo(9);

        mockMvc.perform(get("/api/v1/session").session(bobLaptop))
                .andExpect(jsonPath("$.authenticated").value(true));
        rate(bobLaptop, 7).andExpect(status().isCreated());

        HttpResponse<String> repeated = revoke(userId(alice));
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(repeated.body()).isEqualTo("{\"revokedSessions\":0}");
        assertThat(output.getAll())
                .contains("BFF sessions revoked")
                .doesNotContain(userId(alice), alice, "id-token-");
    }

    @Test
    void aWriteRunningDuringRevocationEndsBeforeConfirmationSoTheLaterDeletionIsFinal()
            throws Exception {
        MockHttpSession aliceLaptop = signIn(alice);
        MockHttpSession alicePhone = signIn(alice);
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            writing.countDown();
                            await(commit);
                            return invocation.callRealMethod();
                        })
                .when(ratingStore)
                .create(eq(userId(alice)), any(), any(), any(), any(), any());

        CompletableFuture<Integer> inFlight =
                CompletableFuture.supplyAsync(() -> rateInBrowser(aliceLaptop, 8), browsers);
        assertThat(writing.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<HttpResponse<String>> revocation = revokeAsync(userId(alice));
        Thread.sleep(300);
        assertThat(revocation).isNotDone();

        commit.countDown();

        assertThat(inFlight.get(10, TimeUnit.SECONDS)).isEqualTo(201);
        HttpResponse<String> confirmed = revocation.get(10, TimeUnit.SECONDS);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(confirmed.body()).isEqualTo("{\"revokedSessions\":2}");
        // The owner deletes only after the confirmation; nothing can write for Alice any more.
        assertThat(deleteRatingsOf(alice)).isEqualTo(1);
        rate(alicePhone, 5).andExpect(status().isUnauthorized());
        assertThat(ratingsOf(alice)).isZero();
    }

    @Test
    void aRequestAdmittedBeforeRevocationThatWritesAfterItIsRefused() throws Exception {
        MockHttpSession aliceLaptop = signIn(alice);
        signIn(alice);
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            admitted.countDown();
                            await(resume);
                            return invocation.callRealMethod();
                        })
                .when(currentUser)
                .changeAsCurrentUser(any());

        CompletableFuture<Integer> stalled =
                CompletableFuture.supplyAsync(() -> rateInBrowser(aliceLaptop, 8), browsers);
        assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();

        HttpResponse<String> confirmed = revoke(userId(alice));
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(confirmed.body()).isEqualTo("{\"revokedSessions\":2}");
        assertThat(deleteRatingsOf(alice)).isZero();
        resume.countDown();

        assertThat(stalled.get(10, TimeUnit.SECONDS)).isEqualTo(401);
        assertThat(ratingsOf(alice)).isZero();
    }

    @Test
    void theOperationIsReachableOnlyOnTheManagementPortAndOnlyWithADerivedUserId()
            throws Exception {
        MockHttpSession aliceLaptop = signIn(alice);
        String body = "{\"userId\":\"" + userId(alice) + "\"}";

        HttpResponse<String> discovery =
                HTTP.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://localhost:" + managementPort + "/actuator"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(discovery.body()).contains("\"sessionrevocation\"");
        assertThat(send(port, "GET", null, null).statusCode()).isEqualTo(404);
        assertThat(send(port, "POST", body, null).statusCode()).isNotEqualTo(200);
        assertThat(send(port, "DELETE", body, null).statusCode()).isNotEqualTo(200);
        assertThat(send(managementPort, "POST", body, "https://attacker.example").statusCode())
                .isEqualTo(403);
        for (String invalid :
                List.of(
                        "{}",
                        "{\"userId\":\"not-a-user\"}",
                        "{\"userId\":\"" + userId(alice).toUpperCase() + "\"}",
                        "{\"userId\":\"" + UUID.randomUUID() + "\"}")) {
            HttpResponse<String> rejected = send(managementPort, "POST", invalid, null);
            assertThat(rejected.statusCode()).isEqualTo(400);
            assertThat(rejected.body()).isEqualTo("{\"code\":\"INVALID_USER_ID\"}");
        }
        mockMvc.perform(get("/api/v1/session").session(aliceLaptop))
                .andExpect(jsonPath("$.authenticated").value(true));
    }

    private MockHttpSession signIn(String subject) {
        Instant authenticatedAt = NOW.minusSeconds(60);
        OidcIdToken token =
                OidcIdToken.withTokenValue("id-token-" + subject)
                        .issuer(ISSUER)
                        .subject(subject)
                        .audience(List.of("test-bff"))
                        .issuedAt(authenticatedAt)
                        .expiresAt(authenticatedAt.plusSeconds(300))
                        .authTime(authenticatedAt)
                        .build();
        DefaultOidcUser user =
                new DefaultOidcUser(AuthorityUtils.createAuthorityList("OIDC_USER"), token);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(
                        new OAuth2AuthenticationToken(user, user.getAuthorities(), "keycloak")));
        sessionRegistry.registerNewSession(session.getId(), user);
        return session;
    }

    private ResultActions rate(MockHttpSession session, int value) throws Exception {
        return mockMvc.perform(
                put("/api/v1/me/ratings/" + game)
                        .session(session)
                        .with(csrf().asHeader())
                        .header(HttpHeaders.IF_NONE_MATCH, "*")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"value\":" + value + "}"));
    }

    private int rateInBrowser(MockHttpSession session, int value) {
        try {
            return rate(session, value).andReturn().getResponse().getStatus();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private int ratingsOf(String subject) {
        return admin.queryForObject(
                "SELECT count(*) FROM ratings.rating WHERE user_id = ?::uuid",
                Integer.class,
                userId(subject));
    }

    private int deleteRatingsOf(String subject) {
        return admin.update("DELETE FROM ratings.rating WHERE user_id = ?::uuid", userId(subject));
    }

    private static String userId(String subject) {
        return UserId.fromIssuerAndSubject(ISSUER, subject).value();
    }

    private HttpResponse<String> revoke(String userId) throws Exception {
        return revokeAsync(userId).get(30, TimeUnit.SECONDS);
    }

    private CompletableFuture<HttpResponse<String>> revokeAsync(String userId) {
        return HTTP.sendAsync(
                request(managementPort, "POST", "{\"userId\":\"" + userId + "\"}", null),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> send(int port, String method, String body, String origin)
            throws Exception {
        return HTTP.send(request(port, method, body, origin), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest request(int port, String method, String body, String origin) {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port + "/actuator/sessionrevocation"))
                        .header("Content-Type", "application/json")
                        .method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return builder.build();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Test latch was never released");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
