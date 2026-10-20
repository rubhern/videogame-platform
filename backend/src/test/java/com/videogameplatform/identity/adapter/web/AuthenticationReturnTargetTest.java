package com.videogameplatform.identity.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthenticationReturnTargetTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "/",
                "/?view=upcoming&weeks=4&platformIds=pc",
                "/?month=2026-10",
                "/?view=recent&weeks=1",
                "/?week=2026-W42",
                "/?granularity=month",
                "/lanzamientos",
                "/lanzamientos?from=2026-08-01&to=2026-08-31&sort=hype&includeApproximateDates=true&platformIds=pc&regionIds=europe&page=2&pageSize=12",
                "/search?q=Final+Fantasy&page=2",
                "/mis-puntuaciones",
                "/mis-plataformas",
                "/administracion/catalogo?tab=cobertura&year=1997&month=12",
                "/games/game-1/a-game?platformId=pc&regionId=europe"
            })
    void preservesOnlyKnownProductPathsAndTheirNavigationState(String target) {
        assertThat(AuthenticationReturnTarget.safePath(target)).isEqualTo(target);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://attacker.example",
                "//attacker.example",
                "///attacker.example",
                "/\\attacker.example",
                "/%2f%2fattacker.example",
                "/games/../auth/login/keycloak",
                "/games/%2e%2e",
                "/games/game-1/%2f%2fevil",
                "/auth/start",
                "/login",
                "/login/oauth2/code/keycloak",
                "/api/v1/session",
                "/search?redirect=https://attacker.example",
                "/search?q=%0d%0aLocation:evil",
                "/search?q=%250d%250a",
                "/search?q=%",
                "/search#//evil",
                "/search?q=%5cevil",
                "/administracion/catalogo?from=1997-01-01",
                "/lanzamientos?redirect=https://attacker.example",
                "/lanzamientos?q=zelda",
                "/mis-plataformas?platformIds=pc",
                "/bienvenida",
                "/lanzamientos/../auth/start",
                "/administracion/catalogo/../../auth/start",
                "javascript:alert(1)"
            })
    void rejectsExternalEncodedServerAndUnexpectedDestinations(String target) {
        assertThat(AuthenticationReturnTarget.safePath(target)).isEqualTo("/");
    }

    @Test
    void encodesUnicodeSearchTextForTheHttpLocationHeader() {
        assertThat(AuthenticationReturnTarget.safePath("/search?q=Pokémon"))
                .isEqualTo("/search?q=Pok%C3%A9mon");
        assertThat(AuthenticationReturnTarget.safePath("/search?q=" + "界".repeat(300)))
                .isEqualTo("/");
    }

    @Test
    void rejectsMissingAndOversizedInput() {
        assertThat(AuthenticationReturnTarget.safePath(null)).isEqualTo("/");
        assertThat(AuthenticationReturnTarget.safePath("/search?q=" + "a".repeat(2048)))
                .isEqualTo("/");
    }
}
