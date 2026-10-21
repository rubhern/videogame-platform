package com.videogameplatform.ratings.application;

import java.util.List;

/**
 * A create or update command for the caller's rating of one game. {@code value} is the direct
 * score. {@code platforms}, when not null, replaces the whole played-platform set (empty clears
 * it); null keeps the saved set, so a command with only a value never touches saved platforms.
 */
public record PersonalRatingCommand(Integer value, List<PlatformChoice> platforms) {
    public PersonalRatingCommand {
        platforms = platforms == null ? null : List.copyOf(platforms);
    }

    /** A direct score that keeps the saved platforms, as every command before #359 was. */
    public static PersonalRatingCommand direct(int value) {
        return new PersonalRatingCommand(value, null);
    }

    /** One named platform with the user's optional score there. */
    public record PlatformChoice(String platformId, Integer value) {}
}
