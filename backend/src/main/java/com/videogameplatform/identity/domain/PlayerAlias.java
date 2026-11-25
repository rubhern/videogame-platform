package com.videogameplatform.identity.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** The canonical URL spelling and the product's reserved names. */
public final class PlayerAlias {
    private static final Pattern FORM = Pattern.compile("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*");
    private static final Set<String> RESERVED =
            Set.of(
                    "admin",
                    "administracion",
                    "administrador",
                    "administrator",
                    "api",
                    "auth",
                    "contacto",
                    "equipo",
                    "gameometro",
                    "gameometro-admin",
                    "gameometro-oficial",
                    "help",
                    "keycloak",
                    "moderacion",
                    "moderador",
                    "moderator",
                    "official",
                    "oficial",
                    "root",
                    "seguridad",
                    "soporte",
                    "staff",
                    "support",
                    "system",
                    "sistema",
                    "topometro");

    private PlayerAlias() {}

    public static Optional<String> canonical(String candidate) {
        if (candidate == null
                || candidate.length() < 3
                || candidate.length() > 24
                || !FORM.matcher(candidate).matches()) {
            return Optional.empty();
        }
        return Optional.of(candidate.toLowerCase(Locale.ROOT));
    }

    public static boolean reserved(String canonical) {
        return RESERVED.contains(canonical);
    }
}
