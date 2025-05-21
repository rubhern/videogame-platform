package com.videogame.platform.game.domain.ports.out.repository;

import com.videogame.platform.game.domain.model.entities.Game;

import java.util.Optional;

public interface GameService {
    Optional<Game> findGame(final Long id);
}
