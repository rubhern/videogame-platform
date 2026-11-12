package com.videogameplatform.api.delivery.catalogue.administration;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings;
import com.videogameplatform.identity.adapter.web.ProductRoleAuthorities;
import com.videogameplatform.identity.domain.UserId;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.ObjectMapper;

/**
 * UC-024 behind the real BFF security chain and PostgreSQL: who may read and change the product
 * settings, validation, the revision guard, the audit trail and a public read that follows a change.
 */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class ProductSettingsApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("product_settings_api");
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String SUBJECT = "operator-subject";
    private static final String ROOT = "/api/v1/admin/catalogue/product-settings";
    private static final String PUBLICATION = "00000000-0000-4000-8000-000000000450";
    private static final OpenApiResponseContract READ =
            OpenApiResponseContract.load("/admin/catalogue/product-settings");
    private static final OpenApiResponseContract UPDATE =
            OpenApiResponseContract.load("/admin/catalogue/product-settings", "put");
    private static final OpenApiResponseContract CALENDAR =
            OpenApiResponseContract.load("/release-calendar");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mockMvc;

    @Autowired
    com.videogameplatform.catalogue.application.administration.ManageProductSettings settings;

    private JdbcTemplate admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, false);
    }

    @BeforeEach
    void reset() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.update("DELETE FROM catalogue.product_setting");
        admin.update("DELETE FROM catalogue.product_setting_change");
        admin.update("UPDATE catalogue.product_setting_revision SET revision = 0");
        if (admin.queryForObject(
                        "SELECT count(*) FROM catalogue.catalogue_publication", Integer.class)
                == 0) {
            admin.update(
                    "INSERT INTO catalogue.catalogue_publication (publication_id,"
                            + " catalogue_version, published_at, last_synchronized_at,"
                            + " source_kind, source_name, is_current) VALUES (CAST(? AS uuid),"
                            + " 'settings-v1', now(), now(), 'external_provider', 'IGDB', true)",
                    PUBLICATION);
        }
    }

    @Test
    void onlyTheOperatorReadsAndOnlyAProvenOperatorCommandChanges() throws Exception {
        mockMvc.perform(get(ROOT).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        get(ROOT)
                                .accept(MediaType.APPLICATION_JSON)
                                .with(signedIn(List.of("catalogue-reader"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(update(0, horizon(8)).with(signedIn(List.of())).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        mockMvc.perform(update(0, horizon(8)).with(operator()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
        assertThat(revision()).isZero();

        MockHttpServletResponse read =
                mockMvc.perform(get(ROOT).accept(MediaType.APPLICATION_JSON).with(operator()))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.revision").value(0))
                        .andExpect(jsonPath("$.settings.length()").value(23))
                        .andExpect(
                                jsonPath("$.settings[4].key")
                                        .value("upcoming-highlights.horizon-months"))
                        .andExpect(jsonPath("$.settings[4].value").value(6))
                        .andExpect(jsonPath("$.settings[4].origin").value("default"))
                        .andExpect(jsonPath("$.settings[22].origin").value("deployment"))
                        .andExpect(jsonPath("$.recentChanges").isEmpty())
                        .andReturn()
                        .getResponse();
        READ.assertJsonResponse(read, 200, "ProductSettingsOverview");
    }

    @Test
    void changesTheHorizonFromSixToEightAndAuditsTheSessionsDerivedIdentity() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(update(0, horizon(8)).with(operator()).with(csrf().asHeader()))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.revision").value(1))
                        .andExpect(jsonPath("$.settings[4].value").value(8))
                        .andExpect(jsonPath("$.settings[4].defaultValue").value(6))
                        .andExpect(jsonPath("$.settings[4].origin").value("operator"))
                        .andExpect(jsonPath("$.settings[4].changedAt").exists())
                        .andExpect(
                                jsonPath("$.recentChanges[0].key")
                                        .value("upcoming-highlights.horizon-months"))
                        .andExpect(jsonPath("$.recentChanges[0].previousValue").value(6))
                        .andExpect(jsonPath("$.recentChanges[0].newValue").value(8))
                        .andReturn()
                        .getResponse();
        UPDATE.assertJsonResponse(response, 200, "ProductSettingsOverview");

        Map<String, Object> audit =
                admin.queryForMap(
                        "SELECT changed_by::text AS changed_by, previous_value, new_value,"
                                + " restored_default FROM catalogue.product_setting_change");
        assertThat(audit)
                .containsEntry("changed_by", UserId.fromIssuerAndSubject(ISSUER, SUBJECT).value())
                .containsEntry("restored_default", false);

        // Restoring the default deletes the override and is audited as such.
        mockMvc.perform(
                        update(1, List.of(Map.of("key", "upcoming-highlights.horizon-months")))
                                .with(operator())
                                .with(csrf().asHeader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.settings[4].value").value(6))
                .andExpect(jsonPath("$.settings[4].origin").value("default"));
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM catalogue.product_setting", Integer.class))
                .isZero();
        assertThat(
                        admin.queryForObject(
                                "SELECT restored_default FROM catalogue.product_setting_change"
                                        + " WHERE revision = 2",
                                Boolean.class))
                .isTrue();
    }

    @Test
    void rejectsStaleRevisionsUnknownKeysOutOfRangeValuesAndBrokenRulesWithoutChanges()
            throws Exception {
        mockMvc.perform(update(0, horizon(8)).with(operator()).with(csrf().asHeader()))
                .andExpect(status().isOk());

        MockHttpServletResponse stale =
                mockMvc.perform(update(0, horizon(7)).with(operator()).with(csrf().asHeader()))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PRODUCT_SETTINGS_REVISION_CONFLICT"))
                        .andReturn()
                        .getResponse();
        UPDATE.assertJsonResponse(stale, 409, "Problem");

        assertRejected(
                update(1, List.of(Map.of("key", "identity.oidc.issuer-uri", "value", 1))),
                "/changes/0/key");
        assertRejected(update(1, horizon(25)), "/changes/0/value");
        assertRejected(update(1, horizon(7.5)), "/changes/0/value");
        assertRejected(
                update(1, List.of(Map.of("key", "calendar.moderate-interest-hypes", "value", 200))),
                "/changes");
        assertRejected(update(1, List.of()), "/changes");
        mockMvc.perform(
                        put(ROOT)
                                .accept(MediaType.APPLICATION_JSON)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"revision\":1,\"changes\":[],\"extra\":true}")
                                .with(operator())
                                .with(csrf().asHeader()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PROPERTY_UNKNOWN"));

        assertThat(revision()).isEqualTo(1);
        assertThat(
                        admin.queryForObject(
                                "SELECT setting_value FROM catalogue.product_setting",
                                java.math.BigDecimal.class))
                .isEqualByComparingTo("8");
    }

    @Test
    void concurrentUpdatesFromOneRevisionLetExactlyOneWin() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<ManageProductSettings.UpdateOutcome>> outcomes =
                    new java.util.ArrayList<>();
            for (int months = 7; months <= 10; months++) {
                int value = months;
                outcomes.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return settings.update(
                                            0,
                                            List.of(
                                                    new ManageProductSettings.Change(
                                                            "upcoming-highlights.horizon-months",
                                                            java.util.Optional.of(
                                                                    java.math.BigDecimal.valueOf(
                                                                            value)))),
                                            UserId.fromIssuerAndSubject(ISSUER, SUBJECT).value());
                                }));
            }
            start.countDown();
            long updated = 0;
            for (var outcome : outcomes) {
                if (outcome.get() instanceof ManageProductSettings.UpdateOutcome.Updated) {
                    updated++;
                } else {
                    assertThat(outcome.get())
                            .isInstanceOf(ManageProductSettings.UpdateOutcome.Stale.class);
                }
            }
            assertThat(updated).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(revision()).isEqualTo(1);
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM catalogue.product_setting_change",
                                Integer.class))
                .isEqualTo(1);
    }

    @Test
    void thePublicCalendarPresentsTheOperatorsThresholdsFromTheNextRequest() throws Exception {
        MockHttpServletResponse before =
                mockMvc.perform(get("/api/v1/release-calendar").accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.presentation.interestThresholds[3]").value(150))
                        .andExpect(jsonPath("$.presentation.dayPreviewSize").value(3))
                        .andReturn()
                        .getResponse();
        CALENDAR.assertJsonResponse(before, 200, "ReleaseCalendar");
        String tag = before.getHeader(HttpHeaders.ETAG);

        mockMvc.perform(
                        update(
                                        0,
                                        List.of(
                                                Map.of(
                                                        "key",
                                                        "calendar.very-high-interest-hypes",
                                                        "value",
                                                        400),
                                                Map.of(
                                                        "key",
                                                        "calendar.day-preview-size",
                                                        "value",
                                                        5)))
                                .with(operator())
                                .with(csrf().asHeader()))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/release-calendar")
                                .accept(MediaType.APPLICATION_JSON)
                                .header(HttpHeaders.IF_NONE_MATCH, tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presentation.interestThresholds[3]").value(400))
                .andExpect(jsonPath("$.presentation.dayPreviewSize").value(5));
    }

    private void assertRejected(MockHttpServletRequestBuilder request, String pointer)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(request.with(operator()).with(csrf().asHeader()))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.code").value("PRODUCT_SETTING_INVALID"))
                        .andExpect(jsonPath("$.violations[0].pointer").value(pointer))
                        .andReturn()
                        .getResponse();
        UPDATE.assertJsonResponse(response, 422, "Problem");
    }

    private long revision() {
        return admin.queryForObject(
                "SELECT revision FROM catalogue.product_setting_revision", Long.class);
    }

    private static List<Map<String, Object>> horizon(Number months) {
        return List.of(Map.of("key", "upcoming-highlights.horizon-months", "value", months));
    }

    private static MockHttpServletRequestBuilder update(
            long revision, List<Map<String, Object>> changes) throws Exception {
        return put(ROOT)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("revision", revision, "changes", changes)));
    }

    private static RequestPostProcessor operator() {
        return signedIn(List.of("catalogue-operator"));
    }

    private static RequestPostProcessor signedIn(List<String> roles) {
        return oidcSession(
                token ->
                        token.issuer(ISSUER)
                                .subject(SUBJECT)
                                .audience(List.of("test-bff"))
                                .claim(ProductRoleAuthorities.ROLE_CLAIM, roles),
                new ProductRoleAuthorities());
    }
}
