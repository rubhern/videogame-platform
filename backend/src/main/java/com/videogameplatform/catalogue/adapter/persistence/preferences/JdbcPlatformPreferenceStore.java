package com.videogameplatform.catalogue.adapter.persistence.preferences;

import com.videogameplatform.catalogue.application.preferences.PlatformOption;
import com.videogameplatform.catalogue.application.preferences.PlatformPreferences;
import com.videogameplatform.catalogue.application.preferences.ReplacePlatformPreferencesUseCase;
import com.videogameplatform.catalogue.application.preferences.port.PlatformPreferenceStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL store for each product user's preferred platforms. Every statement is scoped by the
 * caller's user. A replacement upserts the user's choice row first, which locks it, so concurrent
 * replacements of one user's set serialize and the last one wins whole; the foreign key to the
 * taxonomy rejects any platform the catalogue does not hold.
 */
public final class JdbcPlatformPreferenceStore implements PlatformPreferenceStore {

    private static final String CONFIGURED_SQL =
            """
            SELECT EXISTS (
                SELECT 1 FROM catalogue.platform_preference_choice
                WHERE user_id = CAST(:user AS uuid)
            )
            """;
    // One more than the write bound, so a set that somehow exceeds it fails rather than truncates.
    private static final String PLATFORMS_SQL =
            """
            SELECT p.platform_id::text AS platform_id, p.display_name
            FROM catalogue.platform_preference preference
            JOIN catalogue.platform p ON p.platform_id = preference.platform_id
            WHERE preference.user_id = CAST(:user AS uuid)
            ORDER BY lower(p.display_name), p.platform_id
            LIMIT :limit
            """;
    private static final String RECORD_CHOICE_SQL =
            """
            INSERT INTO catalogue.platform_preference_choice (user_id, updated_at)
            VALUES (CAST(:user AS uuid), :savedAt)
            ON CONFLICT (user_id) DO UPDATE SET updated_at = EXCLUDED.updated_at
            """;
    private static final String CLEAR_SQL =
            "DELETE FROM catalogue.platform_preference WHERE user_id = CAST(:user AS uuid)";
    // Text comparison keeps an identifier that is not a uuid from failing the cast: it simply
    // matches no platform, which the caller reports as an unknown platform.
    private static final String INSERT_SQL =
            """
            INSERT INTO catalogue.platform_preference (user_id, platform_id)
            SELECT CAST(:user AS uuid), p.platform_id
            FROM catalogue.platform p
            WHERE p.platform_id::text IN (:platformIds)
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations readTransaction;
    private final TransactionOperations writeTransaction;

    public JdbcPlatformPreferenceStore(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations readTransaction,
            TransactionOperations writeTransaction) {
        this.jdbc = jdbc;
        this.readTransaction = readTransaction;
        this.writeTransaction = writeTransaction;
    }

    @Override
    public PlatformPreferences find(String userId) {
        return readTransaction.execute(status -> read(userId));
    }

    @Override
    public Optional<PlatformPreferences> replace(
            String userId, Set<String> platformIds, Instant savedAt) {
        return writeTransaction.execute(
                status -> {
                    Map<String, Object> user = Map.of("user", userId);
                    jdbc.update(
                            RECORD_CHOICE_SQL,
                            Map.of(
                                    "user",
                                    userId,
                                    "savedAt",
                                    OffsetDateTime.ofInstant(savedAt, ZoneOffset.UTC)));
                    jdbc.update(CLEAR_SQL, user);
                    int inserted =
                            platformIds.isEmpty()
                                    ? 0
                                    : jdbc.update(
                                            INSERT_SQL,
                                            Map.of(
                                                    "user",
                                                    userId,
                                                    "platformIds",
                                                    List.copyOf(platformIds)));
                    if (inserted != platformIds.size()) {
                        status.setRollbackOnly();
                        return Optional.empty();
                    }
                    return Optional.of(read(userId));
                });
    }

    private PlatformPreferences read(String userId) {
        Map<String, Object> parameters =
                Map.of(
                        "user",
                        userId,
                        "limit",
                        ReplacePlatformPreferencesUseCase.MAX_PLATFORMS + 1);
        if (!Boolean.TRUE.equals(jdbc.queryForObject(CONFIGURED_SQL, parameters, Boolean.class))) {
            return PlatformPreferences.notConfigured();
        }
        List<PlatformOption> platforms =
                jdbc.query(
                        PLATFORMS_SQL,
                        parameters,
                        (resultSet, row) ->
                                new PlatformOption(
                                        resultSet.getString("platform_id"),
                                        resultSet.getString("display_name")));
        if (platforms.size() > ReplacePlatformPreferencesUseCase.MAX_PLATFORMS) {
            throw new IllegalStateException("Stored preferred platforms exceed their bound");
        }
        return new PlatformPreferences(true, platforms);
    }
}
