package com.videogame.platform.game.infrastructure.adapter.in.rest.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.infrastructure.adapter.in.rest.dto.GameResponse;
import org.junit.jupiter.api.Test;

class GameRestMapperTest {
  private final GameRestMapper mapper = new GameRestMapperImpl();

  @Test
  void should_map_Game_entity_to_response_dto_correctly() {
    // Given
    Game game = Game.builder().id(1L).build();

    // When
    GameResponse response = mapper.toResponse(game);

    // Then
    assertEquals(1L, response.getId());
  }
}
