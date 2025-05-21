Feature: Game consultation

  Scenario Outline: Query product Game at a specific date and time
    Given the application is running
    When I request the Game for id <id>
    Then the response should contain id <id>

    Examples:
      | id        |
      | 1         |