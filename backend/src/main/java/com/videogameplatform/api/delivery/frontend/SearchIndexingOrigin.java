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
    private static final String SCHEME = "https://";
    private static final Pattern LABEL = Pattern.compile("[a-z0-9-]{1,63}");
    private static final Pattern TOP_LEVEL_LABEL = Pattern.compile("[a-z]{2,63}");

    static SearchIndexingOrigin parse(String value) {
        var origin = value == null ? "" : value.strip();
        if (origin.isEmpty()) {
            return new SearchIndexingOrigin(Optional.empty());
        }
        if (!isBareHttpsOrigin(origin)) {
            throw new IllegalStateException(
                    PROPERTY
                            + " must be an https origin without port, path or trailing slash,"
                            + " such as https://gameometro.com, or blank");
        }
        return new SearchIndexingOrigin(Optional.of(origin));
    }

    /**
     * A lowercase host name of at least two dot-separated labels after the scheme, checked label by
     * label so no pattern repeats a group over the whole input.
     */
    private static boolean isBareHttpsOrigin(String origin) {
        if (!origin.startsWith(SCHEME)) {
            return false;
        }
        var labels = origin.substring(SCHEME.length()).split("\\.", -1);
        if (labels.length < 2 || !TOP_LEVEL_LABEL.matcher(labels[labels.length - 1]).matches()) {
            return false;
        }
        for (var label : labels) {
            if (!LABEL.matcher(label).matches() || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    boolean indexable() {
        return origin.isPresent();
    }

    String url(String path) {
        return origin.orElseThrow() + path;
    }
}
