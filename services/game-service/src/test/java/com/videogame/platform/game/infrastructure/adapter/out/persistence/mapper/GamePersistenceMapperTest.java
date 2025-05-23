package com.videogame.platform.game.infrastructure.adapter.out.persistence.mapper;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.infrastructure.adapter.out.persistence.model.GameEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GamePersistenceMapperTest {

    private final GamePersistenceMapper mapper = Mappers.getMapper(GamePersistenceMapper.class);

    @Test
    void should_map_entity_to_domain() {
        // Given
        GameEntity entity = new GameEntity();
        entity.setId(1L);

        // When
        Game game = mapper.toDomain(entity);

        // Then
        assertEquals(1L, game.getId());
    }

    @Test
    void should_map_domain_to_entity() {
        // Given
        Game game = Game.builder()
                .id(1L)
                .build();

        // When
        GameEntity entity = mapper.toEntity(game);

        // Then
        assertNotNull(entity);
        assertEquals(1L, entity.getId());
    }
}