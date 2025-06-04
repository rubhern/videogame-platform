package com.videogame.platform.game.application.usecases;

import com.videogame.platform.game.application.query.GameHandler;
import com.videogame.platform.game.application.query.GameQuery;
import com.videogame.platform.game.domain.exception.GameNotFoundException;
import com.videogame.platform.game.domain.model.entities.Game;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GetGame implements LongFunction<Game> {

  private static final Logger log = LoggerFactory.getLogger(GetGame.class);
  private final GameHandler handler;

  public GetGame(GameHandler handler) {
    this.handler = handler;
  }

  @Override
  public Game apply(long id) {
    GameQuery query = new GameQuery(id);

    return handler
        .handle(query)
        .orElseThrow(
            () -> {
              log.warn("No Game found for id={}", id);
              return new GameNotFoundException("No Game found for id=" + id);
            });
  }
}
