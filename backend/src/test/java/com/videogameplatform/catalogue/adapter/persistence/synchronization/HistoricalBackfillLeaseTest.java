package com.videogameplatform.catalogue.adapter.persistence.synchronization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class HistoricalBackfillLeaseTest {
    @Test
    void reportsConnectionFailureBeforeAcquiringHistoricalOwnership() throws SQLException {
        var dataSource = mock(DataSource.class);
        var failure = new SQLException("Connection unavailable");
        when(dataSource.getConnection()).thenThrow(failure);
        var store = new JdbcHistoricalBackfillStore(dataSource, "IGDB");

        assertThatThrownBy(store::acquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Historical ownership unavailable")
                .hasCause(failure);
    }

    @Test
    void closesTheConnectionWhenScheduledOwnershipAcquisitionFails() throws SQLException {
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var failure = new SQLException("Lock unavailable");
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")).thenThrow(failure);
        var store = new JdbcHistoricalBackfillStore(dataSource, "IGDB");

        assertThatThrownBy(store::acquireScheduledTurn)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Historical ownership unavailable")
                .hasCause(failure);
        verify(connection).close();
    }
}
