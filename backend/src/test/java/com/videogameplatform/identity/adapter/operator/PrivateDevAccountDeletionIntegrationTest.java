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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0", "identity.deletion-executor.enabled=true",
            "spring.profiles.active=private-dev",
                    "identity.deletion-executor.completion-policy=private-dev",
            "identity.oidc.issuer-uri=https://identity.example.test/realms/videogame-platform"
        })
@AutoConfigureMockMvc
class PrivateDevAccountDeletionIntegrationTest {
    static final String DB = PostgreSqlTestDatabase.isolatedDatabaseName("dev_erasure");
    static final String SUBJECT = "11111111-1111-4111-8111-111111111111";
    static final String ISSUER = "https://identity.example.test/realms/videogame-platform";
    static final String OWNER = UserId.fromIssuerAndSubject(ISSUER, SUBJECT).value();
    static final Path CREDENTIAL = AutomaticAccountDeletionIntegrationTest.credential();
    @LocalManagementPort int management;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void setup(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DB, true);
        registry.add("identity.deletion-executor.credential-file", CREDENTIAL::toString);
    }

    @Test
    @Timeout(180)
    void closesBrowserSessionImmediatelyAndAutomaticallyRetriesWithoutExternalEvidence()
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
        var process =
                new ProcessBuilder(
                        "python3", "-I", "../scripts/test-private-dev-account-deletion-runner.py");
        process.environment()
                .putAll(
                        Map.of(
                                "PD_TEST_POSTGRES",
                                PostgreSqlTestDatabase.containerId(),
                                "PD_TEST_DATABASE",
                                DB,
                                "PD_TEST_REPOSITORY",
                                Path.of("..").toAbsolutePath().normalize().toString(),
                                "PD_TEST_MANAGEMENT_URL",
                                "http://127.0.0.1:"
                                        + management
                                        + "/actuator/accountdeletionexecutor",
                                "PD_TEST_CREDENTIAL",
                                CREDENTIAL.toString()));
        var diagnostics = Files.createTempFile("synthetic-dev-runner-", ".log");
        Process runner = process.redirectError(diagnostics.toFile()).start();
        try {
            assertThat(runner.inputReader().readLine()).isEqualTo("READY");
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
            assertThat(accepted.getRequest().getSession(false)).isNull();
            mvc.perform(get("/api/v1/session")).andExpect(jsonPath("$.authenticated").value(false));
            AutomaticAccountDeletionIntegrationTest.await(
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
            mvc.perform(
                            get("/api/v1/me/profile")
                                    .with(oidcSession(t -> t.issuer(ISSUER).subject(SUBJECT))))
                    .andExpect(status().isUnauthorized());
            jdbc.update(
                    "UPDATE identity.account_deletion SET next_attempt_at=now() WHERE state='pending'");
            AutomaticAccountDeletionIntegrationTest.await(
                    () ->
                            jdbc.queryForObject(
                                            "SELECT count(*) FROM identity.account_deletion WHERE state='complete' AND completion_policy='private-dev' AND ledger_digest IS NULL AND proof_digest IS NULL",
                                            Integer.class)
                                    == 1);
            mvc.perform(
                            get("/api/v1/account-deletion")
                                    .cookie(
                                            accepted.getResponse()
                                                    .getCookie("gameometro_deletion")))
                    .andExpect(jsonPath("$.state").value("complete"));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM ratings.rating WHERE user_id=?::uuid",
                                    Integer.class,
                                    other))
                    .isEqualTo(1);
            var named =
                    new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
            var independent =
                    new com.videogameplatform.identity.adapter.persistence.JdbcAccountDeletionStore(
                            named);
            var development =
                    new com.videogameplatform.identity.adapter.persistence.JdbcAccountDeletionStore(
                            named, "private-dev");
            var now = java.time.Instant.now();
            independent.admit(
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    "f".repeat(64),
                    now);
            assertThat(development.claim(now)).isEmpty();
            var protectedClaim = independent.claim(now).orElseThrow();
            assertThat(development.acknowledge(protectedClaim, true, null, null, now)).isFalse();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> independent.finish(protectedClaim, true, now))
                    .isInstanceOf(IllegalArgumentException.class);
            independent.finish(protectedClaim, false, now);
            assertThat(independent.claim(now)).isEmpty();
            assertThat(independent.claim(now.plusSeconds(301)).orElseThrow().requestId())
                    .isEqualTo(protectedClaim.requestId());
        } finally {
            runner.destroy();
            if (!runner.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                runner.destroyForcibly();
            Files.deleteIfExists(CREDENTIAL);
        }
    }
}
