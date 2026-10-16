package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException;
import com.videogameplatform.catalogue.application.synchronization.port.SynchronizationWriteException.Reason;
import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * Classifies a datastore failure into the stable write reason vocabulary.
 *
 * <p>It relies only on Spring's translated exception types, which map PostgreSQL SQLStates
 * reliably (57014 timeout, class 22/23 integrity, class 08 and 53300 resource failure). Lock,
 * deadlock and serialization failures and anything unrecognized keep the generic reason rather
 * than a guess. One integrity violation is named: the release tuple, recognized by its unique
 * violation SQLState and constraint identifier, which PostgreSQL never translates. The original
 * exception stays the cause and is never logged by synchronization.
 */
final class SynchronizationWriteFailures {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String RELEASE_TUPLE_CONSTRAINT = "\"uq_release_snapshot_tuple\"";

    private SynchronizationWriteFailures() {}

    static SynchronizationWriteException classify(RuntimeException failure) {
        return new SynchronizationWriteException(reason(failure), failure);
    }

    static Reason reason(RuntimeException failure) {
        if (failure instanceof QueryTimeoutException
                || failure instanceof TransactionTimedOutException) {
            return Reason.PERSISTENCE_TIMEOUT;
        }
        if (failure instanceof DataIntegrityViolationException) {
            return violates(failure, RELEASE_TUPLE_CONSTRAINT)
                    ? Reason.RELEASE_TUPLE_CONFLICT
                    : Reason.PERSISTENCE_CONSTRAINT_VIOLATION;
        }
        if (failure instanceof DataAccessResourceFailureException
                || failure instanceof TransientDataAccessResourceException
                || failure instanceof CannotCreateTransactionException) {
            return Reason.PERSISTENCE_CONNECTION_FAILED;
        }
        return Reason.PERSISTENCE_WRITE_FAILED;
    }

    private static boolean violates(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && UNIQUE_VIOLATION.equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(constraint)) {
                return true;
            }
        }
        return false;
    }
}
