package com.videogameplatform.api.delivery.catalogue.administration;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.identity.adapter.web.ProductRoleAuthorities;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The operator's synchronization request behind the real BFF security chain and PostgreSQL. The
 * provider is configured but never answers, so an accepted run really executes in the background,
 * through the ordinary synchronization, stays in flight until its provider timeout and finishes
 * failed without any provider data.
 */
@SpringBootTest(
        properties = {
            "management.server.port=0",
            "catalogue.synchronization.provider.client-id=unreachable-client",
            "catalogue.synchronization.provider.client-secret=unreachable-secret",
            "catalogue.synchronization.provider.max-retries=0",
            "catalogue.synchronization.provider.request-timeout=2s"
        })
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class CatalogueSynchronizationRequestApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("catalogue_synchronization_request_api");
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String SYNCHRONIZATION = "/api/v1/admin/catalogue/synchronization";
    private static final String QUARTER = "{\"from\":\"2026-10-01\",\"to\":\"2026-12-31\"}";
    private static final OpenApiResponseContract REQUEST_CONTRACT =
            OpenApiResponseContract.load("/admin/catalogue/synchronization", "post");
    private static final OpenApiResponseContract STATUS_CONTRACT =
            OpenApiResponseContract.load("/admin/catalogue/synchronization");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mockMvc;
    private JdbcTemplate admin;

    /**
     * Accepts connections through its backlog and never answers, so every provider call waits for
     * the request timeout on any host instead of failing at once with a refused connection.
     */
    private static final ServerSocket SILENT_PROVIDER = silentProvider();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, false);
        String provider = "http://127.0.0.1:" + SILENT_PROVIDER.getLocalPort();
        registry.add(
                "catalogue.synchronization.provider.token-uri", () -> provider + "/oauth2/token");
        registry.add("catalogue.synchronization.provider.api-base-uri", () -> provider + "/v4/");
    }

    @AfterAll
    static void closeProvider() throws IOException {
        SILENT_PROVIDER.close();
    }

    private static ServerSocket silentProvider() {
        try {
            return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @BeforeEach
    void noRuns() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.update("DELETE FROM catalogue.synchronization_run");
        admin.update("DELETE FROM catalogue.synchronization_coverage");
    }

    @Test
    void anAnonymousRequestIsUnauthenticatedBeforeCsrfAndStartsNothing() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(command(QUARTER))
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                        .andReturn()
                        .getResponse();

        REQUEST_CONTRACT.assertJsonResponse(response, 401, "Problem");
        assertThat(runs()).isZero();
    }

    @Test
    void aSessionWithoutTheOperatorRoleIsForbiddenEvenWithValidInputAndCsrf() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(
                                command(QUARTER)
                                        .with(signedIn(List.of("catalogue-reader")))
                                        .with(csrf().asHeader()))
                        .andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                        .andReturn()
                        .getResponse();

        REQUEST_CONTRACT.assertJsonResponse(response, 403, "Problem");
        assertThat(runs()).isZero();
    }

    @Test
    void anOperatorWithoutCsrfProofOrFromAnotherOriginIsRejectedAndStartsNothing()
            throws Exception {
        for (MockHttpServletRequestBuilder request :
                List.of(
                        command(QUARTER).with(operator()),
                        command(QUARTER)
                                .with(operator())
                                .with(csrf().asHeader())
                                .header(HttpHeaders.ORIGIN, "https://attacker.example"),
                        command(QUARTER)
                                .with(operator())
                                .with(csrf().asHeader())
                                .header("Sec-Fetch-Site", "cross-site"))) {
            MockHttpServletResponse response =
                    mockMvc.perform(request)
                            .andExpect(status().isForbidden())
                            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                            .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"))
                            .andReturn()
                            .getResponse();
            REQUEST_CONTRACT.assertJsonResponse(response, 403, "Problem");
        }
        assertThat(runs()).isZero();
    }

    @Test
    void invalidIntervalsAndBodiesAreRejectedBeforeAnyRunStarts() throws Exception {
        expectProblem(
                "{\"from\":\"2026-12-31\",\"to\":\"2026-10-01\"}",
                422,
                "SYNCHRONIZATION_RANGE_INVALID",
                "/to");
        expectProblem(
                "{\"from\":\"2026-01-01\",\"to\":\"2026-07-04\"}",
                422,
                "SYNCHRONIZATION_RANGE_INVALID",
                "/to");
        expectProblem(
                "{\"from\":\"1949-12-31\",\"to\":\"1950-01-31\"}",
                422,
                "SYNCHRONIZATION_RANGE_INVALID",
                "/from");
        expectProblem(
                "{\"from\":\"2026-10-01\",\"to\":\"2026-12-31\",\"limit\":5}",
                422,
                "REQUEST_PROPERTY_UNKNOWN",
                "/body");
        expectProblem("{\"from\":\"2026-10-01\"}", 422, "SYNCHRONIZATION_RANGE_INVALID", "/to");
        expectProblem("{\"to\":null}", 422, "SYNCHRONIZATION_RANGE_INVALID", null);
        expectProblem("{\"from\":", 400, "REQUEST_MALFORMED", null);
        expectProblem(
                "{\"from\":\"2026-13-01\",\"to\":\"2026-12-31\"}", 400, "REQUEST_MALFORMED", null);
        MockHttpServletResponse unsupported =
                mockMvc.perform(
                                post(SYNCHRONIZATION)
                                        .with(operator())
                                        .with(csrf().asHeader())
                                        .contentType(MediaType.TEXT_PLAIN)
                                        .accept(MediaType.APPLICATION_JSON)
                                        .content(QUARTER))
                        .andExpect(status().isUnsupportedMediaType())
                        .andReturn()
                        .getResponse();
        REQUEST_CONTRACT.assertJsonResponse(unsupported, 415, "Problem");
        assertThat(runs()).isZero();
    }

    @Test
    void aRunInFlightFromAnyTriggerMakesTheRequestAConflictThatStartsNothing() throws Exception {
        admin.update(
                """
                INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,
                    started_at,heartbeat_at,run_status)
                VALUES (gen_random_uuid(),'IGDB','2026-09-20','2026-11-15',
                    now() - interval '1 minute',now(),'running')
                """);

        MockHttpServletResponse response =
                mockMvc.perform(command(QUARTER).with(operator()).with(csrf().asHeader()))
                        .andExpect(status().isConflict())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.code").value("SYNCHRONIZATION_ALREADY_RUNNING"))
                        .andExpect(jsonPath("$.category").value("conflict"))
                        .andReturn()
                        .getResponse();

        REQUEST_CONTRACT.assertJsonResponse(response, 409, "Problem");
        assertThat(runs()).isOne();
    }

    @Test
    void anAcceptedRunAnswersAtOnceAndIsFollowedThroughTheStatusUntilItsResult() throws Exception {
        MockHttpServletResponse accepted =
                mockMvc.perform(command(QUARTER).with(operator()).with(csrf().asHeader()))
                        .andExpect(status().isAccepted())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.from").value("2026-10-01"))
                        .andExpect(jsonPath("$.to").value("2026-12-31"))
                        .andReturn()
                        .getResponse();
        REQUEST_CONTRACT.assertJsonResponse(accepted, 202, "AcceptedSynchronizationRun");
        assertThat(accepted.getContentAsString())
                .doesNotContain("IGDB")
                .doesNotContain("unreachable")
                .doesNotContain("127.0.0.1");
        String runId = JSON.readTree(accepted.getContentAsString()).path("runId").stringValue();

        // The ordinary run recorded the interval under the provider's single ownership.
        assertThat(
                        admin.queryForMap(
                                "SELECT provider, window_from::text AS window_from,"
                                        + " window_to::text AS window_to"
                                        + " FROM catalogue.synchronization_run"
                                        + " WHERE run_id = CAST(? AS uuid)",
                                runId))
                .isEqualTo(
                        Map.of(
                                "provider", "IGDB",
                                "window_from", "2026-10-01",
                                "window_to", "2026-12-31"));

        // The request answered before the provider call ended: the run is still in flight.
        assertThat(
                        JSON.readTree(readStatus().getContentAsString())
                                .path("currentRun")
                                .path("runId")
                                .stringValue())
                .isEqualTo(runId);

        JsonNode finished = awaitFinished(runId);
        assertThat(finished.path("result").stringValue()).isEqualTo("failed");
        assertThat(finished.path("outcomeCode").stringValue()).isEqualTo("SYNCHRONIZATION_FAILED");
        assertThat(finished.path("from").stringValue()).isEqualTo("2026-10-01");
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM catalogue.synchronization_coverage",
                                Integer.class))
                .isZero();

        // Ownership was released, so the next request is accepted again.
        mockMvc.perform(command(QUARTER).with(operator()).with(csrf().asHeader()))
                .andExpect(status().isAccepted());
        awaitNoCurrentRun();
    }

    private JsonNode awaitFinished(String runId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            MockHttpServletResponse response = readStatus();
            JsonNode body = JSON.readTree(response.getContentAsString());
            for (JsonNode run : body.path("recentRuns")) {
                if (runId.equals(run.path("runId").stringValue())) {
                    STATUS_CONTRACT.assertJsonResponse(
                            response, 200, "CatalogueSynchronizationStatus");
                    return run;
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The accepted run did not finish: " + runId);
    }

    private void awaitNoCurrentRun() throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            if (JSON.readTree(readStatus().getContentAsString())
                    .path("currentRun")
                    .isMissingNode()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The second accepted run did not finish");
    }

    private MockHttpServletResponse readStatus() throws Exception {
        return mockMvc.perform(
                        get(SYNCHRONIZATION).with(operator()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
    }

    private void expectProblem(String body, int status, String code, String pointer)
            throws Exception {
        var result =
                mockMvc.perform(command(body).with(operator()).with(csrf().asHeader()))
                        .andExpect(status().is(status))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.code").value(code));
        if (pointer != null) {
            result.andExpect(jsonPath("$.violations[0].pointer").value(pointer));
        }
        REQUEST_CONTRACT.assertJsonResponse(result.andReturn().getResponse(), status, "Problem");
    }

    private int runs() {
        return admin.queryForObject(
                "SELECT count(*) FROM catalogue.synchronization_run", Integer.class);
    }

    private static MockHttpServletRequestBuilder command(String body) {
        return post(SYNCHRONIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static RequestPostProcessor operator() {
        return signedIn(List.of("catalogue-operator"));
    }

    private static RequestPostProcessor signedIn(List<String> roles) {
        return oidcSession(
                token ->
                        token.issuer(ISSUER)
                                .subject("operator-subject")
                                .audience(List.of("test-bff"))
                                .claim(ProductRoleAuthorities.ROLE_CLAIM, roles),
                new ProductRoleAuthorities());
    }
}
