package com.videogameplatform.catalogue.adapter.persistence.search;

import com.videogameplatform.catalogue.adapter.persistence.CatalogueGenresReader;
import com.videogameplatform.catalogue.adapter.persistence.CatalogueReadTransaction;
import com.videogameplatform.catalogue.adapter.persistence.CurrentPublicationReader;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** Coordinates a coherent publication, count and bounded page in one read transaction. */
public final class JdbcGameSearchReadAdapter implements GameSearchReadPort {
    private final NamedParameterJdbcOperations jdbcOperations;
    private final TransactionOperations readTransaction;

    public JdbcGameSearchReadAdapter(
            NamedParameterJdbcOperations jdbcOperations, TransactionOperations readTransaction) {
        this.jdbcOperations = jdbcOperations;
        this.readTransaction = readTransaction;
    }

    @Override
    public Optional<Result> findMatchingGames(Criteria criteria) {
        return CatalogueReadTransaction.execute(
                readTransaction, status -> findInTransaction(criteria));
    }

    private Optional<Result> findInTransaction(Criteria criteria) {
        Optional<CurrentPublicationReader.Publication> currentPublication =
                CurrentPublicationReader.read(jdbcOperations);
        if (currentPublication.isEmpty()) {
            return Optional.empty();
        }
        CurrentPublicationReader.Publication publication = currentPublication.orElseThrow();

        Map<String, Object> parameters = GameSearchSql.parameters(publication.id(), criteria);
        Long totalItems =
                jdbcOperations.queryForObject(GameSearchSql.COUNT, parameters, Long.class);

        var items = jdbcOperations.query(GameSearchSql.PAGE, parameters, GameSearchPageMapper::map);
        var genres =
                CatalogueGenresReader.read(
                        jdbcOperations,
                        publication.id(),
                        items.stream().map(Item::gameId).toList(),
                        2);
        items =
                items.stream()
                        .map(
                                item ->
                                        new Item(
                                                item.gameId(),
                                                item.slug(),
                                                item.canonicalTitle(),
                                                item.matchedAlias(),
                                                item.cover(),
                                                item.releaseContext(),
                                                item.releaseSummary(),
                                                genres.getOrDefault(item.gameId(), List.of())))
                        .toList();
        return Optional.of(
                new Result(publication.version(), items, totalItems == null ? 0 : totalItems));
    }
}
