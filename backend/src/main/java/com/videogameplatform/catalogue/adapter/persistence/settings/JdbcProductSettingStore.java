package com.videogameplatform.catalogue.adapter.persistence.settings;

import com.videogameplatform.catalogue.application.settings.port.ProductSettingStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.support.TransactionOperations;

/**
 * PostgreSQL store of the operator's product settings (UC-024). Every read is bounded by the closed
 * registry of settings or by an explicit limit. An update locks the single revision row, so
 * concurrent updates serialize and the one prepared from a superseded revision writes nothing.
 */
public final class JdbcProductSettingStore implements ProductSettingStore {

    private static final String REVISION_SQL =
            "SELECT revision FROM catalogue.product_setting_revision WHERE singleton";

    private static final String OVERRIDES_SQL =
            """
            SELECT setting_key, setting_value, changed_at
            FROM catalogue.product_setting
            ORDER BY setting_key
            """;

    private static final String RECENT_SQL =
            """
            SELECT revision, setting_key, previous_value, new_value, restored_default, changed_at
            FROM catalogue.product_setting_change
            ORDER BY revision DESC, setting_key
            LIMIT :limit
            """;

    private static final String LOCK_SQL =
            "SELECT revision FROM catalogue.product_setting_revision WHERE singleton FOR UPDATE";

    private static final String ADVANCE_SQL =
            """
            UPDATE catalogue.product_setting_revision
            SET revision = :revision, updated_at = :changedAt
            WHERE singleton
            """;

    private static final String UPSERT_SQL =
            """
            INSERT INTO catalogue.product_setting
                (setting_key, setting_value, revision, changed_at, changed_by)
            VALUES (:key, :value, :revision, :changedAt, CAST(:operator AS uuid))
            ON CONFLICT (setting_key) DO UPDATE
            SET setting_value = EXCLUDED.setting_value, revision = EXCLUDED.revision,
                changed_at = EXCLUDED.changed_at, changed_by = EXCLUDED.changed_by
            """;

    private static final String DELETE_SQL =
            "DELETE FROM catalogue.product_setting WHERE setting_key = :key";

    private static final String AUDIT_SQL =
            """
            INSERT INTO catalogue.product_setting_change
                (revision, setting_key, previous_value, new_value, restored_default,
                 changed_at, changed_by)
            VALUES (:revision, :key, :previous, :next, :restored, :changedAt,
                    CAST(:operator AS uuid))
            """;

    private final NamedParameterJdbcOperations jdbc;
    private final TransactionOperations readTransaction;
    private final TransactionOperations writeTransaction;

    public JdbcProductSettingStore(
            NamedParameterJdbcOperations jdbc,
            TransactionOperations readTransaction,
            TransactionOperations writeTransaction) {
        this.jdbc = jdbc;
        this.readTransaction = readTransaction;
        this.writeTransaction = writeTransaction;
    }

    /** The revision and the overrides in one snapshot, so they always belong together. */
    @Override
    public Stored read() {
        return readTransaction.execute(
                status -> {
                    Long revision = jdbc.queryForObject(REVISION_SQL, Map.of(), Long.class);
                    Map<String, StoredValue> overrides = new HashMap<>();
                    jdbc.query(
                            OVERRIDES_SQL,
                            resultSet -> {
                                overrides.put(
                                        resultSet.getString("setting_key"),
                                        new StoredValue(
                                                resultSet.getBigDecimal("setting_value"),
                                                instant(resultSet, "changed_at")));
                            });
                    return new Stored(revision == null ? 0 : revision, overrides);
                });
    }

    @Override
    public List<Change> recentChanges(int limit) {
        return jdbc.query(
                RECENT_SQL,
                Map.of("limit", limit),
                (resultSet, row) ->
                        new Change(
                                resultSet.getLong("revision"),
                                resultSet.getString("setting_key"),
                                resultSet.getBigDecimal("previous_value"),
                                resultSet.getBigDecimal("new_value"),
                                resultSet.getBoolean("restored_default"),
                                instant(resultSet, "changed_at")));
    }

    @Override
    public Optional<Long> apply(
            long expectedRevision, List<Write> writes, String operatorId, Instant changedAt) {
        UUID operator = UUID.fromString(operatorId);
        OffsetDateTime at = changedAt.atOffset(ZoneOffset.UTC);
        return writeTransaction.execute(
                status -> {
                    Long current = jdbc.queryForObject(LOCK_SQL, Map.of(), Long.class);
                    if (current == null || current != expectedRevision) {
                        return Optional.<Long>empty();
                    }
                    long revision = expectedRevision + 1;
                    jdbc.update(ADVANCE_SQL, Map.of("revision", revision, "changedAt", at));
                    for (Write write : writes) {
                        Map<String, Object> parameters = new LinkedHashMap<>();
                        parameters.put("key", write.key());
                        parameters.put("revision", revision);
                        parameters.put("changedAt", at);
                        parameters.put("operator", operator.toString());
                        if (write.value().isPresent()) {
                            parameters.put("value", write.value().get());
                            jdbc.update(UPSERT_SQL, parameters);
                        } else {
                            jdbc.update(DELETE_SQL, parameters);
                        }
                        parameters.put("previous", write.previous());
                        parameters.put("next", write.next());
                        parameters.put("restored", write.value().isEmpty());
                        jdbc.update(AUDIT_SQL, parameters);
                    }
                    return Optional.of(revision);
                });
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }
}
