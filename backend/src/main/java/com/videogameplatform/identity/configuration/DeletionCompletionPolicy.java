package com.videogameplatform.identity.configuration;

import java.net.URI;
import java.util.Arrays;

/** Owner-approved private-dev exception; public production can never select it. */
final class DeletionCompletionPolicy {
    private DeletionCompletionPolicy() {}

    static String validate(String policy, String issuer, String[] profiles) {
        if ("independent".equals(policy)) return policy;
        if (!"private-dev".equals(policy)
                || !Arrays.asList(profiles).contains("private-dev")
                || !"/realms/videogame-platform".equals(URI.create(issuer).getPath()))
            throw new IllegalStateException(
                    "Non-production deletion requires the explicit private-dev profile and realm");
        return policy;
    }
}
