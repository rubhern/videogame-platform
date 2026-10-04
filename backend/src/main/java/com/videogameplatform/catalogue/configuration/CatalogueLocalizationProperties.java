package com.videogameplatform.catalogue.configuration;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("catalogue.localization")
public record CatalogueLocalizationProperties(
        @DefaultValue("http://127.0.0.1:8092/translate") URI endpoint,
        @DefaultValue("30s") Duration timeout) {}
