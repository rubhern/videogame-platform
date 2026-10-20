package com.videogameplatform.catalogue.application.preferences;

import java.util.Objects;

/** One platform of the local taxonomy, named by its catalogue label. */
public record PlatformOption(String platformId, String name) {
    public PlatformOption {
        Objects.requireNonNull(platformId, "platformId");
        Objects.requireNonNull(name, "name");
    }
}
