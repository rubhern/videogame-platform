package com.videogameplatform.api.delivery.catalogue.administration;

import static com.videogameplatform.test.RegisteredOidcSession.oidcSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.identity.adapter.web.ProductRoleAuthorities;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

/**
 * UC-021 behind the real BFF security chain and PostgreSQL: the prompt, the preview of a file as a
 * language model returns it, the operator's assignments, the range rules and deletion.
 */
@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Execution(ExecutionMode.SAME_THREAD)
class EditorialAttentionApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("editorial_attention_api");
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String ROOT = "/api/v1/admin/catalogue/editorial-attention";
    private static final String PUBLICATION = "00000000-0000-4000-8000-000000000458";
    private static final String PS5 = "10000000-0000-4000-8000-000000000001";
    private static final String WORLDWIDE = "20000000-0000-4000-8000-000000000001";
    private static final String PHANTOM = "30000000-0000-4000-8000-000000000001";
    private static final String GEARS = "30000000-0000-4000-8000-000000000002";
    private static final String KINGDOM = "30000000-0000-4000-8000-000000000003";
    private static final OpenApiResponseContract OVERVIEW =
            OpenApiResponseContract.load("/admin/catalogue/editorial-attention");
    private static final OpenApiResponseContract PREVIEW =
            OpenApiResponseContract.load("/admin/catalogue/editorial-attention/previews", "post");
    private static final OpenApiResponseContract LOAD =
            OpenApiResponseContract.load("/admin/catalogue/editorial-attention/lists", "post");
    private static final OpenApiResponseContract DETAIL =
            OpenApiResponseContract.load("/admin/catalogue/editorial-attention/lists/{listId}");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A file as a model returns it: fenced, with members the schema does not name. */
    private static final String FILE =
            """
            ```json
            {
              "schemaVersion": 1,
              "range": { "from": "2026-10-01", "to": "2026-10-31" },
              "generatedAt": "2026-11-12T10:00:00Z",
              "generator": { "prompt": "editorial-anticipation@1", "model": "test-model" },
              "entries": [
                { "rank": 1, "igdbSlug": "gears-of-war-e-day", "title": "Gears of War: E-Day",
                  "releaseDate": "2026-10-06", "platforms": ["Xbox"], "score": 100,
                  "rationale": "Headline launch.", "sources": ["https://a.test", "https://b.test"],
                  "dateConflict": "2026-10-06 per a.test" },
                { "rank": 2, "igdbSlug": "phantom-blade-0", "title": "Phantom Blade Zero",
                  "releaseDate": "2026-10-29", "score": 96, "sources": ["https://c.test"] },
                { "rank": 3, "igdbSlug": null, "title": "KINGDOM HEARTS Collection [I~III]",
                  "releaseDate": null, "score": 59, "sources": [] }
              ]
            }
            ```
            """;

    @Autowired MockMvc mockMvc;
    private JdbcTemplate admin;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, false);
    }

    @BeforeEach
    void catalogue() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.update("DELETE FROM catalogue.editorial_attention_list");
        if (admin.queryForObject(
                        "SELECT count(*) FROM catalogue.catalogue_publication", Integer.class)
                == 0) {
            admin.update(
                    "INSERT INTO catalogue.catalogue_publication (publication_id,"
                            + " catalogue_version, published_at, last_synchronized_at,"
                            + " source_kind, source_name, is_current) VALUES (CAST(? AS uuid),"
                            + " 'editorial-v1', now(), now(), 'external_provider', 'IGDB', true)",
                    PUBLICATION);
            game(PHANTOM, "Phantom Blade Zero", "phantom-blade-zero", "2026-10-29");
            game(GEARS, "Gears of War: E-Day", "gears-of-war-e-day", "2026-10-01");
            game(KINGDOM, "Kingdom Hearts III", "kingdom-hearts-iii", "2026-10-08");
        }
    }

    @Test
    void onlyTheOperatorReadsThePromptAndOnlyAProvenCommandPreviews() throws Exception {
        mockMvc.perform(get(ROOT).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        get(ROOT)
                                .accept(MediaType.APPLICATION_JSON)
                                .with(signedIn(List.of("catalogue-reader"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(preview(FILE).with(operator()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));

        MockHttpServletResponse overview =
                mockMvc.perform(get(ROOT).accept(MediaType.APPLICATION_JSON).with(operator()))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(jsonPath("$.prompt.version").value("editorial-anticipation@2"))
                        .andExpect(jsonPath("$.lists").isEmpty())
                        .andReturn()
                        .getResponse();
        OVERVIEW.assertJsonResponse(overview, 200, "EditorialAttentionOverview");
        String template =
                JSON.readTree(overview.getContentAsString())
                        .path("prompt")
                        .path("template")
                        .asString();
        assertThat(template).contains("{{FROM}}", "{{TO}}", "\"schemaVersion\": 1");
    }

    @Test
    void aPreviewMatchesBySlugThenTitleAndWarnsWithoutStoringAnything() throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(preview(FILE).with(operator()).with(csrf().asHeader()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.from").value("2026-10-01"))
                        .andExpect(jsonPath("$.model").value("test-model"))
                        .andExpect(jsonPath("$.entries[0].match.gameId").value(GEARS))
                        .andExpect(jsonPath("$.entries[0].match.matchedBy").value("igdb_slug"))
                        .andExpect(
                                jsonPath("$.entries[0].match.releaseInRange").value("2026-10-01"))
                        .andExpect(
                                jsonPath("$.entries[0].warnings[0]").value("release_date_differs"))
                        // A wrong slug falls back to the exact title.
                        .andExpect(jsonPath("$.entries[1].match.gameId").value(PHANTOM))
                        .andExpect(jsonPath("$.entries[1].match.matchedBy").value("title"))
                        .andExpect(jsonPath("$.entries[1].warnings").isEmpty())
                        .andExpect(jsonPath("$.entries[2].match").doesNotExist())
                        .andExpect(jsonPath("$.entries[2].warnings[0]").value("unmatched"))
                        .andReturn()
                        .getResponse();
        PREVIEW.assertJsonResponse(response, 200, "EditorialAttentionPreview");
        assertThat(lists()).isZero();
    }

    @Test
    void aLoadAppliesAssignmentsReplacesItsRangeAndRefusesAnOverlap() throws Exception {
        MockHttpServletResponse loaded =
                mockMvc.perform(
                                load(FILE, List.of(Map.of("rank", 3, "gameId", KINGDOM)))
                                        .with(operator())
                                        .with(csrf().asHeader()))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.list.entryCount").value(3))
                        .andExpect(jsonPath("$.entries[2].gameId").value(KINGDOM))
                        .andExpect(
                                jsonPath("$.entries[2].fileTitle")
                                        .value("KINGDOM HEARTS Collection [I~III]"))
                        .andReturn()
                        .getResponse();
        LOAD.assertJsonResponse(loaded, 201, "EditorialAttentionListDetail");
        String first =
                JSON.readTree(loaded.getContentAsString()).path("list").path("listId").asString();

        // The same range again replaces the whole list; an assignment without a Game drops it.
        String second =
                JSON.readTree(
                                mockMvc.perform(
                                                load(FILE, List.of(Map.of("rank", 1)))
                                                        .with(operator())
                                                        .with(csrf().asHeader()))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.list.entryCount").value(1))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("list")
                        .path("listId")
                        .asString();
        mockMvc.perform(get(ROOT + "/lists/" + first).with(operator()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EDITORIAL_ATTENTION_LIST_NOT_FOUND"));

        String overlapping =
                FILE.replace(
                        "\"from\": \"2026-10-01\", \"to\": \"2026-10-31\"",
                        "\"from\": \"2026-10-15\", \"to\": \"2026-11-14\"");
        mockMvc.perform(load(overlapping, List.of()).with(operator()).with(csrf().asHeader()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EDITORIAL_ATTENTION_RANGE_CONFLICT"));

        MockHttpServletResponse detail =
                mockMvc.perform(get(ROOT + "/lists/" + second).with(operator()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.entries[0].gameId").value(PHANTOM))
                        .andReturn()
                        .getResponse();
        DETAIL.assertJsonResponse(detail, 200, "EditorialAttentionListDetail");
        mockMvc.perform(delete(ROOT + "/lists/" + second).with(operator()).with(csrf().asHeader()))
                .andExpect(status().isNoContent());
        assertThat(lists()).isZero();
    }

    @Test
    void anInvalidFileOrAssignmentIsRejectedWithAPointerAndStoresNothing() throws Exception {
        expectInvalid(preview("not json"), "/content");
        expectInvalid(
                preview(FILE.replace("\"score\": 96", "\"score\": 960")),
                "/content/entries/1/score");
        expectInvalid(
                preview(FILE.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")),
                "/content/schemaVersion");
        expectInvalid(
                load(FILE, List.of(Map.of("rank", 9, "gameId", KINGDOM))), "/assignments/0/rank");
        expectInvalid(
                load(
                        FILE,
                        List.of(
                                Map.of(
                                        "rank",
                                        3,
                                        "gameId",
                                        "30000000-0000-4000-8000-000000000999"))),
                "/assignments/0/gameId");
        assertThat(lists()).isZero();
    }

    private void expectInvalid(MockHttpServletRequestBuilder request, String pointer)
            throws Exception {
        MockHttpServletResponse response =
                mockMvc.perform(request.with(operator()).with(csrf().asHeader()))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.code").value("EDITORIAL_ATTENTION_FILE_INVALID"))
                        .andExpect(jsonPath("$.violations[0].pointer").value(pointer))
                        .andReturn()
                        .getResponse();
        PREVIEW.assertJsonResponse(response, 422, "Problem");
    }

    private int lists() {
        return admin.queryForObject(
                "SELECT count(*) FROM catalogue.editorial_attention_list", Integer.class);
    }

    private void game(String id, String title, String igdbSlug, String day) {
        admin.update(
                "INSERT INTO catalogue.game (game_id, created_at) VALUES (CAST(? AS uuid), now())",
                id);
        admin.update(
                "INSERT INTO catalogue.game_snapshot (publication_id, game_id, canonical_title,"
                        + " slug, cover_reference, cover_source, cover_usage_mode,"
                        + " cover_alternative_text, cover_usage_status, cover_source_url) VALUES"
                        + " (CAST(? AS uuid), CAST(? AS uuid), ?, ?, 'co0001', 'IGDB',"
                        + " 'provider_cdn_reference', ?, 'approved', ?)",
                PUBLICATION,
                id,
                title,
                igdbSlug + "-local",
                "Portada de " + title,
                "https://www.igdb.com/games/" + igdbSlug);
        String release = id.replace("30000000", "40000000");
        admin.update(
                "INSERT INTO catalogue.game_release (release_id, game_id, created_at) VALUES"
                        + " (CAST(? AS uuid), CAST(? AS uuid), now())",
                release,
                id);
        admin.update(
                "INSERT INTO catalogue.release_snapshot (publication_id, release_id, game_id,"
                        + " platform_id, region_id, date_precision, exact_date, release_status,"
                        + " source_kind, source_name, source_entity_type, last_synchronized_at,"
                        + " verification_level, review_status, release_stage) VALUES (CAST(? AS"
                        + " uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS"
                        + " uuid), 'day', CAST(? AS date), 'announced', 'external_provider',"
                        + " 'IGDB', 'release_date', now(), 'provider_only', 'not_required',"
                        + " 'full_release')",
                PUBLICATION,
                release,
                id,
                PS5,
                WORLDWIDE,
                day);
    }

    private static MockHttpServletRequestBuilder preview(String content) throws Exception {
        return post(ROOT + "/previews")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("content", content)));
    }

    private static MockHttpServletRequestBuilder load(
            String content, List<Map<String, Object>> assignments) throws Exception {
        return post(ROOT + "/lists")
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                        JSON.writeValueAsString(
                                Map.of("content", content, "assignments", assignments)));
    }

    private static RequestPostProcessor operator() {
        return signedIn(List.of("catalogue-operator"));
    }

    private static RequestPostProcessor signedIn(List<String> roles) {
        return oidcSession(
                token ->
                        token.issuer(ISSUER)
                                .subject("operator-subject")
                                .audience(List.of("test-bff"))
                                .claim(ProductRoleAuthorities.ROLE_CLAIM, roles),
                new ProductRoleAuthorities());
    }
}
