package com.videogame.platform.game.infrastructure.adapter.out.persistence;

import com.videogame.platform.game.infrastructure.adapter.out.persistence.model.GameEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GameJpaRepository extends JpaRepository<GameEntity, Long> {

    Optional<GameEntity> findGameEntityById(final Long id);
}
