package com.videogame.platform.game.domain.model.entities;

import static java.util.Objects.requireNonNull;

public class Game {

    private final Long id;

    private Game(Builder builder) {
        this.id = requireNonNull(builder.id, "Id is required");
    }

    // Getters
    public Long getId() { return id; }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Game build() {
            return new Game(this);
        }
    }
}
