package com.videogame.platform.game.integration;

import static org.junit.jupiter.api.Assertions.*;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestTemplate;

@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class GameStepDefinitions {

  @LocalServerPort private int port;

  private ResponseEntity<String> response;
  private final RestTemplate restTemplate = new RestTemplate();

  @Given("the application is running")
  public void the_application_is_running() {
    assertTrue(port > 0);
  }

  @When("I request the Game for id {long}")
  public void i_request_the_Game(Long id) {

    String url = String.format("http://localhost:%d/games/%d", port, id);
    response = restTemplate.getForEntity(url, String.class);
  }

  @Then("the response should contain id {long}")
  public void the_response_should_contain(Long expectedId) {
    assertEquals(200, response.getStatusCode().value());
    String body = response.getBody();
    assertNotNull(body);
    assertTrue(body.contains("\"id\":" + expectedId));
  }
}
