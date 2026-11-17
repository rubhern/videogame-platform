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
import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase.Batch;
import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase.Report;
import com.videogameplatform.identity.adapter.web.ProductRoleAuthorities;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
class CatalogueTranslationsApiIntegrationTest {
    private static final String PATH = "/admin/catalogue/translations/";
    private static final String API = "/api/v1" + PATH;
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("translation_api");

    @Autowired MockMvc mvc;
    @MockitoBean LocalizeCatalogueUseCase localization;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, false);
    }

    @Test
    void coverageAndExecutionAreOperatorOnlyAndMatchTheContract() throws Exception {
        for (String endpoint : List.of("coverage", "execution")) {
            mvc.perform(get(API + endpoint)).andExpect(status().isUnauthorized());
            mvc.perform(get(API + endpoint).with(session(false))).andExpect(status().isForbidden());
            var response =
                    mvc.perform(get(API + endpoint).with(session(true)))
                            .andExpect(status().isOk())
                            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                            .andReturn()
                            .getResponse();
            OpenApiResponseContract.load(PATH + endpoint)
                    .assertJsonResponse(
                            response,
                            200,
                            endpoint.equals("coverage")
                                    ? "TranslationCoverage"
                                    : "TranslationExecution");
            mvc.perform(get(API + endpoint + "?actor=operator").with(session(true)))
                    .andExpect(status().isUnprocessableContent());
        }
    }

    @Test
    void commandRequiresPrincipalRoleCsrfAndSameOriginAndRejectsUnknownInput() throws Exception {
        var id = UUID.randomUUID();
        for (String action : List.of("start", "stop", "resume")) {
            var body =
                    """
                {"action":"%s","executionId":"%s"}
                """
                            .formatted(action, id);
            mvc.perform(
                            post(API + "execution")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isUnauthorized());
            mvc.perform(
                            post(API + "execution")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                                    .with(session(false))
                                    .with(csrf().asHeader()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mvc.perform(
                            post(API + "execution")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                                    .with(session(true)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
            mvc.perform(
                            post(API + "execution")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                                    .with(session(true))
                                    .with(csrf().asHeader())
                                    .header(HttpHeaders.ORIGIN, "https://elsewhere.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
            var conflict =
                    mvc.perform(
                                    post(API + "execution")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(body)
                                            .with(session(true))
                                            .with(csrf().asHeader()))
                            .andExpect(status().isConflict())
                            .andExpect(jsonPath("$.code").value("TRANSLATION_EXECUTION_CONFLICT"))
                            .andReturn()
                            .getResponse();
            OpenApiResponseContract.load(PATH + "execution", "post")
                    .assertJsonResponse(conflict, 409, "Problem");
        }
        mvc.perform(
                        post(API + "execution")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"start\",\"operator\":\"spoofed\"}")
                                .with(session(true))
                                .with(csrf().asHeader()))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void acceptedExecutionRunsAsynchronouslyAndPublishesItsCheckpoint() throws Exception {
        var first = new UUID(0, 0);
        org.mockito.Mockito.when(localization.scan("", first, 3))
                .thenReturn(new Batch("", first, 0, true, new Report(0, 0, 0, 0)));
        var response =
                mvc.perform(
                                post(API + "execution")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"action\":\"start\"}")
                                        .with(session(true))
                                        .with(csrf().asHeader()))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.state").value("running"))
                        .andExpect(jsonPath("$.executionId").isString())
                        .andReturn()
                        .getResponse();
        OpenApiResponseContract.load(PATH + "execution", "post")
                .assertJsonResponse(response, 202, "TranslationExecution");
        String body;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        do {
            body =
                    mvc.perform(get(API + "execution").with(session(true)))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            if (body.contains("\"completed\"")) break;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        assertThat(body).contains("\"completed\"", "\"processed\":0");
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
