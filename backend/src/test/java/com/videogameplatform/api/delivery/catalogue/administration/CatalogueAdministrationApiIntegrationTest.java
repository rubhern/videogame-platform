package com.videogameplatform.api.delivery.catalogue.administration;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.identity.adapter.web.ProductRoleAuthorities;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.util.List;
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

/**
 * The catalogue administration reads behind the real BFF security chain and PostgreSQL: anonymous
 * sessions are 401, signed-in sessions without the operator role are 403, and operators read
 * bounded, never-stored evidence that matches the reviewed contract.
 */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class CatalogueAdministrationApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("catalogue_administration_api");
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String STATUS = "/api/v1/admin/catalogue/synchronization";
    private static final String COVERAGE = "/api/v1/admin/catalogue/synchronization-coverage";
    private static final OpenApiResponseContract STATUS_CONTRACT =
            OpenApiResponseContract.load("/admin/catalogue/synchronization");
    private static final OpenApiResponseContract COVERAGE_CONTRACT =
            OpenApiResponseContract.load("/admin/catalogue/synchronization-coverage");
    private static final OpenApiResponseContract SESSION_CONTRACT =
            OpenApiResponseContract.load("/session");

    @Autowired MockMvc mockMvc;
    private UUID coveringRun;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, false);
    }

    @BeforeEach
    void evidence() {
        JdbcTemplate admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.update("DELETE FROM catalogue.synchronization_run");
        admin.update("DELETE FROM catalogue.synchronization_coverage");
        coveringRun = UUID.randomUUID();
        admin.update(
                """
                INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,
                    started_at,heartbeat_at,completed_at,run_status,outcome_code,report)
                VALUES (?,'IGDB','1997-12-01','1997-12-31','2026-10-17T04:00:00Z',
                    '2026-10-17T04:10:00Z','2026-10-17T04:10:00Z','partial',
                    'SYNCHRONIZATION_COMPLETED_WITH_FAILURES',CAST(? AS jsonb))
                """,
                coveringRun,
                report(coveringRun));
        admin.update(
                """
                INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,
                    started_at,heartbeat_at,completed_at,run_status,outcome_code)
                VALUES (gen_random_uuid(),'IGDB','1998-01-01','1998-01-31',
                    '2026-10-16T04:00:00Z','2026-10-16T04:00:00Z','2026-10-17T03:59:00Z',
                    'abandoned','SYNCHRONIZATION_ABANDONED')
                """);
        admin.update(
                """
                INSERT INTO catalogue.synchronization_run(run_id,provider,window_from,window_to,
                    started_at,heartbeat_at,run_status)
                VALUES (gen_random_uuid(),'IGDB','2026-09-20','2026-11-15',
                    now() - interval '5 minutes',now() - interval '2 minutes','running')
                """);
        admin.update(
                """
                INSERT INTO catalogue.synchronization_coverage(provider,covered_from,covered_to,
                    run_id,scan_started_at,scan_completed_at,failed_games)
                VALUES ('IGDB','1997-12-01','1997-12-31',?,'2026-10-17T04:00:00Z',
                    '2026-10-17T04:10:00Z',2)
                """,
                coveringRun);
    }

    @Test
    void anAnonymousRequestIsUnauthenticatedWithoutRedirect() throws Exception {
        for (MockHttpServletRequestBuilder request :
                List.of(get(STATUS), coverage("1997-01-01", "1997-12-31"))) {
            MockHttpServletResponse response =
                    mockMvc.perform(request.accept(MediaType.APPLICATION_JSON))
                            .andExpect(status().isUnauthorized())
                            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                            .andReturn()
                            .getResponse();
            STATUS_CONTRACT.assertJsonResponse(response, 401, "Problem");
        }
    }

    @Test
    void aSignedInSessionWithoutTheOperatorRoleIsForbiddenBeforeAnyValidation() throws Exception {
        for (MockHttpServletRequestBuilder request :
                List.of(
                        get(STATUS),
                        coverage("1997-01-01", "1997-12-31"),
                        coverage("1997-12-31", "1997-01-01"),
                        get(COVERAGE).param("unknown", "1"))) {
            MockHttpServletResponse response =
                    mockMvc.perform(
                                    request.with(signedIn(List.of("catalogue-reader")))
                                            .accept(MediaType.APPLICATION_JSON))
                            .andExpect(status().isForbidden())
                            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                            .andExpect(jsonPath("$.category").value("authorization"))
                            .andReturn()
                            .getResponse();
            COVERAGE_CONTRACT.assertJsonResponse(response, 403, "Problem");
            assertThat(response.getContentAsString()).doesNotContain("1997-12");
        }
    }

    @Test
    void anOperatorReadsTheRunInFlightAndBoundedHistoryThatIsNeverStored() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(get(STATUS).with(operator()).accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.currentRun.from").value("2026-09-20"))
                        .andExpect(jsonPath("$.currentRun.stalled").value(false))
                        .andExpect(jsonPath("$.recentRuns.length()").value(2))
                        .andExpect(jsonPath("$.recentRuns[0].runId").value(coveringRun.toString()))
                        .andExpect(jsonPath("$.recentRuns[0].result").value("partial"))
                        .andExpect(jsonPath("$.recentRuns[0].counters.failedGames").value(2))
                        .andExpect(jsonPath("$.recentRuns[1].result").value("abandoned"))
                        .andExpect(jsonPath("$.recentRuns[1].counters").doesNotExist())
                        .andReturn()
                        .getResponse();

        STATUS_CONTRACT.assertJsonResponse(response, 200, "CatalogueSynchronizationStatus");
        // Run evidence only: no provider name, provider payload or internal report fields.
        assertThat(response.getContentAsString())
                .doesNotContain("IGDB")
                .doesNotContain("inspectedReleaseDates")
                .doesNotContain("providerLatencyMillis");
    }

    @Test
    void anOperatorReadsCoveredAndNeverCoveredPeriodsOfARange() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(
                                coverage("1997-11-01", "1998-01-31")
                                        .with(operator())
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.periods.length()").value(3))
                        .andExpect(jsonPath("$.periods[0].status").value("never_covered"))
                        .andExpect(jsonPath("$.periods[0].lastScan").doesNotExist())
                        .andExpect(jsonPath("$.periods[1].from").value("1997-12-01"))
                        .andExpect(jsonPath("$.periods[1].to").value("1997-12-31"))
                        .andExpect(jsonPath("$.periods[1].status").value("covered"))
                        .andExpect(
                                jsonPath("$.periods[1].lastScan.runId")
                                        .value(coveringRun.toString()))
                        .andExpect(jsonPath("$.periods[1].lastScan.failedGames").value(2))
                        .andExpect(jsonPath("$.periods[2].status").value("never_covered"))
                        .andExpect(jsonPath("$.continuesFrom").doesNotExist())
                        .andReturn()
                        .getResponse();

        COVERAGE_CONTRACT.assertJsonResponse(response, 200, "CatalogueSynchronizationCoverage");
    }

    @Test
    void anOperatorsInvalidRangeIsRejectedWithStableCodes() throws Exception {
        expectProblem(coverage("1997-12-31", "1997-01-01"), 422, "COVERAGE_RANGE_INVALID");
        expectProblem(
                coverage("1997-01-01", "1997-12-31").param("from", "1996-01-01"),
                422,
                "COVERAGE_RANGE_INVALID");
        expectProblem(
                coverage("1997-01-01", "1997-12-31").param("page", "2"),
                422,
                "REQUEST_PARAMETER_UNKNOWN");
        expectProblem(get(STATUS).param("limit", "500"), 422, "REQUEST_PARAMETER_UNKNOWN");
        expectProblem(get(COVERAGE).param("from", "1997-01-01"), 400, "REQUEST_MALFORMED");
        expectProblem(coverage("1997-13-01", "1997-12-31"), 400, "REQUEST_MALFORMED");
    }

    @Test
    void theSessionNamesTheOperatorCapabilityButNeverTheRawRole() throws Exception {
        MockHttpServletResponse operator =
                mockMvc.perform(get("/api/v1/session").with(operator()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.authenticated").value(true))
                        .andExpect(jsonPath("$.capabilities[0]").value("catalogue_administration"))
                        .andReturn()
                        .getResponse();
        SESSION_CONTRACT.assertJsonResponse(operator, 200, "SessionState");
        assertThat(operator.getContentAsString())
                .doesNotContain("catalogue-operator")
                .doesNotContain("ROLE_");

        MockHttpServletResponse player =
                mockMvc.perform(get("/api/v1/session").with(signedIn(List.of())))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.authenticated").value(true))
                        .andExpect(jsonPath("$.capabilities").doesNotExist())
                        .andReturn()
                        .getResponse();
        SESSION_CONTRACT.assertJsonResponse(player, 200, "SessionState");
    }

    private void expectProblem(MockHttpServletRequestBuilder request, int status, String code)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(request.with(operator()).accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().is(status))
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.code").value(code))
                        .andReturn()
                        .getResponse();
        COVERAGE_CONTRACT.assertJsonResponse(response, status, "Problem");
    }

    private static MockHttpServletRequestBuilder coverage(String from, String to) {
        return get(COVERAGE).param("from", from).param("to", to);
    }

    private static RequestPostProcessor operator() {
        return signedIn(List.of("catalogue-operator"));
    }

    /** Authorities come from the BFF's own sign-in mapping of the ID token's role claim. */
    private static RequestPostProcessor signedIn(List<String> roles) {
        return oidcSession(
                token ->
                        token.issuer(ISSUER)
                                .subject("operator-subject")
                                .audience(List.of("test-bff"))
                                .claim(ProductRoleAuthorities.ROLE_CLAIM, roles),
                new ProductRoleAuthorities());
    }

    private static String report(UUID run) {
        return """
                {"runId":"%s","from":"1997-12-01","to":"1997-12-31",
                 "startedAt":"2026-10-17T04:00:00Z","completedAt":"2026-10-17T04:10:00Z",
                 "outcome":"PARTIAL","outcomeCode":"SYNCHRONIZATION_COMPLETED_WITH_FAILURES",
                 "counters":{"inspectedReleaseDates":40,"createdGames":1,"updatedGames":2,
                  "unchangedGames":30,"createdReleases":3,"updatedReleases":4,
                  "unchangedReleases":50,"deferredGames":0,"failedGames":2,
                  "providerRequests":9,"providerRetries":1,"providerLatencyMillis":1200}}
                """
                .formatted(run);
    }
}
