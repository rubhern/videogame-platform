package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.CoveredMonths;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.State;
import com.videogameplatform.catalogue.application.administration.HistoricalSynchronization.Status;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationReport;
import com.videogameplatform.catalogue.application.synchronization.CatalogueSynchronizationRequest;
import com.videogameplatform.catalogue.application.synchronization.port.HistoricalBackfillStore;
import com.videogameplatform.catalogue.application.synchronization.port.ScheduledSynchronizationStore;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Constant-row checkpoint reads and writes. Session locks never hold a transaction open. */
public final class JdbcHistoricalBackfillStore
        implements HistoricalBackfillStore, ScheduledSynchronizationStore {
    // Fixed module-owned two-part advisory namespace. Never derive a key from user input.
    private static final int NAMESPACE = 246009;
    private static final int BACKFILL = 1;
    private static final int SCHEDULED = 2;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final String provider;

    public JdbcHistoricalBackfillStore(DataSource dataSource, String provider) {
        // Advisory ownership has a dedicated non-pooled session. Two bounded ownership
        // sessions must never exhaust the application's transaction pool while waiting.
        this.dataSource =
                dataSource instanceof com.zaxxer.hikari.HikariDataSource pool
                        ? new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                pool.getJdbcUrl(), pool.getUsername(), pool.getPassword())
                        : dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.provider = provider;
    }

    @Override
    public Optional<Lease> acquire() {
        return acquire(BACKFILL);
    }

    @Override
    public Optional<Lease> acquireScheduledTurn() {
        return acquire(SCHEDULED);
    }

    private Optional<Lease> acquire(int key) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
                statement.setInt(1, NAMESPACE);
                statement.setInt(2, key);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        connection.close();
                        return Optional.empty();
                    }
                }
            }
            final Connection owned = connection;
            return Optional.of(
                    new Lease() {
                        private boolean closed;

                        @Override
                        public void check() {
                            try {
                                if (closed || !owned.isValid(2))
                                    throw new IllegalStateException("Backfill ownership lost");
                            } catch (SQLException failure) {
                                throw new IllegalStateException("Backfill ownership lost", failure);
                            }
                        }

                        @Override
                        public void close() {
                            if (closed) return;
                            closed = true;
                            try (var statement =
                                    owned.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
                                statement.setInt(1, NAMESPACE);
                                statement.setInt(2, key);
                                statement.execute();
                            } catch (SQLException failure) {
                                // Evict a broken pooled connection; never return a session holding
                                // a lock.
                                try {
                                    owned.abort(Runnable::run);
                                } catch (SQLException ignored) {
                                    /* Already broken. */
                                }
                            } finally {
                                try {
                                    owned.close();
                                } catch (SQLException ignored) {
                                    /* Already broken. */
                                }
                            }
                        }
                    });
        } catch (SQLException failure) {
            if (connection != null)
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    /* Already broken. */
                }
            throw new IllegalStateException("Historical ownership unavailable", failure);
        }
    }

    private boolean held(int key) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
            SELECT EXISTS (SELECT 1 FROM pg_locks
              WHERE locktype='advisory' AND database=(SELECT oid FROM pg_database WHERE datname=current_database())
                AND classid=?::oid AND objid=?::oid AND objsubid=2 AND granted)
            """,
                        Boolean.class,
                        NAMESPACE,
                        key));
    }

    @Override
    public boolean scheduledTurnPending() {
        return held(SCHEDULED)
                || Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM catalogue.deferred_synchronization_policy)",
                                Boolean.class));
    }

    @Override
    public Status status(Instant now) {
        var rows =
                jdbc.query(
                        "SELECT * FROM catalogue.historical_backfill",
                        (r, n) -> {
                            State state =
                                    State.valueOf(r.getString("state").toUpperCase(Locale.ROOT));
                            LocalDate from = r.getObject("window_from", LocalDate.class),
                                    to = r.getObject("window_to", LocalDate.class);
                            var started = r.getTimestamp("started_at").toInstant();
                            var finished = r.getTimestamp("finished_at");
                            return new Status(
                                    r.getObject("backfill_id", UUID.class),
                                    r.getObject("execution_id", UUID.class),
                                    state,
                                    from,
                                    to,
                                    CoveredMonths.valueOf(
                                            r.getString("covered_months").toUpperCase(Locale.ROOT)),
                                    r.getObject("next_from", LocalDate.class),
                                    r.getObject("current_to", LocalDate.class),
                                    r.getObject("run_id", UUID.class),
                                    (int)
                                                    ChronoUnit.MONTHS.between(
                                                            from.withDayOfMonth(1),
                                                            to.withDayOfMonth(1))
                                            + 1,
                                    r.getInt("completed_months"),
                                    r.getInt("skipped_months"),
                                    r.getLong("processed_games"),
                                    r.getLong("processed_releases"),
                                    r.getLong("quality_failures"),
                                    r.getObject("current_games", Long.class),
                                    r.getObject("current_releases", Long.class),
                                    r.getObject("current_failures", Long.class),
                                    r.getObject("current_quality_failures", Long.class),
                                    r.getObject("current_provider_requests", Long.class),
                                    started,
                                    r.getTimestamp("activity_at").toInstant(),
                                    Math.max(
                                            0,
                                            ChronoUnit.SECONDS.between(
                                                    started,
                                                    finished == null ? now : finished.toInstant())),
                                    r.getString("last_reason"));
                        });
        if (rows.isEmpty()) return Status.notStarted();
        var s = rows.getFirst();
        if ((s.state() == State.RUNNING
                        || s.state() == State.WAITING
                        || s.state() == State.STOPPING)
                && !held(BACKFILL)) {
            // This is a read-time interpretation, not a startup action or permission to resume.
            return new Status(
                    s.id(),
                    s.executionId(),
                    State.INTERRUPTED,
                    s.from(),
                    s.to(),
                    s.coveredMonths(),
                    s.nextFrom(),
                    s.currentTo(),
                    s.runId(),
                    s.totalMonths(),
                    s.completedMonths(),
                    s.skippedMonths(),
                    s.processedGames(),
                    s.processedReleases(),
                    s.dataQualityFailedGames(),
                    s.currentGames(),
                    s.currentReleases(),
                    s.currentFailedGames(),
                    s.currentDataQualityFailedGames(),
                    s.currentProviderRequests(),
                    s.startedAt(),
                    s.lastActivityAt(),
                    Math.max(0, ChronoUnit.SECONDS.between(s.startedAt(), s.lastActivityAt())),
                    "HISTORICAL_WORKER_INTERRUPTED");
        }
        return s;
    }

    @Override
    public void create(UUID id, LocalDate from, LocalDate to, CoveredMonths intent, Instant now) {
        jdbc.update(
                """
            INSERT INTO catalogue.historical_backfill(singleton,backfill_id,execution_id,window_from,window_to,
              covered_months,state,next_from,started_at,activity_at)
            VALUES(true,?,?,?,?,?,'running',?,?,?)
            ON CONFLICT(singleton) DO UPDATE SET backfill_id=excluded.backfill_id,execution_id=excluded.execution_id,
              window_from=excluded.window_from,window_to=excluded.window_to,covered_months=excluded.covered_months,
              state='running',next_from=excluded.next_from,started_at=excluded.started_at,activity_at=excluded.activity_at,
              current_to=null,run_id=null,completed_months=0,skipped_months=0,processed_games=0,
              processed_releases=0,quality_failures=0,current_games=null,current_releases=null,current_failures=null,
              current_quality_failures=null,current_provider_requests=null,finished_at=null,last_reason=null
            WHERE historical_backfill.state='completed'
            """,
                id,
                UUID.randomUUID(),
                from,
                to,
                intent.name().toLowerCase(Locale.ROOT),
                from,
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now));
    }

    @Override
    public void resume(UUID id, Instant now) {
        jdbc.update(
                """
            UPDATE catalogue.historical_backfill SET execution_id=?,state='running',finished_at=null,last_reason=null,
              run_id=null,current_to=null,current_games=null,current_releases=null,current_failures=null,
              current_quality_failures=null,current_provider_requests=null,activity_at=? WHERE backfill_id=?
            """,
                UUID.randomUUID(),
                java.sql.Timestamp.from(now),
                id);
    }

    @Override
    public void stop(UUID id, Instant now) {
        jdbc.update(
                """
            UPDATE catalogue.historical_backfill SET state='stopping',last_reason='HISTORICAL_STOP_REQUESTED',activity_at=?
            WHERE backfill_id=? AND state<>'completed'
            """,
                java.sql.Timestamp.from(now),
                id);
        if (!held(BACKFILL))
            jdbc.update(
                    "UPDATE catalogue.historical_backfill SET state='stopped',finished_at=?,last_reason='HISTORICAL_STOPPED' WHERE backfill_id=? AND state='stopping'",
                    java.sql.Timestamp.from(now),
                    id);
    }

    @Override
    public void state(UUID id, UUID executionId, State state, String reason, Instant now) {
        jdbc.update(
                """
            UPDATE catalogue.historical_backfill SET state=?,last_reason=?,activity_at=?,
              finished_at=CASE WHEN ? IN ('completed','attention','stopped','interrupted') THEN ?::timestamptz ELSE null END
            WHERE backfill_id=? AND execution_id=? AND (state<>'stopping' OR ? IN ('stopped','interrupted','attention'))
              AND (state<>? OR last_reason IS DISTINCT FROM ?)
            """,
                state.name().toLowerCase(Locale.ROOT),
                reason,
                java.sql.Timestamp.from(now),
                state.name().toLowerCase(Locale.ROOT),
                java.sql.Timestamp.from(now),
                id,
                executionId,
                state.name().toLowerCase(Locale.ROOT),
                state.name().toLowerCase(Locale.ROOT),
                reason);
    }

    @Override
    public void attach(UUID id, UUID executionId, UUID runId, LocalDate to, Instant now) {
        jdbc.update(
                """
            UPDATE catalogue.historical_backfill SET state=CASE WHEN state='stopping' THEN state ELSE 'running' END,
              run_id=?,current_to=?,current_games=null,current_releases=null,current_failures=null,
              current_quality_failures=null,current_provider_requests=null,activity_at=?,
              last_reason=CASE WHEN state='stopping' THEN last_reason ELSE null END WHERE backfill_id=? AND execution_id=?
            """,
                runId,
                to,
                java.sql.Timestamp.from(now),
                id,
                executionId);
    }

    @Override
    public void advance(
            UUID id,
            UUID executionId,
            LocalDate next,
            boolean skipped,
            CatalogueSynchronizationReport report,
            Instant now) {
        var c = report == null ? null : report.counters();
        jdbc.update(
                """
            UPDATE catalogue.historical_backfill SET next_from=?,completed_months=completed_months+1,
              skipped_months=skipped_months+?,processed_games=processed_games+?,processed_releases=processed_releases+?,
              quality_failures=quality_failures+?,run_id=null,current_to=null,current_games=null,current_releases=null,
              current_failures=null,current_quality_failures=null,current_provider_requests=null,activity_at=?
            WHERE backfill_id=? AND execution_id=? AND next_from<?
            """,
                next,
                skipped ? 1 : 0,
                c == null ? 0 : games(c),
                c == null ? 0 : releases(c),
                c == null ? 0 : c.dataQualityFailedGames(),
                java.sql.Timestamp.from(now),
                id,
                executionId,
                next);
    }

    @Override
    public boolean covered(LocalDate from, LocalDate to) {
        // Disjoint coverage can be fragmented; PostgreSQL aggregates, Java never materializes it.
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
            SELECT COALESCE(sum(LEAST(covered_to,?::date)-GREATEST(covered_from,?::date)+1),0)
              = ?::date-?::date+1 FROM catalogue.synchronization_coverage
            WHERE provider=? AND daterange(covered_from,covered_to,'[]') && daterange(?::date,?::date,'[]')
            """,
                        Boolean.class,
                        to,
                        from,
                        to,
                        from,
                        provider,
                        from,
                        to));
    }

    @Override
    public void progress(UUID runId, CatalogueSynchronizationReport.Counters c, Instant now) {
        jdbc.update(
                """
            UPDATE catalogue.historical_backfill SET current_games=?,current_releases=?,current_failures=?,
              current_quality_failures=?,current_provider_requests=?,activity_at=? WHERE run_id=?
            """,
                games(c),
                releases(c),
                c.failedGames(),
                c.dataQualityFailedGames(),
                c.providerRequests(),
                java.sql.Timestamp.from(now),
                runId);
    }

    @Override
    public void defer(
            ScheduledSynchronizationStore.Policy policy,
            CatalogueSynchronizationRequest window,
            Instant dueAt) {
        jdbc.update(
                """
            INSERT INTO catalogue.deferred_synchronization_policy
                (policy,request_id,window_from,window_to,due_at,requested_at)
            VALUES(?,?,?,?,?,?) ON CONFLICT(policy) DO UPDATE SET
                request_id=CASE WHEN deferred_synchronization_policy.window_from=excluded.window_from
                        AND deferred_synchronization_policy.window_to=excluded.window_to
                    THEN deferred_synchronization_policy.request_id ELSE excluded.request_id END,
                window_from=excluded.window_from,window_to=excluded.window_to,requested_at=excluded.requested_at
            WHERE excluded.requested_at>=deferred_synchronization_policy.requested_at
            """,
                policy.name().toLowerCase(Locale.ROOT),
                UUID.randomUUID(),
                window.from(),
                window.to(),
                java.sql.Timestamp.from(dueAt),
                java.sql.Timestamp.from(dueAt));
    }

    @Override
    public List<ScheduledSynchronizationStore.Pending> pending() {
        return jdbc.query(
                """
            SELECT policy,request_id,window_from,window_to,due_at
            FROM catalogue.deferred_synchronization_policy ORDER BY due_at,policy LIMIT 2
            """,
                (r, n) ->
                        new ScheduledSynchronizationStore.Pending(
                                ScheduledSynchronizationStore.Policy.valueOf(
                                        r.getString("policy").toUpperCase(Locale.ROOT)),
                                r.getObject("request_id", UUID.class),
                                new CatalogueSynchronizationRequest(
                                        r.getObject("window_from", LocalDate.class),
                                        r.getObject("window_to", LocalDate.class)),
                                r.getTimestamp("due_at").toInstant()));
    }

    @Override
    public void complete(ScheduledSynchronizationStore.Pending pending) {
        jdbc.update(
                "DELETE FROM catalogue.deferred_synchronization_policy WHERE policy=? AND request_id=?",
                pending.policy().name().toLowerCase(Locale.ROOT),
                pending.requestId());
    }

    @Override
    public void cancel(ScheduledSynchronizationStore.Policy policy) {
        jdbc.update(
                "DELETE FROM catalogue.deferred_synchronization_policy WHERE policy=?",
                policy.name().toLowerCase(Locale.ROOT));
    }

    private static long games(CatalogueSynchronizationReport.Counters c) {
        return c.createdGames()
                + c.updatedGames()
                + c.unchangedGames()
                + c.deferredGames()
                + c.failedGames();
    }

    private static long releases(CatalogueSynchronizationReport.Counters c) {
        return c.createdReleases()
                + c.updatedReleases()
                + c.unchangedReleases()
                + c.deletedReleases();
    }
}
