Feature: ${entity} consultation

  Scenario Outline: Query ${entity}
    Given the application is running
    When I request the ${entity} for id <id>
    Then the response should contain id <id>

    Examples:
      | id        |
      | 1         |