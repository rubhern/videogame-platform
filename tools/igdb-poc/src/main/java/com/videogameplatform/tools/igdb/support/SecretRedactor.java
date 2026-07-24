package com.videogameplatform.tools.igdb.support;

import java.util.Collection;

public final class SecretRedactor {

    private static final String REDACTED = "[REDACTED]";

    private SecretRedactor() {
    }

    public static String redact(String value, Collection<String> secrets) {
        if (value == null) {
            return "";
        }
        String redacted = value;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        redacted = redacted.replaceAll(
                "(?i)(access_token|client_secret|authorization)([\"'=: ]+)[^\\s,\"'}]+",
                "$1$2" + REDACTED);
        return redacted;
    }
}
