package com.videogame.platform.game.application.query;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.domain.ports.out.repository.GameService;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class GameHandler {

  private final GameService gameService;

  public GameHandler(GameService gameService) {
    this.gameService = gameService;
  }

  public Optional<Game> handle(GameQuery query) {
    return gameService.findGame(query.id());
  }
}
