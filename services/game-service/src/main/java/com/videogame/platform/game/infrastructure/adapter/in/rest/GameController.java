package com.videogame.platform.game.infrastructure.adapter.in.rest;

import com.videogame.platform.game.application.usecases.GetGame;
import com.videogame.platform.game.infrastructure.adapter.in.rest.api.GamesApi;
import com.videogame.platform.game.infrastructure.adapter.in.rest.dto.GameResponse;
import com.videogame.platform.game.infrastructure.adapter.in.rest.mapper.GameRestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GameController implements GamesApi {

  private final GetGame getGame;
  private final GameRestMapper gameRestMapper;

  @Override
  public ResponseEntity<GameResponse> getGameById(Long id) {
    var game = getGame.apply(id);
    return ResponseEntity.ok(gameRestMapper.toResponse(game));
  }
}
