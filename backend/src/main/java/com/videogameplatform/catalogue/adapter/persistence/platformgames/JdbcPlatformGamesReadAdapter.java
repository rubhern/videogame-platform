package com.videogameplatform.catalogue.adapter.persistence.platformgames;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueGenresReader;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.adapter.persistence.ReleaseDateRowMapper;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.details.GameDetailsResult;
import com.videogameplatform.catalogue.application.platformgames.PlatformGames.Taxonomy;
import com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort;
import com.videogameplatform.catalogue.domain.ReleaseStatus;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Coordinates the current publication, the taxonomy check, the facets, the count and one bounded
 * page in one repeatable-read snapshot, so the total, the facets and the page describe the same
 * catalogue. Request memory is {@code O(pageSize)} plus the bounded genre taxonomy.
 */
public final class JdbcPlatformGamesReadAdapter implements PlatformGamesReadPort {

    private static final int GENRE_LIMIT = 2;

    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcPlatformGamesReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<Result> findPlatformGames(Criteria criteria) {
        return CatalogueReadTransaction.execute(
                readTransaction, status -> findInTransaction(criteria));
    }

    private Optional<Result> findInTransaction(Criteria criteria) {
        Optional<CurrentPublicationReader.Publication> current =
                CurrentPublicationReader.read(jdbcOperations);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        CurrentPublicationReader.Publication publication = current.orElseThrow();

        // Only identifiers the taxonomy holds reach a uuid cast; an unknown one stops the read and
        // the application rejects the request by finding it missing.
        List<Taxonomy> platforms =
                jdbcOperations.query(
                        PlatformGamesSql.PLATFORMS,
                        Map.of("platformIds", criteria.platformIds()),
                        (resultSet, row) ->
                                new Taxonomy(
                                        resultSet.getString("platform_id"),
                                        resultSet.getString("display_name")));
        Map<String, String> genreIdsByCode = new LinkedHashMap<>();
        if (!criteria.genreIds().isEmpty()) {
            jdbcOperations.query(
                    PlatformGamesSql.GENRES,
                    Map.of("genreCodes", criteria.genreIds()),
                    (org.springframework.jdbc.core.RowCallbackHandler)
                            resultSet ->
                                    genreIdsByCode.put(
                                            resultSet.getString("code"),
                                            resultSet.getString("genre_id")));
        }
        List<String> knownGenreCodes = List.copyOf(genreIdsByCode.keySet());
        if (platforms.size() != criteria.platformIds().size()
                || knownGenreCodes.size() != criteria.genreIds().size()) {
            return Optional.of(
                    new Result(
                            publication.version(),
                            platforms,
                            knownGenreCodes,
                            List.of(),
                            null,
                            null,
                            List.of(),
                            0));
        }

        Map<String, Object> parameters =
                PlatformGamesSql.parameters(
                        publication.id(),
                        criteria,
                        platforms.stream().map(Taxonomy::id).toList(),
                        List.copyOf(genreIdsByCode.values()));

        // The statement reads every release of the scope, so its estimate crosses PostgreSQL's
        // JIT threshold on a large platform; compiling it measured 15-700 ms on a 100,000-game
        // platform, far more than it saves. SET LOCAL ends with this read-only transaction.
        jdbcOperations.getJdbcOperations().execute("SET LOCAL jit = off");
        Page read =
                jdbcOperations.query(
                        PlatformGamesSql.read(criteria),
                        parameters,
                        (ResultSetExtractor<Page>) JdbcPlatformGamesReadAdapter::page);
        List<Item> items = read.items();
        var genres =
                CatalogueGenresReader.read(
                        jdbcOperations,
                        publication.id(),
                        items.stream().map(Item::gameId).toList(),
                        GENRE_LIMIT);
        List<Item> page = new ArrayList<>(items.size());
        for (Item item : items) {
            page.add(
                    new Item(
                            item.gameId(),
                            item.slug(),
                            item.canonicalTitle(),
                            item.matchedAlias(),
                            item.cover(),
                            genres.getOrDefault(item.gameId(), List.of()),
                            item.platform(),
                            item.region(),
                            item.releaseDate(),
                            item.status()));
        }
        return Optional.of(
                new Result(
                        publication.version(),
                        platforms,
                        knownGenreCodes,
                        read.genres(),
                        read.earliestYear(),
                        read.latestYear(),
                        page,
                        read.totalItems()));
    }

    /**
     * Every row repeats the total and the facets; a row without a game only carries them when the
     * page is empty.
     */
    private static Page page(ResultSet resultSet) throws SQLException {
        if (!resultSet.next()) {
            throw new CatalogueDataInvalidException(
                    new IllegalStateException("The platform games read returned no summary"));
        }
        long totalItems = resultSet.getLong("total_items");
        Integer earliestYear = resultSet.getObject("earliest_year", Integer.class);
        Integer latestYear = resultSet.getObject("latest_year", Integer.class);
        List<GameDetailsResult.Term> genres = genres(resultSet);
        List<Item> items = new ArrayList<>();
        do {
            if (resultSet.getString("game_id") != null) {
                items.add(item(resultSet));
            }
        } while (resultSet.next());
        return new Page(totalItems, earliestYear, latestYear, genres, items);
    }

    private static List<GameDetailsResult.Term> genres(ResultSet resultSet) throws SQLException {
        List<String> codes = strings(resultSet, "genre_codes");
        List<String> names = strings(resultSet, "genre_names");
        if (codes.size() != names.size()) {
            throw new CatalogueDataInvalidException(
                    new IllegalStateException("Genre facet columns are misaligned"));
        }
        List<GameDetailsResult.Term> genres = new ArrayList<>(codes.size());
        for (int index = 0; index < codes.size(); index++) {
            genres.add(new GameDetailsResult.Term(codes.get(index), names.get(index)));
        }
        return genres;
    }

    private static List<String> strings(ResultSet resultSet, String column) throws SQLException {
        Array array = resultSet.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static Item item(ResultSet resultSet) throws SQLException {
        try {
            return new Item(
                    resultSet.getString("game_id"),
                    resultSet.getString("slug"),
                    resultSet.getString("canonical_title"),
                    resultSet.getString("matched_alias"),
                    CatalogueCoverReferenceRowMapper.map(resultSet),
                    List.of(),
                    new Taxonomy(
                            resultSet.getString("platform_id"),
                            resultSet.getString("platform_name")),
                    new Taxonomy(
                            resultSet.getString("region_id"), resultSet.getString("region_name")),
                    ReleaseDateRowMapper.map(resultSet),
                    ReleaseStatus.fromValue(resultSet.getString("release_status")));
        } catch (IllegalArgumentException
                | IllegalStateException
                | NullPointerException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }

    private record Page(
            long totalItems,
            Integer earliestYear,
            Integer latestYear,
            List<GameDetailsResult.Term> genres,
            List<Item> items) {}
}
