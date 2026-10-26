package com.videogameplatform.catalogue.adapter.persistence.rankings;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueCoverReferenceRowMapper;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueGenresReader;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.CompactReleaseSummarySql;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.rankings.GameRanking;
import com.videogameplatform.catalogue.application.rankings.RankGamesUseCase.Source;
import com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort;
import com.videogameplatform.catalogue.application.releases.ExternalPlayerScore;
import com.videogameplatform.catalogue.domain.ExternalScore;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Coordinates the current publication, the count and one bounded page of a ranking in one
 * repeatable-read snapshot, so the total and the positions describe the same scores.
 */
public final class JdbcGameRankingReadAdapter implements GameRankingReadPort {

    private static final int GENRE_LIMIT = 2;

    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcGameRankingReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<Result> findRankedGames(Criteria criteria) {
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
        Map<String, Object> parameters = GameRankingSql.parameters(publication.id(), criteria);

        Long totalItems =
                jdbcOperations.queryForObject(
                        GameRankingSql.count(criteria.source()), parameters, Long.class);
        List<Item> items =
                jdbcOperations.query(
                        GameRankingSql.page(criteria.source()),
                        parameters,
                        (resultSet, row) -> item(resultSet, criteria.source()));
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
                            item.cover(),
                            genres.getOrDefault(item.gameId(), List.of()),
                            item.releaseSummary(),
                            item.score()));
        }
        return Optional.of(
                new Result(publication.version(), page, totalItems == null ? 0 : totalItems));
    }

    private static Item item(ResultSet resultSet, Source source) throws SQLException {
        try {
            return new Item(
                    resultSet.getString("game_id"),
                    resultSet.getString("slug"),
                    resultSet.getString("canonical_title"),
                    CatalogueCoverReferenceRowMapper.map(resultSet),
                    List.of(),
                    releaseSummary(CompactReleaseSummarySql.read(resultSet)),
                    score(resultSet, source));
        } catch (IllegalArgumentException
                | IllegalStateException
                | NullPointerException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }

    private static GameRanking.Score score(ResultSet resultSet, Source source) throws SQLException {
        return switch (source) {
            case IGDB_PLAYERS -> {
                var stored =
                        new ExternalScore(
                                resultSet.getBigDecimal("rank_score"),
                                resultSet.getLong("rank_count"));
                var updated =
                        resultSet.getObject("provider_record_updated_at", OffsetDateTime.class);
                yield new GameRanking.Score.IgdbPlayers(
                        new ExternalPlayerScore(
                                ExternalPlayerScore.Source.IGDB,
                                stored.value(),
                                stored.count(),
                                URI.create(resultSet.getString("source_url")),
                                resultSet
                                        .getObject("observed_at", OffsetDateTime.class)
                                        .toInstant(),
                                Optional.ofNullable(updated).map(OffsetDateTime::toInstant)));
            }
            case GAMEOMETRO_COMMUNITY ->
                    new GameRanking.Score.Community(
                            resultSet.getBigDecimal("shown_mean"),
                            Math.toIntExact(resultSet.getLong("rank_count")));
        };
    }

    private static GameRanking.ReleaseSummary releaseSummary(CompactReleaseSummarySql.Row row) {
        List<GameRanking.Taxonomy> platforms = new ArrayList<>(row.platformIds().size());
        for (int index = 0; index < row.platformIds().size(); index++) {
            platforms.add(
                    new GameRanking.Taxonomy(
                            row.platformIds().get(index), row.platformNames().get(index)));
        }
        return new GameRanking.ReleaseSummary(
                platforms, row.totalPlatforms(), row.earliestKnownYear(), row.latestKnownYear());
    }
}
