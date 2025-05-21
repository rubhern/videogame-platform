package com.videogame.platform.game.infrastructure.adapter.out.persistence.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "Game")
@Getter
@Setter
public class GameEntity {

    @Id
    private Long id;
}
