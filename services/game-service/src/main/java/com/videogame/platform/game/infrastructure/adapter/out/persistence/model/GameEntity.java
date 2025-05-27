package com.videogame.platform.game.infrastructure.adapter.out.persistence.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "Game")
@Getter
@Setter
public class GameEntity {

  @Id private Long id;
}
