package com.videogame.platform.game.infrastructure.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videogame.platform.game.application.usecases.GetGame;
import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.infrastructure.adapter.in.rest.dto.GameResponse;
import com.videogame.platform.game.infrastructure.adapter.in.rest.mapper.GameRestMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class GameControllerTest {

    @Mock private GetGame service;

    @Mock private GameRestMapper mapper;

    @InjectMocks private GameController controller;

    @Test
    void should_return_response_when_Game_found() {
        // Given
        long id = 1L;

        Game game = mock(Game.class);
        GameResponse response = mock(GameResponse.class);

        when(service.apply(id)).thenReturn(game);
        when(mapper.toResponse(game)).thenReturn(response);

        // When
        ResponseEntity<GameResponse> result = controller.getGameById(id);

        // Then
        assertEquals(200, result.getStatusCode().value());
        assertEquals(response, result.getBody());
    }
}
