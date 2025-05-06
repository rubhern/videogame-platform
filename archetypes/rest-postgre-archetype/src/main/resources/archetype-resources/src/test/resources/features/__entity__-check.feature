Feature: ${entity} consultation

  Scenario Outline: Query product ${entity} at a specific date and time
    Given the application is running
    When I request the ${entity} for id <id>
    Then the response should contain id <id>

    Examples:
      | id        |
      | 1         |