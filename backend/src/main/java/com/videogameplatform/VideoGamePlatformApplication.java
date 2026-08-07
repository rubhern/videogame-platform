package com.videogameplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.modulith.Modulith;

/** Entry point for the backend modular monolith. */
@Modulith(systemName = "VideoGame Platform")
public class VideoGamePlatformApplication {

    private VideoGamePlatformApplication() {}

    public static void main(String[] args) {
        SpringApplication.run(VideoGamePlatformApplication.class, args);
    }
}
