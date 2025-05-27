package com.videogame.platform.game.infrastructure.adapter.out.persistence.mapper;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.infrastructure.adapter.out.persistence.model.GameEntity;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface GamePersistenceMapper {

  Game toDomain(GameEntity entity);

  GameEntity toEntity(Game game);
}
