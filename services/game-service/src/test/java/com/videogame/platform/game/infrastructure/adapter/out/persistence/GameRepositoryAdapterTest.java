package com.videogame.platform.game.infrastructure.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.infrastructure.adapter.out.persistence.mapper.GamePersistenceMapper;
import com.videogame.platform.game.infrastructure.adapter.out.persistence.model.GameEntity;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GameRepositoryAdapterTest {

  @Mock private GameJpaRepository jpaRepository;
  @Mock private GamePersistenceMapper mapper;
  @InjectMocks private GameRepositoryAdapter adapter;

  @Test
  void should_return_mapped_Game() {
    // Given
    Long id = 1L;

    GameEntity entity = mock(GameEntity.class);
    Game game = mock(Game.class);

    when(jpaRepository.findGameEntityById(id)).thenReturn(Optional.of(entity));
    when(mapper.toDomain(entity)).thenReturn(game);

    // When
    Optional<Game> result = adapter.findGame(1L);

    // Then
    assertFalse(result.isEmpty());

    verify(jpaRepository).findGameEntityById(id);
    verify(mapper).toDomain(entity);
  }
}
