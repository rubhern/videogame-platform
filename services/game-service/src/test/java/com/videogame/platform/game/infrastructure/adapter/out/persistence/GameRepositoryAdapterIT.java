package com.videogame.platform.game.infrastructure.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.domain.ports.out.repository.GameService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class GameRepositoryAdapterIT {

  @Autowired private GameService adapter;

  @Test
  void should_return_Game_from_database() {
    // Given
    Long id = 1L;

    // When
    Optional<Game> optionalGame = adapter.findGame(id);

    // Then
    assertFalse(optionalGame.isEmpty());
    Game game = optionalGame.get();
    assertEquals(id, game.getId());
  }
}
