package com.videogameplatform.catalogue.adapter.provider.igdb;

import com.videogameplatform.catalogue.adapter.observability.CatalogueSynchronizationMetrics;
import com.videogameplatform.catalogue.adapter.provider.igdb.model.IgdbGamePayload;
import com.videogameplatform.catalogue.adapter.provider.igdb.model.IgdbReleaseDatePayload;
import com.videogameplatform.catalogue.application.synchronization.port.CatalogueProviderPort;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderCallStatistics;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderFailureCode;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderMappingFailure;
import com.videogameplatform.catalogue.application.synchronization.port.ProviderRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The IGDB anti-corruption layer.
 *
 * <p>Everything provider-shaped stops here: transport records, IGDB category names, IGDB
 * identifiers beyond the typed external reference, and the raw payload itself. What leaves is
 * product vocabulary and bounded failure reasons, so no other layer can accidentally depend on the
 * provider.
 */
public final class IgdbCatalogueProviderAdapter implements CatalogueProviderPort {

    /** Provenance recorded on everything this adapter produces. */
    public static final String PROVIDER_NAME = "IGDB";

    /** The provider's own ceiling for one response. */
    private static final int MAX_ROWS_PER_REQUEST = 500;

    private static final Pattern COVER_REFERENCE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String ATTRIBUTION_PREFIX = "https://www.igdb.com/games/";
    private static final TypeReference<List<IgdbGamePayload>> GAME_LIST = new TypeReference<>() {};
    private static final TypeReference<List<IgdbReleaseDatePayload>> RELEASE_LIST =
            new TypeReference<>() {};

    private final IgdbApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final IgdbApiSettings settings;
    private final CatalogueSynchronizationMetrics metrics;

    public IgdbCatalogueProviderAdapter(
            IgdbApiClient apiClient,
            ObjectMapper objectMapper,
            IgdbApiSettings settings,
            CatalogueSynchronizationMetrics metrics) {
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.metrics = metrics;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isConfigured() {
        return settings.isConfigured();
    }

    @Override
    public ReleasePage releaseGames(LocalDate from, LocalDate to, long afterGameId, int limit) {
        if (limit < 1 || limit > MAX_ROWS_PER_REQUEST) {
            throw new IllegalArgumentException("Invalid provider page size");
        }
        IgdbApiClient.ApiResponse response =
                query(
                        CatalogueSynchronizationMetrics.OPERATION_WINDOW,
                        IgdbQueries.RELEASE_DATES_ENDPOINT,
                        IgdbQueries.releaseGames(from, to, afterGameId, limit));
        List<IgdbReleaseDatePayload> rows =
                read(response.body(), RELEASE_LIST, response.statistics());
        long nextGameId = afterGameId;
        java.util.Set<String> games = new java.util.LinkedHashSet<>();
        java.util.Set<Long> releaseDates = new java.util.HashSet<>();
        if (rows.size() > limit) {
            throw new ProviderRequestException(
                    ProviderFailureCode.PROVIDER_RESPONSE_INVALID, response.statistics());
        }
        for (IgdbReleaseDatePayload row : rows) {
            if (row == null
                    || row.id() == null
                    || row.id() <= 0
                    || !releaseDates.add(row.id())
                    || row.game() == null
                    || row.game() <= afterGameId
                    || row.game() < nextGameId) {
                throw new ProviderRequestException(
                        ProviderFailureCode.PROVIDER_RESPONSE_INVALID, response.statistics());
            }
            nextGameId = row.game();
            games.add(Long.toString(row.game()));
        }
        return new ReleasePage(
                List.copyOf(games),
                rows.size(),
                nextGameId,
                rows.size() < limit,
                response.statistics());
    }

    @Override
    public ProviderWorkBatch fetchWorks(List<String> providerIds) {
        if (providerIds.size() > MAX_ROWS_PER_REQUEST) {
            throw new IllegalArgumentException("Too many provider Game IDs");
        }
        ProviderCallStatistics statistics = ProviderCallStatistics.none();
        List<ProviderWork> works = new ArrayList<>();
        // The application passes one game at a time so provider failures isolate that aggregate.
        // A sentinel row detects overflow; partial release responses are never published.
        for (String id : new java.util.LinkedHashSet<>(providerIds)) {
            IgdbApiClient.ApiResponse gameResponse =
                    query(
                            CatalogueSynchronizationMetrics.OPERATION_WORKS,
                            IgdbQueries.GAMES_ENDPOINT,
                            IgdbQueries.worksById(List.of(id)));
            statistics = statistics.plus(gameResponse.statistics());
            List<IgdbGamePayload> games = read(gameResponse.body(), GAME_LIST, statistics);
            if (games.isEmpty()) {
                continue;
            }
            if (games.size() != 1
                    || games.get(0) == null
                    || games.get(0).id() == null
                    || !id.equals(Long.toString(games.get(0).id()))) {
                throw new ProviderRequestException(
                        ProviderFailureCode.PROVIDER_RESPONSE_INVALID, statistics);
            }
            IgdbApiClient.ApiResponse releaseResponse;
            try {
                releaseResponse =
                        query(
                                CatalogueSynchronizationMetrics.OPERATION_RELEASE_DATES,
                                IgdbQueries.RELEASE_DATES_ENDPOINT,
                                IgdbQueries.releaseDatesForGames(
                                        List.of(id), settings.maxReleasesPerGame() + 1));
            } catch (ProviderRequestException failure) {
                throw new ProviderRequestException(
                        failure.code(), statistics.plus(failure.statistics()));
            }
            statistics = statistics.plus(releaseResponse.statistics());
            List<IgdbReleaseDatePayload> rows =
                    read(releaseResponse.body(), RELEASE_LIST, statistics);
            if (rows.size() > settings.maxReleasesPerGame()
                    || rows.stream()
                            .anyMatch(
                                    row ->
                                            row == null
                                                    || row.id() == null
                                                    || row.id() <= 0
                                                    || row.game() == null
                                                    || !id.equals(Long.toString(row.game())))
                    || rows.stream().map(IgdbReleaseDatePayload::id).distinct().count()
                            != rows.size()) {
                throw new ProviderRequestException(
                        ProviderFailureCode.PROVIDER_RESPONSE_INVALID, statistics);
            }
            works.add(toWork(games.get(0), rows));
        }
        return new ProviderWorkBatch(works, statistics);
    }

    private ProviderWork toWork(IgdbGamePayload game, List<IgdbReleaseDatePayload> releaseDates) {
        List<ProviderRelease> releases = new ArrayList<>();
        List<ProviderMappingFailure> failures = new ArrayList<>();
        String gameStatus = game.gameStatus() == null ? "" : game.gameStatus().value();
        for (IgdbReleaseDatePayload releaseDate : releaseDates) {
            IgdbReleaseMapper.Mapped mapped =
                    IgdbReleaseMapper.map(releaseDate, gameStatus, settings);
            mapped.release().ifPresent(releases::add);
            mapped.failure().ifPresent(failures::add);
        }

        Optional<ProviderCover> cover = cover(game);
        if (cover.isEmpty() && game.cover() != null) {
            failures.add(ProviderMappingFailure.COVER_REFERENCE_INVALID);
        }
        metrics.recordMappingFailures(failures);

        return new ProviderWork(
                Long.toString(game.id()),
                game.name(),
                IgdbWorkTypeMapper.map(game.gameType() == null ? null : game.gameType().value()),
                game.updatedAt() == null ? null : Instant.ofEpochSecond(game.updatedAt()),
                cover,
                releases,
                failures);
    }

    private IgdbApiClient.ApiResponse query(String operation, String endpoint, String query) {
        long startedAt = System.nanoTime();
        try {
            IgdbApiClient.ApiResponse response = apiClient.query(endpoint, query);
            metrics.recordProviderRequest(operation, "success", startedAt, response.statistics());
            return response;
        } catch (ProviderRequestException exception) {
            metrics.recordProviderRequest(
                    operation, exception.code().name(), startedAt, exception.statistics());
            throw exception;
        }
    }

    private <T> T read(String body, TypeReference<T> type, ProviderCallStatistics statistics) {
        try {
            T value = objectMapper.readValue(body, type);
            if (value == null) {
                throw new ProviderRequestException(
                        ProviderFailureCode.PROVIDER_RESPONSE_INVALID, statistics);
            }
            return value;
        } catch (JacksonException exception) {
            // The payload itself is never propagated, logged or measured.
            throw new ProviderRequestException(
                    ProviderFailureCode.PROVIDER_RESPONSE_INVALID, statistics);
        }
    }

    /** ADR-0001: only an opaque image identifier with its documented attribution page is usable. */
    private static Optional<ProviderCover> cover(IgdbGamePayload game) {
        if (game.cover() == null || game.cover().imageId() == null) {
            return Optional.empty();
        }
        String reference = game.cover().imageId();
        String sourceUrl = attributionUrl(game.url());
        if (!COVER_REFERENCE.matcher(reference).matches() || sourceUrl == null) {
            return Optional.empty();
        }
        return Optional.of(new ProviderCover(reference, sourceUrl));
    }

    private static String attributionUrl(String url) {
        return url != null && url.startsWith(ATTRIBUTION_PREFIX) ? url : null;
    }
}
