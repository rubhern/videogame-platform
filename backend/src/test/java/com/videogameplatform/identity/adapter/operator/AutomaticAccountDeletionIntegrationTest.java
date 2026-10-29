package com.videogameplatform.identity.adapter.operator;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.identity.domain.UserId;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "identity.deletion-executor.enabled=true",
            "identity.oidc.issuer-uri=https://identity.example.test/realms/gameometro"
        })
@AutoConfigureMockMvc
class AutomaticAccountDeletionIntegrationTest {
    static final String DB = PostgreSqlTestDatabase.isolatedDatabaseName("automatic_erasure");
    static final String SUBJECT = "11111111-1111-4111-8111-111111111111";
    static final String ISSUER = "https://identity.example.test/realms/gameometro";
    static final String OWNER = UserId.fromIssuerAndSubject(ISSUER, SUBJECT).value();
    static final Path CREDENTIAL = credential();
    @LocalManagementPort int management;
    @LocalServerPort int product;
    @Autowired MockMvc mvc;
    final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void setup(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
        registry.add("identity.deletion-executor.credential-file", CREDENTIAL::toString);
    }

    static Path credential() {
        try {
            var file = Files.createTempFile("synthetic-erasure-", ".key");
            Files.writeString(
                    file,
                    UUID.randomUUID().toString().replace("-", "")
                            + UUID.randomUUID().toString().replace("-", ""));
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return file;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    @Timeout(180)
    void automaticPollingReusesTheLedgerAndRecoversAfterProductDeletionBeforeIdentityDeletion()
            throws Exception {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DB),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        String game =
                jdbc.queryForObject(
                        "SELECT game_id::text FROM catalogue.game ORDER BY game_id LIMIT 1",
                        String.class);
        String other = UUID.randomUUID().toString();
        for (String owner : java.util.List.of(OWNER, other))
            jdbc.update(
                    "INSERT INTO ratings.rating(user_id,game_id,value,created_at,updated_at,version_token) VALUES (?::uuid,?::uuid,8,now(),now(),?::uuid)",
                    owner,
                    game,
                    UUID.randomUUID().toString());
        String url = "http://127.0.0.1:" + management + "/actuator/accountdeletionexecutor";
        assertThat(
                        exchange(
                                        "http://127.0.0.1:"
                                                + product
                                                + "/actuator/accountdeletionexecutor",
                                        "{}",
                                        null)
                                .statusCode())
                .isEqualTo(403);
        assertThat(exchange(url, "{\"action\":\"claim\"}", null).statusCode()).isEqualTo(401);
        assertThat(
                        http.send(
                                        HttpRequest.newBuilder(URI.create(url))
                                                .header("Origin", "https://attacker.test")
                                                .header(
                                                        "Authorization",
                                                        "Bearer " + Files.readString(CREDENTIAL))
                                                .header("Content-Type", "application/json")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                "{\"action\":\"claim\"}"))
                                                .build(),
                                        HttpResponse.BodyHandlers.ofString())
                                .statusCode())
                .isEqualTo(403);
        var process =
                new ProcessBuilder("python3", "-I", "../scripts/test-account-deletion-runner.py");
        process.environment()
                .putAll(
                        java.util.Map.of(
                                "PD_TEST_POSTGRES",
                                PostgreSqlTestDatabase.containerId(),
                                "PD_TEST_DATABASE",
                                DB,
                                "PD_TEST_REPOSITORY",
                                Path.of("..").toAbsolutePath().normalize().toString(),
                                "PD_TEST_MANAGEMENT_URL",
                                url,
                                "PD_TEST_CREDENTIAL",
                                CREDENTIAL.toString()));
        Path diagnostics = Files.createTempFile("synthetic-runner-", ".log");
        Process runner = process.redirectError(diagnostics.toFile()).start();
        try {
            var reader = runner.inputReader();
            assertThat(reader.readLine()).isEqualTo("READY");
            var accepted =
                    mvc.perform(
                                    post("/api/v1/me/account-deletion")
                                            .with(
                                                    oidcSession(
                                                            t -> t.issuer(ISSUER).subject(SUBJECT)))
                                            .with(csrf().asHeader())
                                            .contentType("application/json")
                                            .content("{\"confirmation\":\"ELIMINAR\"}"))
                            .andExpect(status().isAccepted())
                            .andReturn();
            var receipt = accepted.getResponse().getCookie("gameometro_deletion");
            await(
                    () ->
                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM ratings.rating WHERE user_id=?::uuid",
                                                    Integer.class,
                                                    OWNER)
                                            == 0
                                    && jdbc.queryForObject(
                                                    "SELECT count(*) FROM identity.account_deletion WHERE state='pending' AND claim IS NULL",
                                                    Integer.class)
                                            == 1);
            mvc.perform(get("/api/v1/account-deletion").cookie(receipt))
                    .andExpect(jsonPath("$.state").value("pending"));
            mvc.perform(
                            get("/api/v1/me/profile")
                                    .with(oidcSession(t -> t.issuer(ISSUER).subject(SUBJECT))))
                    .andExpect(status().isUnauthorized());
            // Advance only disposable retry eligibility, without shortening the production backoff.
            jdbc.update(
                    "UPDATE identity.account_deletion SET next_attempt_at=now() WHERE state='pending'");
            await(
                    () ->
                            jdbc.queryForObject(
                                            "SELECT count(*) FROM identity.account_deletion WHERE state='complete' AND ledger_digest IS NOT NULL AND proof_digest IS NOT NULL",
                                            Integer.class)
                                    == 1);
            mvc.perform(get("/api/v1/account-deletion").cookie(receipt))
                    .andExpect(jsonPath("$.state").value("complete"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM ratings.rating WHERE user_id=?::uuid",
                                    Integer.class,
                                    other))
                    .isEqualTo(1);
            assertThat(
                            exchange(
                                            url,
                                            "{\"action\":\"finish\",\"userId\":\""
                                                    + OWNER
                                                    + "\",\"requestId\":\""
                                                    + UUID.randomUUID()
                                                    + "\",\"claim\":\""
                                                    + UUID.randomUUID()
                                                    + "\",\"verified\":true}",
                                            Files.readString(CREDENTIAL))
                                    .statusCode())
                    .isEqualTo(400);
        } finally {
            runner.destroy();
            if (!runner.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                runner.destroyForcibly();
            Files.deleteIfExists(CREDENTIAL);
        }
    }

    HttpResponse<String> exchange(String url, String body, String token) throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline)
                throw new AssertionError("Synthetic automatic deletion did not progress");
            Thread.sleep(100);
        }
    }
}
