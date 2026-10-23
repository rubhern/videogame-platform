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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
class HistoricalSynchronizationApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("historical_api");
    private static final String PATH = "/api/v1/admin/catalogue/historical-synchronization";
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, false);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(
                new DriverManagerDataSource(
                        PostgreSqlTestDatabase.runtimeUrl(DATABASE),
                        PostgreSqlTestDatabase.runtimeUsername(),
                        PostgreSqlTestDatabase.runtimePassword()));
    }

    @BeforeEach
    void clearHistory() {
        jdbc().update("DELETE FROM catalogue.historical_backfill");
    }

    @Test
    void authorizedStartAndNamedResumeReturnDurableContractSnapshots() throws Exception {
        jdbc().update(
                        "INSERT INTO catalogue.synchronization_coverage VALUES('IGDB',DATE '2024-01-01',DATE '2024-02-29',gen_random_uuid(),now(),now(),0)");
        var response =
                mvc.perform(
                                post(PATH)
                                        .with(session(true))
                                        .with(csrf().asHeader())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"from\":\"2024-01-01\",\"to\":\"2024-02-29\",\"coveredMonths\":\"skip\"}"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.backfillId").isString())
                        .andExpect(jsonPath("$.totalMonths").value(2))
                        .andReturn()
                        .getResponse();
        OpenApiResponseContract.load("/admin/catalogue/historical-synchronization", "post")
                .assertJsonResponse(response, 202, "HistoricalSynchronizationStatus");
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!"completed"
                        .equals(
                                jdbc().queryForObject(
                                                "SELECT state FROM catalogue.historical_backfill",
                                                String.class))
                && System.nanoTime() < deadline) Thread.sleep(10);
        mvc.perform(get(PATH).with(session(true)))
                .andExpect(jsonPath("$.state").value("completed"))
                .andExpect(jsonPath("$.completedMonths").value(2))
                .andExpect(jsonPath("$.skippedMonths").value(2));
        String id =
                jdbc().queryForObject(
                                "SELECT backfill_id::text FROM catalogue.historical_backfill",
                                String.class);
        // A prepared stopped target models an explicit operator stop before its first chunk.
        jdbc().update(
                        "UPDATE catalogue.historical_backfill SET state='stopped',next_from=window_from,completed_months=0,skipped_months=0");
        mvc.perform(post(PATH + "/" + id + "/stop").with(session(true)).with(csrf().asHeader()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("stopped"));
        mvc.perform(post(PATH + "/" + id + "/resume").with(session(true)).with(csrf().asHeader()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.backfillId").value(id));
        deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (!"completed"
                        .equals(
                                jdbc().queryForObject(
                                                "SELECT state FROM catalogue.historical_backfill",
                                                String.class))
                && System.nanoTime() < deadline) Thread.sleep(10);
        mvc.perform(get(PATH).with(session(true)))
                .andExpect(jsonPath("$.completedMonths").value(2));
        jdbc().update("DELETE FROM catalogue.synchronization_coverage");
    }

    @Test
    void everyHistoricalCommandRequiresAnOperatorSessionCsrfAndSameOriginBeforeValidation()
            throws Exception {
        for (String suffix : List.of("", "/missing/stop", "/missing/resume")) {
            mvc.perform(command(suffix)).andExpect(status().isUnauthorized());
            mvc.perform(command(suffix).with(session(false)).with(csrf().asHeader()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mvc.perform(command(suffix).with(session(true)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
            mvc.perform(
                            command(suffix)
                                    .with(session(true))
                                    .with(csrf().asHeader())
                                    .header(HttpHeaders.ORIGIN, "https://attacker.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
        }
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.runtimeUrl(DATABASE),
                                PostgreSqlTestDatabase.runtimeUsername(),
                                PostgreSqlTestDatabase.runtimePassword()));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM catalogue.historical_backfill", Long.class))
                .isZero();
    }

    @Test
    void statusAndRejectionsFollowTheGeneratedContractWithoutProviderWork() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(session(false))).andExpect(status().isForbidden());
        var response =
                mvc.perform(get(PATH).with(session(true)))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.state").value("not_started"))
                        .andReturn()
                        .getResponse();
        OpenApiResponseContract.load("/admin/catalogue/historical-synchronization")
                .assertJsonResponse(response, 200, "HistoricalSynchronizationStatus");
        mvc.perform(get(PATH + "?unsupported=true").with(session(true)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PARAMETER_UNKNOWN"));
        for (String action : List.of("stop", "resume")) {
            var rejected =
                    mvc.perform(
                                    command("/missing/" + action)
                                            .with(session(true))
                                            .with(csrf().asHeader()))
                            .andExpect(status().isNotFound())
                            .andExpect(
                                    jsonPath("$.code")
                                            .value("HISTORICAL_SYNCHRONIZATION_NOT_FOUND"))
                            .andReturn()
                            .getResponse();
            OpenApiResponseContract.load(
                            "/admin/catalogue/historical-synchronization/{backfillId}/" + action,
                            "post")
                    .assertJsonResponse(rejected, 404, "Problem");
        }
        mvc.perform(
                        post(PATH)
                                .with(session(true))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"from\":\"1949-01-01\",\"to\":\"2026-09-30\",\"coveredMonths\":\"skip\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("HISTORICAL_SYNCHRONIZATION_RANGE_INVALID"));
    }

    private static MockHttpServletRequestBuilder command(String suffix) {
        var request = post(PATH + suffix).accept(MediaType.APPLICATION_JSON);
        if (suffix.isEmpty())
            request.contentType(MediaType.APPLICATION_JSON)
                    .content(
                            "{\"from\":\"2024-01-01\",\"to\":\"2024-01-31\",\"coveredMonths\":\"skip\"}");
        return request;
    }

    private static RequestPostProcessor session(boolean operator) {
        return oidcSession(
                token ->
                        token.issuer("https://identity.example/realms/test")
                                .subject("operator-subject")
                                .audience(List.of("test-bff"))
                                .claim(
                                        ProductRoleAuthorities.ROLE_CLAIM,
                                        operator ? List.of("catalogue-operator") : List.of()),
                new ProductRoleAuthorities());
    }
}
