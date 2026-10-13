package com.videogameplatform.catalogue.adapter.persistence;

import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Runs one bounded catalogue read and classifies its persistence failures for every reader. */
public final class CatalogueReadTransaction {

    private CatalogueReadTransaction() {}

    /**
     * A retryable outage, including the read deadline expiring during a statement or between
     * statements, becomes {@link CatalogueReadException}; any other data-access failure is
     * deterministic and becomes {@link CatalogueDataInvalidException}.
     */
    public static <T> T execute(TransactionOperations transaction, TransactionCallback<T> read) {
        try {
            return transaction.execute(read);
        } catch (CannotCreateTransactionException
                | DataAccessResourceFailureException
                | RecoverableDataAccessException
                | TransientDataAccessException
                | TransactionTimedOutException exception) {
            throw new CatalogueReadException(exception);
        } catch (DataAccessException exception) {
            throw new CatalogueDataInvalidException(exception);
        }
    }
}
