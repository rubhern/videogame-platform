package com.videogameplatform.catalogue.configuration;

import com.videogameplatform.catalogue.adapter.persistence.releases.JdbcFeaturedReleaseReadAdapter;
import com.videogameplatform.catalogue.adapter.persistence.releases.JdbcReleaseBrowseReadAdapter;
import com.videogameplatform.catalogue.adapter.persistence.releases.JdbcReleaseCalendarReadAdapter;
import com.videogameplatform.catalogue.adapter.persistence.releases.JdbcUpcomingHighlightsReadAdapter;
import com.videogameplatform.catalogue.adapter.persistence.search.JdbcGameSearchReadAdapter;
import com.videogameplatform.catalogue.application.details.port.GameDetailsReadPort;
import com.videogameplatform.catalogue.application.releases.port.FeaturedReleaseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import com.videogameplatform.catalogue.application.releases.port.ReleaseCalendarReadPort;
import com.videogameplatform.catalogue.application.releases.port.UpcomingHighlightsReadPort;
import com.videogameplatform.catalogue.application.search.port.GameSearchReadPort;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Composition root for catalogue persistence adapters and their execution policy. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CatalogueJdbcProperties.class)
class CataloguePersistenceConfiguration {
    @Bean
    com.videogameplatform.catalogue.application.preferences.ExportPlatformPreferences
            exportPlatformPreferences(
                    org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations jdbc,
                    com.videogameplatform.catalogue.application.preferences.port
                                    .PlatformPreferenceStore
                            store) {
        return new com.videogameplatform.catalogue.adapter.persistence.preferences
                .JdbcPreferenceExport(jdbc, store);
    }

    @Bean
    com.videogameplatform.catalogue.application.details.port.GameListingReadPort
            gameListingReadPort(CatalogueReadExecution execution) {
        return new com.videogameplatform.catalogue.adapter.persistence.details
                .JdbcGameListingReadAdapter(execution.jdbcOperations());
    }

    @Bean
    CatalogueReadExecution catalogueReadExecution(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            CatalogueJdbcProperties properties) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(properties.readTimeoutSeconds());

        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(properties.readTimeoutSeconds());

        return new CatalogueReadExecution(
                new NamedParameterJdbcTemplate(jdbcTemplate), transaction);
    }

    @Bean
    com.videogameplatform.catalogue.application.releases.port.ExternalPlayerScoresReadPort
            externalPlayerScoresReadPort(CatalogueReadExecution execution) {
        return new com.videogameplatform.catalogue.adapter.persistence.releases
                .JdbcExternalPlayerScoresReadAdapter(execution.jdbcOperations());
    }

    /**
     * The projection writes inside the Ratings transaction that changes a score, so it takes the
     * caller's transaction rather than the read-only execution policy.
     */
    @Bean
    com.videogameplatform.catalogue.application.releases.port.CommunityScoreStore
            communityScoreStore(DataSource dataSource, CatalogueJdbcProperties properties) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.setQueryTimeout(properties.readTimeoutSeconds());
        return new com.videogameplatform.catalogue.adapter.persistence.releases
                .JdbcCommunityScoreStore(new NamedParameterJdbcTemplate(jdbcTemplate));
    }

    @Bean
    ReleaseBrowseReadPort releaseBrowseReadPort(CatalogueReadExecution execution) {
        return new JdbcReleaseBrowseReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    ReleaseCalendarReadPort releaseCalendarReadPort(CatalogueReadExecution execution) {
        return new JdbcReleaseCalendarReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    FeaturedReleaseReadPort featuredReleaseReadPort(CatalogueReadExecution execution) {
        return new JdbcFeaturedReleaseReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    UpcomingHighlightsReadPort upcomingHighlightsReadPort(CatalogueReadExecution execution) {
        return new JdbcUpcomingHighlightsReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    GameSearchReadPort gameSearchReadPort(CatalogueReadExecution execution) {
        return new JdbcGameSearchReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    com.videogameplatform.catalogue.application.rankings.port.GameRankingReadPort
            gameRankingReadPort(CatalogueReadExecution execution) {
        return new com.videogameplatform.catalogue.adapter.persistence.rankings
                .JdbcGameRankingReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    com.videogameplatform.catalogue.application.platformgames.port.PlatformGamesReadPort
            platformGamesReadPort(CatalogueReadExecution execution) {
        return new com.videogameplatform.catalogue.adapter.persistence.platformgames
                .JdbcPlatformGamesReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    GameDetailsReadPort gameDetailsReadPort(CatalogueReadExecution execution) {
        return new com.videogameplatform.catalogue.adapter.persistence.details
                .JdbcGameDetailsReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    @Bean
    com.videogameplatform.catalogue.application.preferences.port.SelectablePlatformsReadPort
            selectablePlatformsReadPort(CatalogueReadExecution execution) {
        return new com.videogameplatform.catalogue.adapter.persistence.preferences
                .JdbcSelectablePlatformsReadAdapter(
                execution.jdbcOperations(), execution.readTransaction());
    }

    /**
     * Personal preferred platforms read in the shared read-only policy and replace their whole set
     * in one bounded read-write transaction of their own.
     */
    @Bean
    com.videogameplatform.catalogue.application.preferences.port.PlatformPreferenceStore
            platformPreferenceStore(
                    CatalogueReadExecution execution,
                    PlatformTransactionManager transactionManager,
                    CatalogueJdbcProperties properties) {
        TransactionTemplate write = new TransactionTemplate(transactionManager);
        write.setTimeout(properties.readTimeoutSeconds());
        return new com.videogameplatform.catalogue.adapter.persistence.preferences
                .JdbcPlatformPreferenceStore(
                execution.jdbcOperations(), execution.readTransaction(), write);
    }

    /** Shared read-only, bounded-timeout execution policy for every catalogue query. */
    record CatalogueReadExecution(
            NamedParameterJdbcTemplate jdbcOperations, TransactionTemplate readTransaction) {}
}
