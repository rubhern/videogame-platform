package com.videogameplatform.catalogue.configuration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Type-safe runtime configuration for bounded catalogue search. */
@Validated
@ConfigurationProperties("catalogue.search")
record CatalogueSearchProperties(@Min(1) @Max(10) int releaseContextLimit) {}
