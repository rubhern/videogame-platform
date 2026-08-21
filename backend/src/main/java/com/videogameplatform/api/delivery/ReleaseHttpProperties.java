package com.videogameplatform.api.delivery;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Type-safe public caching configuration for the release endpoint. */
@Validated
@ConfigurationProperties("api.releases")
record ReleaseHttpProperties(@NotBlank String cacheControl) {}
