package com.videogameplatform.api.delivery.ratings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.videogameplatform.api.delivery.OpenApiResponseContract;
import com.videogameplatform.identity.domain.UserId;
import com.videogameplatform.test.PostgreSqlTestDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "management.server.port=0")
@AutoConfigureMockMvc
@Import(PersonalRatingApiIntegrationTest.FixedClock.class)
@Execution(ExecutionMode.SAME_THREAD)
class PersonalRatingApiIntegrationTest {
    private static final String DATABASE =
            PostgreSqlTestDatabase.isolatedDatabaseName("personal_rating_api");
    private static final String ISSUER = "https://identity.example/realms/test";
    private static final String ALICE = "alice-subject";
    private static final String BOB = "bob-subject";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OpenApiResponseContract GET_CONTRACT =
            OpenApiResponseContract.load("/me/ratings/{gameId}");
    private static final OpenApiResponseContract PUT_CONTRACT =
            OpenApiResponseContract.load("/me/ratings/{gameId}", "put");
    private static final OpenApiResponseContract DELETE_CONTRACT =
            OpenApiResponseContract.load("/me/ratings/{gameId}", "delete");

    @Autowired MockMvc mockMvc;
    private JdbcTemplate admin;
    private UUID game;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgreSqlTestDatabase.configureSpringDatabase(registry, DATABASE, true);
    }

    @BeforeEach
    void createEligibleGame() {
        admin =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                PostgreSqlTestDatabase.adminUrl(DATABASE),
                                PostgreSqlTestDatabase.adminUsername(),
                                PostgreSqlTestDatabase.adminPassword()));
        admin.update("DELETE FROM ratings.rating");
        game = UUID.randomUUID();
        admin.update("INSERT INTO catalogue.game(game_id, created_at) VALUES (?, now())", game);
        admin.update(
                """
                INSERT INTO catalogue.game_snapshot(
                    publication_id, game_id, canonical_title, slug, cover_reference,
                    cover_source, cover_usage_mode, cover_alternative_text, cover_usage_status)
                SELECT publication_id, ?, 'Rated game', ?, '/assets/covers/fallback.svg',
                    'Product', 'product_owned', 'Cover unavailable', 'approved'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                game,
                "rated-game-" + game);
        UUID release = UUID.randomUUID();
        admin.update(
                "INSERT INTO catalogue.game_release(release_id, game_id, created_at) VALUES (?, ?, now())",
                release,
                game);
        admin.update(
                """
                INSERT INTO catalogue.release_snapshot(
                    publication_id, release_id, game_id, platform_id, region_id,
                    date_precision, exact_date, release_status, source_kind, source_name,
                    source_entity_type, last_synchronized_at, last_verified_at,
                    verification_level, review_status)
                SELECT publication_id, ?, ?, '10000000-0000-4000-8000-000000000001',
                    '20000000-0000-4000-8000-000000000002', 'day', date '2026-08-13',
                    'released', 'official_source', 'Publisher', 'release', now(), now(),
                    'verified', 'not_required'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                release,
                game);
    }

    @Test
    void createAndReadReturnTheSameStrongOpaqueValidatorAndServerDerivedOwner() throws Exception {
        MvcResult create = create(ALICE, 9);
        MockHttpServletResponse created = create.getResponse();
        PUT_CONTRACT.assertJsonResponse(created, 201, "RatingWriteResult");
        assertThat(created.getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/v1/me/ratings/" + game);
        assertThat(created.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        String entityTag = created.getHeader(HttpHeaders.ETAG);
        assertThat(entityTag).matches("^\"[0-9a-f-]{36}\"$");
        JsonNode createdBody = JSON.readTree(created.getContentAsString());
        assertThat(createdBody.path("personalRating").path("entityTag").stringValue())
                .isEqualTo(entityTag);
        assertThat(createdBody.path("ratingStatistics").path("count").intValue()).isEqualTo(1);

        MvcResult read =
                mockMvc.perform(get(path()).with(login(ALICE)).accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(header().string(HttpHeaders.ETAG, entityTag))
                        .andReturn();
        GET_CONTRACT.assertJsonResponse(read.getResponse(), 200, "PersonalRating");
        assertThat(
                        JSON.readTree(read.getResponse().getContentAsString())
                                .path("entityTag")
                                .stringValue())
                .isEqualTo(entityTag);

        UUID expectedOwner = UUID.fromString(UserId.fromIssuerAndSubject(ISSUER, ALICE).value());
        assertThat(
                        admin.queryForObject(
                                "SELECT user_id FROM ratings.rating WHERE game_id = ?",
                                UUID.class,
                                game))
                .isEqualTo(expectedOwner);
    }

    @Test
    void createAndUpdateHonorExclusiveIntentPreconditionsAndPreserveTheWinner() throws Exception {
        String createdTag = create(ALICE, 6).getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":7}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .header(HttpHeaders.IF_MATCH, createdTag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":7}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":7}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RATING_ALREADY_EXISTS"));

        MvcResult update = update(ALICE, 8, createdTag);
        PUT_CONTRACT.assertJsonResponse(update.getResponse(), 200, "RatingWriteResult");
        String winningTag = update.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(winningTag).isNotEqualTo(createdTag);

        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_MATCH, createdTag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":2}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RATING_WRITE_CONFLICT"));
        assertThat(
                        admin.queryForObject(
                                "SELECT value FROM ratings.rating WHERE game_id = ?",
                                Integer.class,
                                game))
                .isEqualTo(8);
    }

    @Test
    void createAndUpdateReevaluateEligibilityButDeleteDoesNot() throws Exception {
        admin.update(
                "UPDATE catalogue.release_snapshot SET release_status = 'scheduled' WHERE game_id = ?",
                game);
        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":7}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RATING_NOT_ELIGIBLE"))
                .andExpect(jsonPath("$.eligibilityReason").value("RELEASE_NOT_OCCURRED"));

        admin.update(
                "UPDATE catalogue.release_snapshot SET release_status = 'released' WHERE game_id = ?",
                game);
        String tag = create(ALICE, 7).getResponse().getHeader(HttpHeaders.ETAG);
        admin.update(
                "UPDATE catalogue.release_snapshot SET release_status = 'scheduled' WHERE game_id = ?",
                game);

        MvcResult deleted =
                mockMvc.perform(
                                delete(path())
                                        .with(login(ALICE))
                                        .with(csrf().asHeader())
                                        .header(HttpHeaders.IF_MATCH, tag)
                                        .accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andReturn();
        DELETE_CONTRACT.assertJsonResponse(deleted.getResponse(), 200, "RatingDeleteResult");
        JsonNode body = JSON.readTree(deleted.getResponse().getContentAsString());
        assertThat(body.path("personalRating").isNull()).isTrue();
        assertThat(body.path("ratingStatistics").path("count").intValue()).isZero();
    }

    @Test
    void ownershipMakesAnotherUsersStateIndistinguishableFromAbsence() throws Exception {
        String ownerTag = create(ALICE, 10).getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(get(path()).with(login(BOB)).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RATING_NOT_FOUND"));
        mockMvc.perform(
                        put(path())
                                .with(login(BOB))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_MATCH, ownerTag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RATING_NOT_FOUND"));
        mockMvc.perform(
                        delete(path())
                                .with(login(BOB))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_MATCH, ownerTag))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RATING_NOT_FOUND"));
        assertThat(
                        admin.queryForObject(
                                "SELECT value FROM ratings.rating WHERE game_id = ?",
                                Integer.class,
                                game))
                .isEqualTo(10);
    }

    @Test
    void rejectsUnauthenticatedAndNonCsrfOrCrossOriginMutationsBeforeApplicationWork()
            throws Exception {
        mockMvc.perform(get(path()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(
                        put(path())
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":7}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":7}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":7}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_VALIDATION_FAILED"));
        assertThat(
                        admin.queryForObject(
                                "SELECT count(*) FROM ratings.rating WHERE game_id = ?",
                                Integer.class,
                                game))
                .isZero();
    }

    @Test
    void validatesBodyAndStrongDeletePreconditionsAgainstTheReviewedErrors() throws Exception {
        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":11}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("RATING_VALUE_INVALID"));
        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":7,\"userId\":\"attacker\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PROPERTY_UNKNOWN"));

        String tag = create(ALICE, 7).getResponse().getHeader(HttpHeaders.ETAG);
        String currentTag = update(ALICE, 8, tag).getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(delete(path()).with(login(ALICE)).with(csrf().asHeader()))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mockMvc.perform(
                        delete(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_MATCH, "W/" + tag))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"))
                .andExpect(jsonPath("$.violations[0].pointer").value("/headers/If-Match"));
        mockMvc.perform(
                        put(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_MATCH, tag.substring(1, tag.length() - 1))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"))
                .andExpect(jsonPath("$.violations[0].pointer").value("/headers/If-Match"));
        mockMvc.perform(
                        delete(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_MATCH, tag))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RATING_WRITE_CONFLICT"));
        mockMvc.perform(get(path()).with(login(ALICE)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, currentTag))
                .andExpect(jsonPath("$.value").value(8));
    }

    @Test
    void listingIsPrivateEmptyAndContainsOnlyThePrincipalsActiveRatings() throws Exception {
        mockMvc.perform(get("/api/v1/me/ratings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.page.totalItems").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
        String tag = create(BOB, 8).getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("q", "Rated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalItems").value(0));
        var response =
                mockMvc.perform(get("/api/v1/me/ratings").with(login(BOB)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.page.totalItems").value(1))
                        .andExpect(jsonPath("$.items[0].game.gameId").value(game.toString()))
                        .andExpect(jsonPath("$.items[0].game.canonicalTitle").value("Rated game"))
                        .andExpect(jsonPath("$.items[0].personalRating.value").value(8))
                        .andExpect(jsonPath("$.items[0].personalRating.entityTag").value(tag))
                        .andReturn();
        OpenApiResponseContract.load("/me/ratings")
                .assertJsonResponse(response.getResponse(), 200, "PersonalRatingPage");
        mockMvc.perform(
                        delete(path())
                                .with(login(BOB))
                                .with(csrf().asHeader())
                                .header("If-Match", tag))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/ratings").with(login(BOB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void listingSearchUsesCanonicalAndApprovedAliasWordPrefixesWithinOwnerScope() throws Exception {
        admin.update(
                "UPDATE catalogue.game_snapshot SET canonical_title='Élite Dangerous' WHERE game_id=?",
                game);
        admin.update(
                """
                INSERT INTO catalogue.game_alias(publication_id,game_id,alias,alias_kind,approval_status,source_kind,source_name)
                SELECT publication_id,game_id,'Space Odyssey','alternative','approved','product_curated','Test'
                FROM catalogue.game_snapshot WHERE game_id=?
                """,
                game);
        admin.update(
                """
                INSERT INTO catalogue.game_alias(publication_id,game_id,alias,alias_kind,approval_status,source_kind,source_name)
                SELECT publication_id,game_id,'Secret Alias','alternative','pending','product_curated','Test'
                FROM catalogue.game_snapshot WHERE game_id=?
                """,
                game);
        create(ALICE, 8);
        for (String query : List.of("elite", "ÉLITE dang", "spa ody")) {
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("q", query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalItems").value(1));
            mockMvc.perform(get("/api/v1/me/ratings").with(login(BOB)).param("q", query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalItems").value(0));
        }
        for (String query : List.of("lite", "secret", "elite odyssey", "unmatched")) {
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("q", query))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalItems").value(0));
        }
    }

    @Test
    void listingSortsAndCountsBeforePagingWithGameIdAsTheFinalTieBreaker() throws Exception {
        create(ALICE, 7);
        UUID second = addListedGame("Beta", 9);
        UUID third = addListedGame("Alpha", 7);
        List<String> ids =
                java.util.stream.Stream.of(game, second, third)
                        .map(UUID::toString)
                        .sorted()
                        .toList();
        // Every update timestamp is the fixed clock instant.
        for (int page = 1; page <= 3; page++) {
            mockMvc.perform(
                            get("/api/v1/me/ratings")
                                    .with(login(ALICE))
                                    .param("pageSize", "1")
                                    .param("page", Integer.toString(page)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page.totalItems").value(3))
                    .andExpect(jsonPath("$.page.totalPages").value(3))
                    .andExpect(jsonPath("$.items[0].game.gameId").value(ids.get(page - 1)));
        }
        mockMvc.perform(
                        get("/api/v1/me/ratings")
                                .with(login(ALICE))
                                .param("sort", "canonicalTitle")
                                .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].game.gameId").value(third.toString()));
        mockMvc.perform(
                        get("/api/v1/me/ratings")
                                .with(login(ALICE))
                                .param("sort", "ratingValue")
                                .param("direction", "desc")
                                .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].game.gameId").value(second.toString()));
        // Tie every earlier key, including title and value, across page boundaries.
        admin.update("UPDATE ratings.game_listing SET normalized_title='same'");
        admin.update("UPDATE ratings.rating SET value=7");
        for (String sort : List.of("updatedAt", "canonicalTitle", "ratingValue")) {
            for (String direction : List.of("asc", "desc")) {
                mockMvc.perform(
                                get("/api/v1/me/ratings")
                                        .with(login(ALICE))
                                        .param("sort", sort)
                                        .param("direction", direction)
                                        .param("pageSize", "1")
                                        .param("page", "2"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items[0].game.gameId").value(ids.get(1)));
            }
        }
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("page", "2147483647"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page.totalItems").value(3));
    }

    @Test
    void listingRejectsInvalidUnknownAndRepeatedParameters() throws Exception {
        for (String q : List.of("", " ", "...", "a".repeat(101))) {
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("q", q))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("SEARCH_QUERY_INVALID"));
        }
        for (String name : List.of("sort", "direction")) {
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param(name, ""))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("SORT_INVALID"));
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param(name, "invalid"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("SORT_INVALID"));
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param(name, "asc", "desc"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("SORT_INVALID"));
        }
        for (String name : List.of("page", "pageSize")) {
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param(name, ""))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PAGINATION_INVALID"));
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param(name, "0"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("PAGINATION_INVALID"));
            mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param(name, "x"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));
        }
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("pageSize", "101"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PAGINATION_INVALID"));
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("userId", "bob"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("REQUEST_PARAMETER_UNKNOWN"));
    }

    @Test
    void listingValidatorSupportsDirectMaintenanceAndRejectsAnOldVersion() throws Exception {
        create(ALICE, 7);
        var list = mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE))).andReturn();
        String tag =
                JSON.readTree(list.getResponse().getContentAsString())
                        .path("items")
                        .get(0)
                        .path("personalRating")
                        .path("entityTag")
                        .stringValue();
        String next = update(ALICE, 9, tag).getResponse().getHeader("ETag");
        mockMvc.perform(
                        delete(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header("If-Match", tag))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("RATING_WRITE_CONFLICT"));
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)))
                .andExpect(jsonPath("$.items[0].personalRating.entityTag").value(next))
                .andExpect(jsonPath("$.items[0].personalRating.value").value(9));
        mockMvc.perform(
                        delete(path())
                                .with(login(ALICE))
                                .with(csrf().asHeader())
                                .header("If-Match", next))
                .andExpect(status().isOk());
    }

    @Autowired org.springframework.context.ApplicationEventPublisher events;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    @Test
    void publicListingRefreshCommitsAndRollsBackWithCataloguePublication() throws Exception {
        create(ALICE, 7);
        var tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        // Use the application's connection so the event sees the exact uncommitted catalogue state.
        var jdbc = new JdbcTemplate(dataSource);
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "UPDATE catalogue.game_snapshot SET canonical_title='Renamed game' WHERE game_id=?",
                            game);
                    events.publishEvent(
                            new com.videogameplatform.catalogue.application.details
                                    .GameListingChanged(game.toString()));
                });
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("q", "renamed"))
                .andExpect(jsonPath("$.items[0].game.canonicalTitle").value("Renamed game"));
        tx.executeWithoutResult(
                status -> {
                    jdbc.update(
                            "UPDATE catalogue.game_snapshot SET canonical_title='Rolled back game' WHERE game_id=?",
                            game);
                    events.publishEvent(
                            new com.videogameplatform.catalogue.application.details
                                    .GameListingChanged(game.toString()));
                    status.setRollbackOnly();
                });
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("q", "renamed"))
                .andExpect(jsonPath("$.page.totalItems").value(1));
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)).param("q", "rolled"))
                .andExpect(jsonPath("$.page.totalItems").value(0));
    }

    @Autowired javax.sql.DataSource dataSource;

    private UUID addListedGame(String title, int value) throws Exception {
        UUID original = game;
        UUID added = UUID.randomUUID();
        admin.update("INSERT INTO catalogue.game(game_id,created_at) VALUES (?,now())", added);
        admin.update(
                """
                INSERT INTO catalogue.game_snapshot(publication_id,game_id,canonical_title,slug,
                    cover_reference,cover_source,cover_usage_mode,cover_alternative_text,cover_usage_status)
                SELECT publication_id,?,?,?,'/assets/covers/fallback.svg','Product','product_owned','Cover unavailable','approved'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                added,
                title,
                "listed-" + added);
        UUID release = UUID.randomUUID();
        admin.update(
                "INSERT INTO catalogue.game_release(release_id,game_id,created_at) VALUES (?,?,now())",
                release,
                added);
        admin.update(
                """
                INSERT INTO catalogue.release_snapshot(publication_id,release_id,game_id,platform_id,region_id,
                    date_precision,exact_date,release_status,source_kind,source_name,source_entity_type,
                    last_synchronized_at,last_verified_at,verification_level,review_status)
                SELECT publication_id,?,?,'10000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000002',
                    'day',date '2026-08-13','released','official_source','Publisher','release',now(),now(),'verified','not_required'
                FROM catalogue.catalogue_publication WHERE is_current
                """,
                release,
                added);
        game = added;
        try {
            create(ALICE, value);
        } finally {
            game = original;
        }
        return added;
    }

    @Autowired
    com.videogameplatform.ratings.adapter.persistence.JdbcGameListingProjection projection;

    @Test
    void missingPublicContextIsBackfilledThroughCatalogueBeforeServingLegacyRatings()
            throws Exception {
        admin.update(
                """
                INSERT INTO ratings.rating(user_id,game_id,value)
                VALUES (?,?,8)
                """,
                UUID.fromString(UserId.fromIssuerAndSubject(ISSUER, ALICE).value()),
                game);
        projection.run(new org.springframework.boot.DefaultApplicationArguments());
        mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].game.canonicalTitle").value("Rated game"));
    }

    @Test
    void collectionDatabaseFailuresReturnTheStablePrivateError() throws Exception {
        create(ALICE, 7);
        admin.execute("ALTER TABLE ratings.game_listing RENAME TO game_listing_unavailable");
        try {
            var result =
                    mockMvc.perform(get("/api/v1/me/ratings").with(login(ALICE)))
                            .andExpect(status().isInternalServerError())
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(jsonPath("$.code").value("PERSONAL_RATINGS_READ_FAILED"))
                            .andReturn();
            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("SELECT", "game_listing", "SQLException");
        } finally {
            admin.execute("ALTER TABLE ratings.game_listing_unavailable RENAME TO game_listing");
        }
    }

    private MvcResult create(String subject, int value) throws Exception {
        return mockMvc.perform(
                        put(path())
                                .with(login(subject))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_NONE_MATCH, "*")
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("{\"value\":" + value + "}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private MvcResult update(String subject, int value, String entityTag) throws Exception {
        return mockMvc.perform(
                        put(path())
                                .with(login(subject))
                                .with(csrf().asHeader())
                                .header(HttpHeaders.IF_MATCH, entityTag)
                                .contentType(MediaType.APPLICATION_JSON)
                                .accept(MediaType.APPLICATION_JSON)
                                .content("{\"value\":" + value + "}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String path() {
        return "/api/v1/me/ratings/" + game;
    }

    private static OidcLoginRequestPostProcessor login(String subject) {
        return oidcLogin()
                .idToken(
                        token ->
                                token.issuer(ISSUER)
                                        .subject(subject)
                                        .audience(List.of("test-bff")));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-13T10:00:00Z"), ZoneOffset.UTC);
        }
    }
}
