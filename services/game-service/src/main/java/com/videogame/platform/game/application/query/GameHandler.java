package com.videogame.platform.game.application.query;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.domain.ports.out.repository.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class GameHandler {

    private final GameService GameService;

    public Optional<Game> handle(GameQuery query) {
        return GameService.findGame(
                query.getId());
    }
}
