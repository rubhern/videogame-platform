package com.videogame.platform.game.application.usecases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.videogame.platform.game.application.query.GameHandler;
import com.videogame.platform.game.application.query.GameQuery;
import com.videogame.platform.game.domain.exception.GameNotFoundException;
import com.videogame.platform.game.domain.model.entities.Game;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetGameTest {

    @Mock private GameHandler handler;
    @InjectMocks private GetGame getGameUseCase;

    @Test
    void should_delegate_to_handler_and_return_result() {
        // Given
        long id = 1L;

        Game expectedGame = Game.builder().id(id).build();

        when(handler.handle(any(GameQuery.class))).thenReturn(Optional.of(expectedGame));

        // When
        Game result = getGameUseCase.apply(id);

        // Then
        assertEquals(expectedGame, result);

        verify(handler, times(1)).handle(argThat(query -> query.id() == id));
    }

    @Test
    void should_throw_exception_when_no_Game_found() {
        // Given
        long id = 1L;

        when(handler.handle(any(GameQuery.class))).thenReturn(Optional.empty());

        // When + Then
        assertThrows(GameNotFoundException.class, () -> getGameUseCase.apply(id));

        verify(handler, times(1)).handle(any(GameQuery.class));
    }
}
