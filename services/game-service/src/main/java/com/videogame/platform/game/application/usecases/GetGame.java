package com.videogame.platform.game.application.usecases;

import com.videogame.platform.game.application.query.GameHandler;
import com.videogame.platform.game.application.query.GameQuery;
import com.videogame.platform.game.domain.exception.GameNotFoundException;
import com.videogame.platform.game.domain.model.entities.Game;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.LongFunction;

@Component
@RequiredArgsConstructor
@Slf4j
public class GetGame implements LongFunction<Game> {

    private final GameHandler handler;

    @Override
    public Game apply(long id) {
        GameQuery query = GameQuery.builder()
                .id(id)
                .build();

        return handler.handle(query).orElseThrow(() -> {
            log.warn("No Game found for id={}", id);
            return new GameNotFoundException("No Game found for id=" + id);
        });
    }
}
