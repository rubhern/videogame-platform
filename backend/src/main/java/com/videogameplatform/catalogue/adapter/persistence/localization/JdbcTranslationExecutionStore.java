package com.videogameplatform.catalogue.adapter.persistence.localization;

import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.Action;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.Conflict;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.Coverage;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.CoverageRow;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.Execution;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.Reason;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations.State;
import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase.Batch;
import com.videogameplatform.catalogue.application.localization.port.TranslationExecutionStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

public final class JdbcTranslationExecutionStore implements TranslationExecutionStore {
    // Exceeds one batch of three requests (60 s each), plus bounded JDBC work.
    private static final String LEASE = "clock_timestamp() + interval '6 minutes'";
    private static final String COLUMNS =
            """
        *, CASE WHEN lease_until <= clock_timestamp() THEN 'interrupted' ELSE state END AS effective_state
        """;
    private static final String RETURNING = " RETURNING " + COLUMNS;
    private static final Execution INITIAL =
            new Execution(State.NOT_STARTED, null, "", null, null, 0, 0, 0, 0, null, null, null);

    private final NamedParameterJdbcOperations jdbc;
    private final Clock clock;

    public JdbcTranslationExecutionStore(NamedParameterJdbcOperations jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Coverage coverage() {
        var observedAt = clock.instant();
        var rows =
                jdbc.query(
                        """
            SELECT kind, status, origin, last_valid_retained, items
            FROM catalogue.observability_localization_coverage
            ORDER BY kind, status, origin, last_valid_retained
            """,
                        (rs, row) ->
                                new CoverageRow(
                                        rs.getString("kind"),
                                        rs.getString("status"),
                                        rs.getString("origin"),
                                        rs.getBoolean("last_valid_retained"),
                                        rs.getLong("items")));
        return new Coverage(observedAt, rows);
    }

    @Override
    public Execution execution() {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM catalogue.translation_execution",
                        JdbcTranslationExecutionStore::read)
                .stream()
                .findFirst()
                .orElse(INITIAL);
    }

    @Override
    public Execution control(Action action, @Nullable UUID observedExecution) {
        var params =
                new MapSqlParameterSource()
                        .addValue("observed", observedExecution, java.sql.Types.OTHER)
                        .addValue("execution", UUID.randomUUID());
        String sql =
                switch (action) {
                    case START ->
                            observedExecution == null
                                    ? """
                INSERT INTO catalogue.translation_execution (execution_id,state,lease_until)
                VALUES (:execution,'running',%s) ON CONFLICT (singleton) DO NOTHING
                """
                                            .formatted(LEASE)
                                    : """
                UPDATE catalogue.translation_execution
                SET execution_id=:execution,state='running',lease_until=%s,
                    after_kind=DEFAULT,after_id=DEFAULT,estimated_total=NULL,
                    processed=0,succeeded=0,skipped=0,failed=0,reason=NULL,
                    started_at=clock_timestamp(),activity_at=clock_timestamp()
                WHERE execution_id=:observed AND state='completed'
                """
                                            .formatted(LEASE);
                    case STOP ->
                            """
                UPDATE catalogue.translation_execution SET state='stopping',activity_at=clock_timestamp()
                WHERE execution_id=:observed AND state IN ('running','stopping')
                  AND lease_until > clock_timestamp()
                """;
                    case RESUME ->
                            """
                UPDATE catalogue.translation_execution
                SET execution_id=:execution,state='running',lease_until=%s,reason=NULL,
                    after_kind=CASE WHEN reason = 'remaining_failures' THEN '' ELSE after_kind END,
                    after_id=CASE WHEN reason = 'remaining_failures'
                        THEN '00000000-0000-0000-0000-000000000000'::uuid ELSE after_id END,
                    estimated_total=CASE WHEN reason = 'remaining_failures' THEN NULL ELSE estimated_total END,
                    processed=CASE WHEN reason = 'remaining_failures' THEN 0 ELSE processed END,
                    succeeded=CASE WHEN reason = 'remaining_failures' THEN 0 ELSE succeeded END,
                    skipped=CASE WHEN reason = 'remaining_failures' THEN 0 ELSE skipped END,
                    failed=CASE WHEN reason = 'remaining_failures' THEN 0 ELSE failed END,
                    started_at=CASE WHEN reason = 'remaining_failures' THEN clock_timestamp() ELSE started_at END,
                    activity_at=clock_timestamp()
                WHERE execution_id=:observed AND
                    (state IN ('attention','stopped') OR lease_until <= clock_timestamp())
                """
                                    .formatted(LEASE);
                };
        return jdbc.query(sql + RETURNING, params, JdbcTranslationExecutionStore::read).stream()
                .findFirst()
                .orElseThrow(Conflict::new);
    }

    @Override
    public Execution next(UUID execution) {
        return jdbc
                .query(
                        """
                UPDATE catalogue.translation_execution
                SET state=CASE WHEN state='stopping' THEN 'stopped' ELSE 'running' END,
                    lease_until=CASE WHEN state='stopping' THEN NULL ELSE %s END,
                    activity_at=clock_timestamp(),
                    estimated_total=CASE WHEN state='running' AND estimated_total IS NULL THEN
                        (SELECT count(*) FROM catalogue.game_snapshot
                         WHERE summary_kind='sourced' AND summary_language='en'
                           AND summary_source_kind='external_provider' AND summary_source_name='IGDB')
                        + (SELECT count(*) FROM catalogue.genre WHERE source_label IS NOT NULL)
                        + (SELECT count(*) FROM catalogue.game_mode WHERE source_label IS NOT NULL)
                        ELSE estimated_total END
                WHERE execution_id=:execution AND state IN ('running','stopping')
                  AND lease_until > clock_timestamp()
                """
                                        .formatted(LEASE)
                                + RETURNING,
                        Map.of("execution", execution),
                        JdbcTranslationExecutionStore::read)
                .stream()
                .findFirst()
                .orElse(INITIAL);
    }

    @Override
    public boolean checkpoint(UUID execution, Batch batch, boolean pauseForFailures) {
        var report = batch.report();
        String sql =
                """
            UPDATE catalogue.translation_execution
            SET after_kind=:kind,after_id=:id,processed=processed+:processed,
                succeeded=succeeded+:succeeded,skipped=skipped+:skipped,failed=failed+:failed,
                activity_at=clock_timestamp(),
                state=CASE WHEN state='stopping' THEN 'stopped'
                           WHEN :batchFailed OR (:complete AND failed+:failed>0) THEN 'attention'
                           WHEN :complete THEN 'completed' ELSE 'running' END,
                reason=CASE WHEN state='stopping' THEN NULL WHEN :batchFailed THEN 'batch_failed'
                            WHEN :complete AND failed+:failed>0 THEN 'remaining_failures' END,
                lease_until=CASE WHEN state='stopping' OR :batchFailed OR :complete THEN NULL ELSE %s END
            WHERE execution_id=:execution AND state IN ('running','stopping')
              AND lease_until > clock_timestamp()
            """
                        .formatted(LEASE);
        return jdbc.query(
                        sql + " RETURNING state",
                        Map.of(
                                "execution",
                                execution,
                                "kind",
                                batch.afterKind(),
                                "id",
                                batch.afterId(),
                                "processed",
                                batch.inspected(),
                                "succeeded",
                                report.translated() + report.reused(),
                                "skipped",
                                report.skipped(),
                                "failed",
                                report.failed(),
                                "batchFailed",
                                pauseForFailures,
                                "complete",
                                batch.completed()),
                        (rs, row) -> rs.getString(1))
                .contains("running");
    }

    @Override
    public void workerFailed(UUID execution) {
        jdbc.update(
                """
            UPDATE catalogue.translation_execution
            SET state='attention',reason='worker_failed',lease_until=NULL,activity_at=clock_timestamp()
            WHERE execution_id=:execution AND state IN ('running','stopping')
              AND lease_until > clock_timestamp()
            """,
                Map.of("execution", execution));
    }

    private static Execution read(ResultSet rs, int row) throws SQLException {
        var reason = rs.getString("reason");
        return new Execution(
                State.valueOf(rs.getString("effective_state").toUpperCase(Locale.ROOT)),
                rs.getObject("execution_id", UUID.class),
                rs.getString("after_kind"),
                rs.getObject("after_id", UUID.class),
                rs.getObject("estimated_total", Long.class),
                rs.getLong("processed"),
                rs.getLong("succeeded"),
                rs.getLong("skipped"),
                rs.getLong("failed"),
                rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("activity_at").toInstant(),
                reason == null ? null : Reason.valueOf(reason.toUpperCase(Locale.ROOT)));
    }
}
