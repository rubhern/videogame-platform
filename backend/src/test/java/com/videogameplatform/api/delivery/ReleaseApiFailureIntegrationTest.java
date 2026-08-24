package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.ReleaseQueryValidationException;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class ReleaseApiFailureIntegrationTest {

    private static final String DATABASE_NAME = "release_api_failures";
    private static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static {
        try {
            PostgreSqlTestDatabase.createDatabase(DATABASE_NAME);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BrowseReleasesUseCase useCase;

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url", () -> PostgreSqlTestDatabase.runtimeUrl(DATABASE_NAME));
        registry.add("spring.datasource.username", PostgreSqlTestDatabase::runtimeUsername);
        registry.add("spring.datasource.password", PostgreSqlTestDatabase::runtimePassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.url", () -> PostgreSqlTestDatabase.adminUrl(DATABASE_NAME));
        registry.add("spring.flyway.user", PostgreSqlTestDatabase::migratorUsername);
        registry.add("spring.flyway.password", PostgreSqlTestDatabase::migratorPassword);
    }

    @BeforeEach
    void captureFailureLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingFailureLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void unexpectedFailureReturnsSafeContractedProblemAndLogsOriginalExceptionOnce()
            throws Exception {
        String correlationId = "owner-correlation-79";
        var failure = new IllegalStateException("private implementation path and SQL");
        doThrow(failure).when(useCase).browse(any());

        var result =
                mockMvc.perform(
                                get("/api/v1/releases")
                                        .queryParam("view", "recent")
                                        .header(HttpHeaders.ACCEPT, "application/json")
                                        .header(CORRELATION_HEADER, correlationId))
                        .andExpect(status().isInternalServerError())
                        .andExpect(header().string(CORRELATION_HEADER, correlationId))
                        .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").stringValue()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.path("category").stringValue()).isEqualTo("technical");
        assertThat(body.path("correlationId").stringValue()).isEqualTo(correlationId);
        assertThat(body.toString())
                .doesNotContain(
                        failure.getMessage(),
                        failure.getClass().getName(),
                        "stackTrace",
                        "jdbc",
                        "postgresql");

        ILoggingEvent event = singleFailureEvent();
        assertThat(keyValue(event, "error.code")).isEqualTo("INTERNAL_ERROR");
        assertThat(loggedThrowable(event)).isSameAs(failure);
        assertThat(event.getMDCPropertyMap()).containsEntry("correlationId", correlationId);
    }

    @Test
    void technicalCatalogueFailureReturnsSafe503AndLogsPreservedCauseOnce() throws Exception {
        SQLException rootCause = new SQLException("jdbc://private-host/catalogue", "08006");
        var failure = new CatalogueReadException(rootCause);
        doThrow(failure).when(useCase).browse(any());

        var result =
                mockMvc.perform(
                                get("/api/v1/releases")
                                        .queryParam("view", "recent")
                                        .header(HttpHeaders.ACCEPT, "application/json")
                                        .header(CORRELATION_HEADER, "unsafe correlation/value"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().exists(CORRELATION_HEADER))
                        .andReturn();

        String effectiveCorrelationId = result.getResponse().getHeader(CORRELATION_HEADER);
        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(UUID.fromString(effectiveCorrelationId)).isNotNull();
        assertThat(body.path("code").stringValue()).isEqualTo("CATALOGUE_READ_FAILED");
        assertThat(body.path("correlationId").stringValue()).isEqualTo(effectiveCorrelationId);
        assertThat(body.toString())
                .doesNotContain(rootCause.getMessage(), "SQLException", "jdbc", "private-host");

        ILoggingEvent event = singleFailureEvent();
        assertThat(keyValue(event, "error.code")).isEqualTo("CATALOGUE_READ_FAILED");
        assertThat(loggedThrowable(event)).isSameAs(failure);
        assertThat(failure).hasCause(rootCause);
        assertThat(event.getMDCPropertyMap())
                .containsEntry("correlationId", effectiveCorrelationId);
    }

    @Test
    void validationFailureDoesNotEmitTechnicalErrorLog() throws Exception {
        mockMvc.perform(
                        get("/api/v1/releases")
                                .queryParam("view", "invalid")
                                .header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists(CORRELATION_HEADER));

        mockMvc.perform(
                        get("/api/v1/releases")
                                .queryParam("view", "recent")
                                .queryParam("page", "1", "2")
                                .header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists(CORRELATION_HEADER));

        mockMvc.perform(
                        get("/api/v1/releases")
                                .queryParam("view", "recent")
                                .queryParam("platformId", "ps5", "windows")
                                .header(HttpHeaders.ACCEPT, "application/json"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(header().exists(CORRELATION_HEADER));

        assertThat(appender.list).isEmpty();
    }

    @Test
    void unsupportedRegionUsesItsContractedTypedProblemCodeWithoutTechnicalLogging()
            throws Exception {
        doThrow(
                        new ReleaseQueryValidationException(
                                ReleaseQueryValidationException.Code.REGION_NOT_SUPPORTED))
                .when(useCase)
                .browse(any());

        var result =
                mockMvc.perform(
                                get("/api/v1/releases")
                                        .queryParam("view", "recent")
                                        .queryParam("regionId", "not-supported")
                                        .header(HttpHeaders.ACCEPT, "application/json"))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(header().exists(CORRELATION_HEADER))
                        .andReturn();

        JsonNode body = OBJECT_MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").stringValue()).isEqualTo("REGION_NOT_SUPPORTED");
        assertThat(body.path("violations").get(0).path("pointer").stringValue())
                .isEqualTo("/query/regionId");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void unsupportedRequestProblemCodeFallsBackWithoutBreakingProblemConstruction() {
        var response = new MockHttpServletResponse();
        response.setHeader(CORRELATION_HEADER, "safe-fallback-correlation");
        var exception = new ApiRequestException(ProblemCode.GAME_NOT_FOUND, "/query/view");

        var result = new ApiExceptionHandler().requestInvalid(exception, response);

        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getCode()).isEqualTo(ProblemCode.INTERNAL_ERROR);
        assertThat(result.getBody().getCorrelationId()).isEqualTo("safe-fallback-correlation");
        ILoggingEvent event = singleFailureEvent();
        assertThat(keyValue(event, "error.code")).isEqualTo("INTERNAL_ERROR");
        assertThat(loggedThrowable(event)).isSameAs(exception);
    }

    @Test
    void directProblemConstructionReusesMdcCorrelationWhenTheServletFilterIsAbsent() {
        var response = new MockHttpServletResponse();
        MDC.put("correlationId", "mdc-correlation");
        try {
            var result =
                    new ApiExceptionHandler()
                            .requestInvalid(
                                    new ApiRequestException(
                                            ProblemCode.FILTER_INVALID, "/query/view"),
                                    response);

            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getCorrelationId()).isEqualTo("mdc-correlation");
            assertThat(response.getHeader(CORRELATION_HEADER)).isEqualTo("mdc-correlation");
        } finally {
            MDC.remove("correlationId");
        }
    }

    @Test
    void directProblemConstructionGeneratesOneCorrelationWhenNoRequestContextExists() {
        var response = new MockHttpServletResponse();

        var result =
                new ApiExceptionHandler()
                        .requestInvalid(
                                new ApiRequestException(ProblemCode.FILTER_INVALID, "/query/view"),
                                response);

        assertThat(result.getBody()).isNotNull();
        String correlationId = result.getBody().getCorrelationId();
        assertThat(UUID.fromString(correlationId)).isNotNull();
        assertThat(response.getHeader(CORRELATION_HEADER)).isEqualTo(correlationId);
    }

    private ILoggingEvent singleFailureEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst();
    }

    private static Throwable loggedThrowable(ILoggingEvent event) {
        assertThat(event.getThrowableProxy()).isInstanceOf(ThrowableProxy.class);
        return ((ThrowableProxy) event.getThrowableProxy()).getThrowable();
    }

    private static Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }
}
