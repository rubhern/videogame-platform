package com.videogameplatform.identity.adapter.web;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded allowlist of product destinations; authentication and server routes are excluded. */
public final class AuthenticationReturnTarget {
    private static final int MAX_LENGTH = 2048;
    private static final Pattern GAME_PATH =
            Pattern.compile("/games/[a-z0-9-]{1,100}(?:/[a-z0-9-]{1,200})?");
    private static final Set<String> BROWSE_QUERY =
            Set.of("view", "month", "weeks", "platformIds", "regionIds", "page", "pageSize");
    private static final Set<String> SEARCH_QUERY = Set.of("q", "page", "pageSize");
    private static final Set<String> GAME_QUERY = Set.of("platformId", "regionId");

    private AuthenticationReturnTarget() {}

    public static String safePath(String candidate) {
        if (candidate == null || candidate.length() > MAX_LENGTH || !candidate.startsWith("/")) {
            return "/";
        }
        try {
            URI uri = URI.create(candidate);
            String path = uri.getRawPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
                return "/";
            }
            Set<String> allowed;
            if ("/".equals(path)) {
                allowed = BROWSE_QUERY;
            } else if ("/search".equals(path)) {
                allowed = SEARCH_QUERY;
            } else if ("/mis-puntuaciones".equals(path)) {
                allowed = Set.of();
            } else if (path != null && GAME_PATH.matcher(path).matches()) {
                allowed = GAME_QUERY;
            } else {
                return "/";
            }
            if (uri.getRawQuery() != null) {
                for (String part : uri.getRawQuery().split("&", -1)) {
                    String[] pair = part.split("=", 2);
                    String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                    String value =
                            URLDecoder.decode(
                                    pair.length == 2 ? pair[1] : "", StandardCharsets.UTF_8);
                    if (!allowed.contains(key)
                            || value.codePoints().anyMatch(Character::isISOControl)
                            || value.contains("\\")
                            || value.contains("%")) {
                        return "/";
                    }
                }
            }
            String target = uri.toASCIIString();
            return target.length() <= MAX_LENGTH ? target : "/";
        } catch (IllegalArgumentException invalidUri) {
            return "/";
        }
    }
}
