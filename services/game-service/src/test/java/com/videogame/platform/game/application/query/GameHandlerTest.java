package com.videogame.platform.game.application.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.domain.ports.out.repository.GameService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GameHandlerTest {

    @Mock private GameService repository;
    @InjectMocks private GameHandler handler;

    private static GameQuery query;

    @BeforeAll
    public static void setUp() {
        query = new GameQuery(1L);
    }

    @Test
    void should_return_Game() {
        // Given
        Game game = Game.builder().id(1L).build();

        when(repository.findGame(1L)).thenReturn(Optional.of(game));

        // When
        Optional<Game> result = handler.handle(query);

        // Then
        assertTrue(result.isPresent());
        assertEquals(1L, result.get().getId());
    }

    @Test
    void should_return_empty_if_no_applicable_Game() {
        // Given
        when(repository.findGame(1L)).thenReturn(Optional.empty());

        // When
        Optional<Game> result = handler.handle(query);

        // Then
        assertTrue(result.isEmpty());
    }
}
