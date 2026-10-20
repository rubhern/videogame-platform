package com.videogameplatform.ratings.configuration;

import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.catalogue.application.releases.RecordCommunityScoresUseCase;
import com.videogameplatform.ratings.adapter.observability.RatingActivityMetrics;
import com.videogameplatform.ratings.adapter.persistence.CommunityScoreBackfill;
import com.videogameplatform.ratings.adapter.persistence.JdbcPersonalRatingStore;
import com.videogameplatform.ratings.adapter.persistence.JdbcRatingStatisticsReadAdapter;
import com.videogameplatform.ratings.application.GetRatingContextUseCase;
import com.videogameplatform.ratings.application.internal.PersonalRatingService;
import com.videogameplatform.ratings.application.internal.RatingContextService;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.ratings.application.port.RatingStatisticsReadPort;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RatingJdbcProperties.class)
class RatingsConfiguration {
    @Bean
    com.videogameplatform.ratings.adapter.persistence.JdbcGameListingProjection
            gameListingProjection(
                    RatingJdbcExecution execution,
                    com.videogameplatform.catalogue.application.details.GetGameListingUseCase
                            games) {
        return new com.videogameplatform.ratings.adapter.persistence.JdbcGameListingProjection(
                execution.jdbc(), execution.transaction(), games);
    }

    @Bean
    com.videogameplatform.ratings.application.ListPersonalRatingsUseCase listPersonalRatings(
            RatingJdbcExecution execution,
            PlatformTransactionManager manager,
            RatingJdbcProperties properties) {
        var read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setTimeout(properties.operationTimeoutSeconds());
        return new com.videogameplatform.ratings.application.internal.PersonalRatingsService(
                new com.videogameplatform.ratings.adapter.persistence
                        .JdbcPersonalRatingsReadAdapter(execution.jdbc(), read));
    }

    @Bean
    RatingJdbcExecution ratingJdbcExecution(
            DataSource dataSource,
            PlatformTransactionManager transactionManager,
            RatingJdbcProperties properties) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(properties.operationTimeoutSeconds());
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setTimeout(properties.operationTimeoutSeconds());
        return new RatingJdbcExecution(new NamedParameterJdbcTemplate(jdbc), transaction);
    }

    @Bean
    com.videogameplatform.ratings.application.GetRatingSummariesUseCase ratingSummaries(
            RatingJdbcExecution execution) {
        return new com.videogameplatform.ratings.application.internal.RatingSummariesService(
                new com.videogameplatform.ratings.adapter.persistence
                        .JdbcRatingSummariesReadAdapter(execution.jdbc()));
    }

    @Bean
    RatingStatisticsReadPort ratingStatisticsReadPort(RatingJdbcExecution execution) {
        return new JdbcRatingStatisticsReadAdapter(execution.jdbc());
    }

    @Bean
    PersonalRatingStore personalRatingStore(
            RatingJdbcExecution execution,
            MeterRegistry registry,
            RecordCommunityScoresUseCase communityScores) {
        return new JdbcPersonalRatingStore(
                execution.jdbc(),
                execution.transaction(),
                new RatingActivityMetrics(registry),
                communityScores);
    }

    @Bean
    CommunityScoreBackfill communityScoreBackfill(
            RatingJdbcExecution execution, RecordCommunityScoresUseCase communityScores) {
        return new CommunityScoreBackfill(
                execution.jdbc(), execution.transaction(), communityScores);
    }

    @Bean
    GetRatingContextUseCase getRatingContextUseCase(RatingStatisticsReadPort statistics) {
        return new RatingContextService(statistics);
    }

    @Bean
    PersonalRatingService personalRatingService(
            PersonalRatingStore ratings,
            GetGameDetailsUseCase games,
            Clock clock,
            com.videogameplatform.ratings.application.port.GameListingProjection listing) {
        return new PersonalRatingService(ratings, games, clock, listing);
    }

    record RatingJdbcExecution(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction) {}
}
