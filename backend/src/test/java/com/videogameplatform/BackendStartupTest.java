package com.videogameplatform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendStartupTest {

    @LocalServerPort private int port;

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
