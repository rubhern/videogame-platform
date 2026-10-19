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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The operator's stop and abandonment commands behind the real BFF security chain and
 * PostgreSQL. Runs are recorded directly as the run ledger holds them, so no worker or provider
 * is involved: the worker side of both commands has its own PostgreSQL evidence.
 */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class CatalogueSynchronizationControlApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("catalogue_synchronization_control_api");
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String STATUS = "/api/v1/admin/catalogue/synchronization";
    private static final String RUNS = "/api/v1/admin/catalogue/synchronization-runs/";
    private static final OpenApiResponseContract STOP_CONTRACT =
            OpenApiResponseContract.load(
                    "/admin/catalogue/synchronization-runs/{runId}/stop", "post");
    private static final OpenApiResponseContract ABANDON_CONTRACT =
            OpenApiResponseContract.load(
                    "/admin/catalogue/synchronization-runs/{runId}/abandon", "post");
    private static final OpenApiResponseContract STATUS_CONTRACT =
            OpenApiResponseContract.load("/admin/catalogue/synchronization");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mockMvc;
    private JdbcTemplate admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, false);
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
    }

    @Test
    void anonymousForbiddenAndUnprovenCommandsAreRejectedBeforeTheyTouchTheRun() throws Exception {
        UUID run = running("now() - interval '2 hours'");
        for (String action : List.of("stop", "abandon")) {
            OpenApiResponseContract contract = contract(action);
            expectRejected(command(run, action), 401, "AUTHENTICATION_REQUIRED", contract);
            expectRejected(
                    command(run, action)
                            .with(signedIn(List.of("catalogue-reader")))
                            .with(csrf().asHeader()),
                    403,
                    "ACCESS_DENIED",
                    contract);
            expectRejected(
                    command(run, action).with(operator()), 403, "CSRF_VALIDATION_FAILED", contract);
            expectRejected(
                    command(run, action)
                            .with(operator())
                            .with(csrf().asHeader())
                            .header(HttpHeaders.ORIGIN, "https://attacker.example"),
                    403,
                    "CSRF_VALIDATION_FAILED",
                    contract);
            expectRejected(
                    command(run, action)
                            .with(operator())
                            .with(csrf().asHeader())
                            .header("Sec-Fetch-Site", "cross-site"),
                    403,
                    "CSRF_VALIDATION_FAILED",
                    contract);
        }
        assertThat(
                        admin.queryForMap(
                                "SELECT run_status, stop_requested_at IS NULL AS untouched"
                                        + " FROM catalogue.synchronization_run WHERE run_id=?",
                                run))
                .isEqualTo(Map.of("run_status", "running", "untouched", true));
    }

    @Test
    void aStopIsAcceptedForTheNamedRunRepeatedIdempotentlyAndShownInTheStatus() throws Exception {
        UUID run = running("now()");

        MockHttpServletResponse first =
                mockMvc.perform(authorized(run, "stop"))
                        .andExpect(status().isAccepted())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.runId").value(run.toString()))
                        .andReturn()
                        .getResponse();
        STOP_CONTRACT.assertJsonResponse(first, 202, "SynchronizationStopRequest");
        String requestedAt =
                JSON.readTree(first.getContentAsString()).path("stopRequestedAt").stringValue();
        mockMvc.perform(authorized(run, "stop"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stopRequestedAt").value(requestedAt));

        MockHttpServletResponse read = readStatus();
        STATUS_CONTRACT.assertJsonResponse(read, 200, "CatalogueSynchronizationStatus");
        JsonNode current = JSON.readTree(read.getContentAsString()).path("currentRun");
        assertThat(current.path("runId").stringValue()).isEqualTo(run.toString());
        assertThat(current.path("stopRequestedAt").isMissingNode()).isFalse();
        // A stop request is a request: the run still holds ownership until its worker acts.
        assertThat(runStatus(run)).isEqualTo("running");
    }

    @Test
    void finishedAndUnknownRunsAreIntelligibleConflictsAndChangeNothing() throws Exception {
        UUID finished = finished("succeeded", "SYNCHRONIZATION_COMPLETED");
        for (String action : List.of("stop", "abandon")) {
            expectProblem(finished.toString(), action, 409, "SYNCHRONIZATION_RUN_FINISHED");
            expectProblem(
                    UUID.randomUUID().toString(), action, 404, "SYNCHRONIZATION_RUN_NOT_FOUND");
            expectProblem("not-a-run", action, 404, "SYNCHRONIZATION_RUN_NOT_FOUND");
        }
        assertThat(runStatus(finished)).isEqualTo("succeeded");
    }

    @Test
    void aRunThatStillGivesHeartbeatsIsNeverAbandoned() throws Exception {
        UUID run = running("now() - interval '1 minute'");

        expectProblem(run.toString(), "abandon", 409, "SYNCHRONIZATION_RUN_NOT_STALE");

        assertThat(runStatus(run)).isEqualTo("running");
    }

    @Test
    void aStaleRunIsAbandonedOnceAndAppearsAsAbandonedByAnOperator() throws Exception {
        UUID run = running("now() - interval '2 hours'");

        MockHttpServletResponse abandoned =
                mockMvc.perform(authorized(run, "abandon"))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.runId").value(run.toString()))
                        .andExpect(jsonPath("$.result").value("abandoned"))
                        .andExpect(
                                jsonPath("$.outcomeCode")
                                        .value("SYNCHRONIZATION_ABANDONED_BY_OPERATOR"))
                        .andExpect(jsonPath("$.counters").doesNotExist())
                        .andReturn()
                        .getResponse();
        ABANDON_CONTRACT.assertJsonResponse(abandoned, 200, "FinishedSynchronizationRun");
        expectProblem(run.toString(), "abandon", 409, "SYNCHRONIZATION_RUN_FINISHED");

        MockHttpServletResponse read = readStatus();
        STATUS_CONTRACT.assertJsonResponse(read, 200, "CatalogueSynchronizationStatus");
        JsonNode body = JSON.readTree(read.getContentAsString());
        assertThat(body.path("currentRun").isMissingNode()).isTrue();
        assertThat(body.path("recentRuns").get(0).path("outcomeCode").stringValue())
                .isEqualTo("SYNCHRONIZATION_ABANDONED_BY_OPERATOR");
    }

    private UUID running(String heartbeat) {
        UUID run = UUID.randomUUID();
        admin.update(
                "INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,"
                        + "started_at,heartbeat_at,run_status) VALUES (?,'IGDB','2026-09-20',"
                        + "'2026-11-15',now() - interval '3 hours',"
                        + heartbeat
                        + ",'running')",
                run);
        return run;
    }

    private UUID finished(String result, String code) {
        UUID run = UUID.randomUUID();
        admin.update(
                "INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,"
                        + "started_at,heartbeat_at,completed_at,run_status,outcome_code) VALUES"
                        + " (?,'IGDB','2026-09-20','2026-11-15',now() - interval '1 hour',now(),"
                        + "now(),?,?)",
                run,
                result,
                code);
        return run;
    }

    private String runStatus(UUID run) {
        return admin.queryForObject(
                "SELECT run_status FROM catalogue.synchronization_run WHERE run_id=?",
                String.class,
                run);
    }

    private void expectProblem(String run, String action, int status, String code)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(
                                post(RUNS + run + "/" + action)
                                        .accept(MediaType.APPLICATION_JSON)
                                        .with(operator())
                                        .with(csrf().asHeader()))
                        .andExpect(status().is(status))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.code").value(code))
                        .andExpect(jsonPath("$.violations[0].pointer").value("/path/runId"))
                        .andReturn()
                        .getResponse();
        contract(action).assertJsonResponse(response, status, "Problem");
    }

    private void expectRejected(
            MockHttpServletRequestBuilder request,
            int status,
            String code,
            OpenApiResponseContract contract)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(request)
                        .andExpect(status().is(status))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.code").value(code))
                        .andReturn()
                        .getResponse();
        contract.assertJsonResponse(response, status, "Problem");
    }

    private MockHttpServletResponse readStatus() throws Exception {
        return mockMvc.perform(get(STATUS).with(operator()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
    }

    private MockHttpServletRequestBuilder authorized(UUID run, String action) {
        return command(run, action).with(operator()).with(csrf().asHeader());
    }

    private static MockHttpServletRequestBuilder command(UUID run, String action) {
        return post(RUNS + run + "/" + action).accept(MediaType.APPLICATION_JSON);
    }

    private static OpenApiResponseContract contract(String action) {
        return "stop".equals(action) ? STOP_CONTRACT : ABANDON_CONTRACT;
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
