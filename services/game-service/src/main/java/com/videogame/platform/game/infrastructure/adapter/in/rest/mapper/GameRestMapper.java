package com.videogame.platform.game.infrastructure.adapter.in.rest.mapper;

import com.videogame.platform.game.domain.model.entities.Game;
import com.videogame.platform.game.infrastructure.adapter.in.rest.dto.GameResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper(componentModel = "spring")
public interface GameRestMapper {

    GameResponse toResponse(Game Game);
}
