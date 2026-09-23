package com.videogameplatform.ratings.adapter.persistence;

import com.videogameplatform.catalogue.application.cover.CatalogueCover;
import com.videogameplatform.catalogue.application.details.GameListingChanged;
import com.videogameplatform.catalogue.application.details.GetGameListingUseCase;
import com.videogameplatform.ratings.application.port.GameListingProjection;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/** Synchronous, rebuildable projection; no Catalogue table access or asynchronous consistency gap. */
public class JdbcGameListingProjection implements GameListingProjection, ApplicationRunner {
    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations transaction;
    private final GetGameListingUseCase games;

    public JdbcGameListingProjection(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations transaction,
            GetGameListingUseCase games) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.games = games;
    }

    @Override
    public void refresh(String gameId) {
        transaction.executeWithoutResult(
                status -> {
                    var id = UUID.fromString(gameId);
                    // Serializes projection rebuilds across instances, including catalogue
                    // publication.
                    jdbc.queryForList(
                            "SELECT pg_advisory_xact_lock(hashtextextended(:game, 32))",
                            Map.of("game", gameId));
                    var game = games.getListing(gameId);
                    var params =
                            new MapSqlParameterSource("game", id)
                                    .addValue("slug", game.slug())
                                    .addValue("title", game.canonicalTitle())
                                    .addValue("normalized", game.normalizedTitle())
                                    .addValue("kind", "unavailable")
                                    .addValue("reference", null)
                                    .addValue("alternative", null)
                                    .addValue("attribution", null)
                                    .addValue("source", null);
                    switch (game.cover()) {
                        case CatalogueCover.Provider cover ->
                                params.addValue("kind", "provider")
                                        .addValue("reference", cover.url().toString())
                                        .addValue("alternative", cover.alternativeText())
                                        .addValue("attribution", cover.attribution().label())
                                        .addValue(
                                                "source",
                                                cover.attribution().sourceUrl().toString());
                        case CatalogueCover.Product cover ->
                                params.addValue("kind", "product")
                                        .addValue("reference", cover.assetPath())
                                        .addValue("alternative", cover.alternativeText());
                        case CatalogueCover.Unavailable ignored -> {}
                    }
                    jdbc.update(
                            """
                    INSERT INTO ratings.game_listing(game_id, slug, canonical_title, normalized_title,
                        cover_kind, cover_reference, cover_alternative_text, cover_attribution, cover_source_url)
                    VALUES (:game, :slug, :title, :normalized, :kind, :reference, :alternative, :attribution, :source)
                    ON CONFLICT (game_id) DO UPDATE SET slug=EXCLUDED.slug,
                        canonical_title=EXCLUDED.canonical_title, normalized_title=EXCLUDED.normalized_title,
                        cover_kind=EXCLUDED.cover_kind, cover_reference=EXCLUDED.cover_reference,
                        cover_alternative_text=EXCLUDED.cover_alternative_text,
                        cover_attribution=EXCLUDED.cover_attribution, cover_source_url=EXCLUDED.cover_source_url
                    """,
                            params);
                    jdbc.update(
                            "DELETE FROM ratings.game_listing_alias WHERE game_id=:game", params);
                    for (String alias : game.normalizedAliases()) {
                        jdbc.update(
                                """
                        INSERT INTO ratings.game_listing_alias(game_id, normalized_alias) VALUES (:game, :alias)
                        ON CONFLICT DO NOTHING
                        """,
                                Map.of("game", id, "alias", alias));
                    }
                });
    }

    @EventListener
    public void on(GameListingChanged event) {
        refresh(event.gameId());
    }

    /** Startup rebuild uses keyset batches of existing rated games, never all users' ratings in Java. */
    @Override
    public void run(ApplicationArguments arguments) {
        UUID after = new UUID(0, 0);
        while (true) {
            var batch =
                    jdbc.query(
                            """
                            SELECT r.game_id FROM ratings.rating r WHERE r.game_id > :after
                              AND NOT EXISTS (SELECT 1 FROM ratings.game_listing g WHERE g.game_id=r.game_id)
                            GROUP BY r.game_id ORDER BY r.game_id LIMIT 100
                    """,
                            Map.of("after", after),
                            (rs, row) -> rs.getObject(1, UUID.class));
            if (batch.isEmpty()) return;
            for (UUID game : batch) refresh(game.toString());
            after = batch.getLast();
        }
    }
}
