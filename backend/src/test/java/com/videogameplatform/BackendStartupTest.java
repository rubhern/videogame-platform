package com.videogameplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.test.PostgreSqlTestDatabase;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendStartupTest {

    @LocalServerPort private int port;

    @DynamicPropertySource
    static void configurePostgreSql(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> PostgreSqlTestDatabase.runtimeUrl("backend_startup"));
        registry.add(
                "spring.datasource.username", PostgreSqlTestDatabase::runtimeUsername);
        registry.add(
                "spring.datasource.password", PostgreSqlTestDatabase::runtimePassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add(
                "spring.flyway.url", () -> PostgreSqlTestDatabase.adminUrl("backend_startup"));
        registry.add("spring.flyway.user", PostgreSqlTestDatabase::migratorUsername);
        registry.add("spring.flyway.password", PostgreSqlTestDatabase::migratorPassword);
    }

    @Test
    void startsOnJava25AndExposesHealth() throws IOException, InterruptedException {
        assertThat(Runtime.version().feature()).isEqualTo(25);

        var request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:%d/actuator/health".formatted(port)))
                        .GET()
                        .build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }
}
