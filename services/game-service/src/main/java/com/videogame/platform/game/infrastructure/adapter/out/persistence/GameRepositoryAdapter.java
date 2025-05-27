package com.videogame.platform.game.infrastructure.adapter.out.persistence;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.domain.ports.out.repository.GameService;
import com.videogame.platform.game.infrastructure.adapter.out.persistence.mapper.GamePersistenceMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GameRepositoryAdapter implements GameService {

  private final GameJpaRepository jpaRepository;
  private final GamePersistenceMapper mapper;

  @Override
  public Optional<Game> findGame(Long id) {
    return jpaRepository.findGameEntityById(id).map(mapper::toDomain);
  }
}
