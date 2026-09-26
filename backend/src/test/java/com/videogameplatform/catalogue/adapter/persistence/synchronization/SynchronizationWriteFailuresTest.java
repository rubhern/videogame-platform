package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException.Reason;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

class SynchronizationWriteFailuresTest {

    private static final String PRIVATE_DETAIL =
            "INSERT INTO catalogue.game_snapshot VALUES ('secret-title') at jdbc://private-host";

    @Test
    void mapsReliablyTranslatedDatastoreFailuresToStableReasons() {
        Map<RuntimeException, Reason> expectations =
                Map.of(
                        new QueryTimeoutException(PRIVATE_DETAIL), Reason.PERSISTENCE_TIMEOUT,
                        new TransactionTimedOutException(PRIVATE_DETAIL),
                                Reason.PERSISTENCE_TIMEOUT,
                        new DuplicateKeyException(PRIVATE_DETAIL),
                                Reason.PERSISTENCE_CONSTRAINT_VIOLATION,
                        new DataIntegrityViolationException(PRIVATE_DETAIL),
                                Reason.PERSISTENCE_CONSTRAINT_VIOLATION,
                        new CannotGetJdbcConnectionException(
                                        PRIVATE_DETAIL, new SQLException(PRIVATE_DETAIL, "08001")),
                                Reason.PERSISTENCE_CONNECTION_FAILED,
                        new DataAccessResourceFailureException(PRIVATE_DETAIL),
                                Reason.PERSISTENCE_CONNECTION_FAILED,
                        new TransientDataAccessResourceException(PRIVATE_DETAIL),
                                Reason.PERSISTENCE_CONNECTION_FAILED,
                        new CannotCreateTransactionException(PRIVATE_DETAIL),
                                Reason.PERSISTENCE_CONNECTION_FAILED);

        expectations.forEach(
                (failure, reason) ->
                        assertThat(SynchronizationWriteFailures.reason(failure))
                                .as(failure.getClass().getSimpleName())
                                .isEqualTo(reason));
    }

    @Test
    void anythingElseKeepsTheGenericReasonInsteadOfAGuess() {
        List<RuntimeException> unclassified =
                List.of(
                        new CannotAcquireLockException(PRIVATE_DETAIL),
                        new DeadlockLoserDataAccessException(PRIVATE_DETAIL, null),
                        new BadSqlGrammarException(
                                "save", PRIVATE_DETAIL, new SQLException(PRIVATE_DETAIL, "42P01")),
                        new TransactionSystemException(PRIVATE_DETAIL),
                        new IllegalStateException(PRIVATE_DETAIL));

        assertThat(unclassified)
                .isNotEmpty()
                .allSatisfy(
                        failure ->
                                assertThat(SynchronizationWriteFailures.reason(failure))
                                        .isEqualTo(Reason.PERSISTENCE_WRITE_FAILED));
    }

    @Test
    void theClassifiedExceptionKeepsTheCauseButExposesOnlyAStablePhrase() {
        var cause = new DuplicateKeyException(PRIVATE_DETAIL);

        var classified = SynchronizationWriteFailures.classify(cause);

        assertThat(classified.reason()).isEqualTo(Reason.PERSISTENCE_CONSTRAINT_VIOLATION);
        assertThat(classified).hasCause(cause);
        assertThat(classified.getMessage())
                .doesNotContain("INSERT", "secret-title", "private-host", "catalogue.");
        assertThat(classified.game()).isEmpty();
    }
}
