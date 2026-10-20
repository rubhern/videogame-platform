package com.videogameplatform.api.delivery.catalogue.preferences;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * HTTP evidence for UC-014: the owner is the session's server-derived user, commands need CSRF
 * proof, personal responses are never stored, invalid choices change nothing, and the selectable
 * platform list stays a public, validator-backed read.
 */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class PlatformPreferencesApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("platform_preferences_api");
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String ALICE = "alice-subject";
    private static final String BOB = "bob-subject";
    private static final String PATH = "/api/v1/me/platform-preferences";
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String SWITCH_2 = "10000000-0000-4000-8000-000000000002";
    private static final OpenApiResponseContract GET_CONTRACT =
            OpenApiResponseContract.load("/me/platform-preferences");
    private static final OpenApiResponseContract PUT_CONTRACT =
            OpenApiResponseContract.load("/me/platform-preferences", "put");
    private static final OpenApiResponseContract LIST_CONTRACT =
            OpenApiResponseContract.load("/platforms");

    @Autowired MockMvc mockMvc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, true);
    }

    @BeforeEach
    void clearPreferences() {
        new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()))
                .update("DELETE FROM catalogue.platform_preference_choice");
    }

    @Test
    void aFirstReadIsNotConfiguredAndNeverStored() throws Exception {
        MvcResult read =
                mockMvc.perform(get(PATH).with(login(ALICE)))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.configured").value(false))
                        .andExpect(jsonPath("$.platforms").isEmpty())
                        .andReturn();
        GET_CONTRACT.assertJsonResponse(read.getResponse(), 200, "PlatformPreferences");
    }

    @Test
    void replacesReadsBackAndClearsTheSessionUsersOwnChoice() throws Exception {
        MvcResult saved =
                replace(
                                ALICE,
                                "{\"platformIds\":[\""
                                        + PS5
                                        + "\",\""
                                        + SWITCH_2
                                        + "\",\""
                                        + PS5
                                        + "\"]}")
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.configured").value(true))
                        .andExpect(jsonPath("$.platforms[0].name").value("Nintendo Switch 2"))
                        .andExpect(jsonPath("$.platforms[1].platformId").value(PS5))
                        .andReturn();
        PUT_CONTRACT.assertJsonResponse(saved.getResponse(), 200, "PlatformPreferences");

        mockMvc.perform(get(PATH).with(login(ALICE)))
                .andExpect(jsonPath("$.platforms.length()").value(2));
        // Another user, and the same user in another session, read only their own state.
        mockMvc.perform(get(PATH).with(login(BOB)))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.platforms").isEmpty());

        replace(ALICE, "{\"platformIds\":[]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.platforms").isEmpty());
        mockMvc.perform(get(PATH).with(login(ALICE)))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.platforms").isEmpty());
    }

    @Test
    void anonymousReadsAndCommandsWithoutCsrfProofAreRejected() throws Exception {
        mockMvc.perform(get(PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(
                        put(PATH)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"platformIds\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        put(PATH)
                                .with(login(ALICE))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"platformIds\":[\"" + PS5 + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));

        mockMvc.perform(get(PATH).with(login(ALICE)))
                .andExpect(jsonPath("$.configured").value(false));
    }

    @Test
    void invalidChoicesChangeNothing() throws Exception {
        replace(ALICE, "{\"platformIds\":[\"" + PS5 + "\"]}").andExpect(status().isOk());

        MvcResult unknown =
                replace(ALICE, "{\"platformIds\":[\"" + SWITCH_2 + "\",\"platform_unknown\"]}")
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.code").value("PLATFORM_NOT_SUPPORTED"))
                        .andExpect(jsonPath("$.violations[0].pointer").value("/platformIds"))
                        .andReturn();
        PUT_CONTRACT.assertJsonResponse(unknown.getResponse(), 422, "Problem");
        replace(ALICE, "{\"platformIds\":[],\"userId\":\"someone-else\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PROPERTY_UNKNOWN"));
        String tooMany =
                IntStream.range(0, 51)
                        .mapToObj(index -> "\"platform-" + index + "\"")
                        .collect(Collectors.joining(",", "{\"platformIds\":[", "]}"));
        replace(ALICE, tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
        mockMvc.perform(get(PATH + "?userId=other").with(login(ALICE)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PARAMETER_UNKNOWN"));

        mockMvc.perform(get(PATH).with(login(ALICE)))
                .andExpect(jsonPath("$.platforms.length()").value(1))
                .andExpect(jsonPath("$.platforms[0].platformId").value(PS5));
    }

    @Test
    void listsTheSelectablePlatformsPubliclyWithAValidator() throws Exception {
        MvcResult list =
                mockMvc.perform(get("/api/v1/platforms"))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, no-cache"))
                        .andExpect(jsonPath("$.items[0].platformId").exists())
                        .andReturn();
        LIST_CONTRACT.assertJsonResponse(list.getResponse(), 200, "PlatformList");
        String entityTag = list.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(entityTag).isNotBlank();
        // A session never changes the public representation.
        mockMvc.perform(get("/api/v1/platforms").with(login(ALICE)))
                .andExpect(header().string(HttpHeaders.ETAG, entityTag));
        mockMvc.perform(get("/api/v1/platforms").header(HttpHeaders.IF_NONE_MATCH, entityTag))
                .andExpect(status().isNotModified());
        mockMvc.perform(get("/api/v1/platforms?page=1"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PARAMETER_UNKNOWN"));
    }

    private ResultActions replace(String subject, String body) throws Exception {
        return mockMvc.perform(
                put(PATH)
                        .with(login(subject))
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private static RequestPostProcessor login(String subject) {
        return oidcSession(
                token -> token.issuer(ISSUER).subject(subject).audience(List.of("test-bff")));
    }
}
