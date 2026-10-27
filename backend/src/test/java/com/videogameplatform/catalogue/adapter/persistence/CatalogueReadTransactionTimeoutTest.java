package com.videogameplatform.catalogue.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.videogameplatform.catalogue.adapter.persistence.details.JdbcGameDetailsReadAdapter;
import com.videogameplatform.catalogue.adapter.persistence.releases.JdbcFeaturedReleaseReadAdapter;
import com.videogameplatform.catalogue.adapter.persistence.releases.JdbcReleaseBrowseReadAdapter;
import com.videogameplatform.catalogue.adapter.persistence.search.JdbcGameSearchReadAdapter;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.releases.BrowseReleasesUseCase;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The read-transaction deadline can expire between statements, where Spring raises {@link
 * TransactionTimedOutException} before the next statement instead of a database cancellation. Both
 * mean the bounded catalogue read ran out of time, so both must be a retryable catalogue outage.
 */
class CatalogueReadTransactionTimeoutTest {

    private static final int DEADLINE_SECONDS = 1;

    @ParameterizedTest
    @MethodSource("catalogueReads")
    void expiredReadTransactionDeadlineIsACatalogueOutage(
            BiConsumer<NamedParameterJdbcTemplate, TransactionTemplate> read) throws Exception {
        Connection connection = mock(Connection.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        // Expire the real transaction resource before Spring applies the statement timeout.
        // A sleep depends on wall-clock progress and can fail when the host clock moves back.
        Answer<PreparedStatement> expiredPreparation =
                invocation -> {
                    var holder =
                            (ConnectionHolder)
                                    TransactionSynchronizationManager.getResource(dataSource);
                    assertThat(holder).isNotNull();
                    var deadline = holder.getDeadline();
                    assertThat(deadline).isNotNull();
                    deadline.setTime(0);
                    return mock(PreparedStatement.class);
                };
        when(connection.prepareStatement(anyString())).thenAnswer(expiredPreparation);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt()))
                .thenAnswer(expiredPreparation);

        assertThatThrownBy(() -> read.accept(jdbc(dataSource), readTransaction(dataSource)))
                .isInstanceOf(CatalogueReadException.class)
                .cause()
                .isInstanceOf(TransactionTimedOutException.class);
        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    static Stream<Arguments> catalogueReads() {
        return Stream.of(
                read(
                        "release browse",
                        (jdbc, transaction) ->
                                new JdbcReleaseBrowseReadAdapter(jdbc, transaction)
                                        .findPublishedReleases(releaseCriteria())),
                read(
                        "featured releases",
                        (jdbc, transaction) ->
                                new JdbcFeaturedReleaseReadAdapter(jdbc, transaction)
                                        .findFeaturedReleases(
                                                new FeaturedReleaseReadPort.Criteria(
                                                        LocalDate.of(2026, 10, 1),
                                                        LocalDate.of(2026, 10, 31),
                                                        6,
                                                        3))),
                read(
                        "catalogue search",
                        (jdbc, transaction) ->
                                new JdbcGameSearchReadAdapter(jdbc, transaction)
                                        .findMatchingGames(
                                                new GameSearchReadPort.Criteria(
                                                        "omega",
                                                        List.of("omega"),
                                                        new GameSearchReadPort.Pagination(1, 20, 0),
                                                        3,
                                                        3))),
                read(
                        "game details",
                        (jdbc, transaction) ->
                                new JdbcGameDetailsReadAdapter(jdbc, transaction)
                                        .find("11111111-1111-4111-8111-111111111111")));
    }

    private static Arguments read(
            String name, BiConsumer<NamedParameterJdbcTemplate, TransactionTemplate> read) {
        return Arguments.of(Named.of(name, read));
    }

    /** Mirrors the production catalogue read execution policy with a shorter deadline. */
    private static NamedParameterJdbcTemplate jdbc(DataSource dataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(DEADLINE_SECONDS);
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    private static TransactionTemplate readTransaction(DataSource dataSource) {
        TransactionTemplate transaction =
                new TransactionTemplate(new JdbcTransactionManager(dataSource));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(DEADLINE_SECONDS);
        return transaction;
    }

    private static ReleaseBrowseReadPort.Criteria releaseCriteria() {
        return new ReleaseBrowseReadPort.Criteria(
                new ReleaseBrowseReadPort.Window(
                        LocalDate.of(2026, 2, 23), LocalDate.of(2026, 8, 23)),
                LocalDate.of(2026, 8, 23),
                BrowseReleasesUseCase.Sort.RELEASE_DATE,
                null,
                null,
                new ReleaseBrowseReadPort.Pagination(1, 20, 0),
                true,
                25);
    }
}
