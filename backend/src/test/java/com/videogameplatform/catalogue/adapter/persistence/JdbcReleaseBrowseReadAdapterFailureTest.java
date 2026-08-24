package com.videogameplatform.catalogue.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.videogameplatform.catalogue.application.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.CatalogueDataInvalidException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.port.ReleaseBrowseReadPort;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcReleaseBrowseReadAdapterFailureTest {

    @Test
    void transientTechnicalReadFailurePreservesItsCause() {
        var rootCause = new SQLException("database timeout", "57014");
        var failure = new QueryTimeoutException("bounded release query timed out", rootCause);
        JdbcReleaseBrowseReadAdapter adapter = adapterThrowing(failure);

        assertThatThrownBy(() -> adapter.findPublishedReleases(criteria()))
                .isInstanceOf(CatalogueReadException.class)
                .hasCause(failure)
                .rootCause()
                .isSameAs(rootCause);
    }

    @Test
    void deterministicPersistenceFailureIsNotClassifiedAsRetryableCatalogueOutage() {
        var rootCause = new SQLException("invalid persisted release value", "22000");
        var failure = new BadSqlGrammarException("release browse", "select ...", rootCause);
        JdbcReleaseBrowseReadAdapter adapter = adapterThrowing(failure);

        assertThatThrownBy(() -> adapter.findPublishedReleases(criteria()))
                .isInstanceOf(CatalogueDataInvalidException.class)
                .isNotInstanceOf(CatalogueReadException.class)
                .hasCause(failure)
                .rootCause()
                .isSameAs(rootCause);
    }

    @Test
    void transactionConnectionFailurePreservesItsCauseAsCatalogueUnavailability() {
        SQLException rootCause = new SQLException("connection unavailable", "08006");
        var failure = new CannotCreateTransactionException("cannot open connection", rootCause);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenThrow(failure);
        var adapter =
                new JdbcReleaseBrowseReadAdapter(
                        mock(NamedParameterJdbcOperations.class),
                        new TransactionTemplate(transactionManager));

        assertThatThrownBy(() -> adapter.findPublishedReleases(criteria()))
                .isInstanceOf(CatalogueReadException.class)
                .hasCause(failure)
                .rootCause()
                .isSameAs(rootCause);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static JdbcReleaseBrowseReadAdapter adapterThrowing(RuntimeException failure) {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        when(jdbcOperations.query(anyString(), any(Map.class), any(ResultSetExtractor.class)))
                .thenThrow(failure);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new JdbcReleaseBrowseReadAdapter(
                jdbcOperations, new TransactionTemplate(transactionManager));
    }

    private static ReleaseBrowseReadPort.Criteria criteria() {
        return new ReleaseBrowseReadPort.Criteria(
                BrowseReleasesUseCase.View.RECENT,
                new ReleaseBrowseReadPort.Window(
                        LocalDate.of(2026, 2, 23), LocalDate.of(2026, 8, 23)),
                null,
                null,
                new ReleaseBrowseReadPort.Pagination(1, 20, 0),
                true);
    }
}
