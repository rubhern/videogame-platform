package com.videogameplatform.api.delivery.catalogue.search;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Type-safe public caching configuration for the catalogue search endpoint. */
@Validated
@ConfigurationProperties("api.games")
public record GameSearchHttpProperties(@NotBlank String cacheControl) {}
