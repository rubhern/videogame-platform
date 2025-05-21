package com.videogame.platform.game.infrastructure.adapter.out.persistence;

import com.videogame.platform.game.infrastructure.adapter.out.persistence.model.GameEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface GameJpaRepository extends JpaRepository<GameEntity, Long> {

    Optional<GameEntity> findGameEntityById(final Long id);
}
