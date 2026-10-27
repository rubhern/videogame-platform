package com.videogameplatform.api.delivery.frontend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FrontendRouteControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FrontendRouteController()).build();
    }

    @Test
    void forwardsOnlyApprovedBrowserRoutesToThePackagedSpa() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/search"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // A shared search result must survive a full page load, query string included.
        mockMvc.perform(get("/search").param("q", "resident evil"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // The release explorer reloads with its whole range, order and filter state.
        mockMvc.perform(
                        get("/lanzamientos")
                                .param("from", "2026-08-01")
                                .param("to", "2026-08-31")
                                .param("sort", "hype"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/lanzamientos/destacados").param("week", "2026-W42"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // The release calendar reloads with its month, chosen day and filters.
        mockMvc.perform(
                        get("/lanzamientos/calendario")
                                .param("month", "2026-11")
                                .param("day", "2026-11-13"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // TOPómetro reloads with its chosen ranking source.
        mockMvc.perform(get("/topometro").param("source", "gameometro"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // Plataformas reloads with its open platform.
        mockMvc.perform(get("/plataformas").param("plataforma", "ps4-id"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // A platform's catalogue reloads with its featured key or platform identifier and filters.
        mockMvc.perform(
                        get("/plataformas/nintendo-switch-2/catalogo")
                                .param("releaseState", "upcoming"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/plataformas/2f1c0a6e-8d4b-4c3a-9e1f-5b6a7c8d9e0f/catalogo"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/mis-puntuaciones"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/mis-puntuaciones/estadisticas"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        // The first-run platform choice and its later management are React routes too.
        mockMvc.perform(get("/bienvenida"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/mis-plataformas"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/administracion/catalogo").param("year", "1997"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
        mockMvc.perform(get("/games/pragmata"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void doesNotCaptureServerOwnedRouteRoots() throws Exception {
        mockMvc.perform(get("/api")).andExpect(status().isNotFound());
        mockMvc.perform(get("/auth")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator")).andExpect(status().isNotFound());
        mockMvc.perform(get("/login/oauth2/code/keycloak")).andExpect(status().isNotFound());
        // Only a platform's catalogue lives under a platform; nothing else is captured there.
        mockMvc.perform(get("/plataformas/ps5/topometro")).andExpect(status().isNotFound());
        mockMvc.perform(get("/plataformas/PS_5/catalogo")).andExpect(status().isNotFound());
    }
}
