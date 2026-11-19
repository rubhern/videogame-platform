package com.videogameplatform.api.delivery.frontend;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The one public origin a deployment offers to search engines (#537). Blank means the deployment
 * is not indexable: every environment except public production. A malformed value fails startup
 * rather than advertise a wrong host.
 */
record SearchIndexingOrigin(Optional<String> origin) {

    static final String PROPERTY = "api.search-indexing.public-origin";
    private static final Pattern HTTPS_ORIGIN =
            Pattern.compile("https://[a-z0-9]+(?:[.-][a-z0-9]+)*\\.[a-z]{2,63}");

    static SearchIndexingOrigin parse(String value) {
        var origin = value == null ? "" : value.strip();
        if (origin.isEmpty()) {
            return new SearchIndexingOrigin(Optional.empty());
        }
        if (!HTTPS_ORIGIN.matcher(origin).matches()) {
            throw new IllegalStateException(
                    PROPERTY
                            + " must be an https origin without port, path or trailing slash,"
                            + " such as https://gameometro.com, or blank");
        }
        return new SearchIndexingOrigin(Optional.of(origin));
    }

    boolean indexable() {
        return origin.isPresent();
    }

    String url(String path) {
        return origin.orElseThrow() + path;
    }
}
