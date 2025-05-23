package com.videogame.platform.game.infrastructure.adapter.out.persistence;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.domain.ports.out.repository.GameService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class GameRepositoryAdapterIT {

    @Autowired
    private GameService adapter;

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
