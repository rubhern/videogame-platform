package com.videogameplatform.ratings.configuration;

import com.videogameplatform.catalogue.application.details.GetGameDetailsUseCase;
import com.videogameplatform.ratings.adapter.persistence.JdbcPersonalRatingStore;
import com.videogameplatform.ratings.adapter.persistence.JdbcRatingStatisticsReadAdapter;
import com.videogameplatform.ratings.application.GetRatingContextUseCase;
import com.videogameplatform.ratings.application.internal.PersonalRatingService;
import com.videogameplatform.ratings.application.internal.RatingContextService;
import com.videogameplatform.ratings.application.port.PersonalRatingStore;
import com.videogameplatform.ratings.application.port.RatingStatisticsReadPort;
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
    RatingStatisticsReadPort ratingStatisticsReadPort(RatingJdbcExecution execution) {
        return new JdbcRatingStatisticsReadAdapter(execution.jdbc());
    }

    @Bean
    PersonalRatingStore personalRatingStore(RatingJdbcExecution execution) {
        return new JdbcPersonalRatingStore(execution.jdbc(), execution.transaction());
    }

    @Bean
    GetRatingContextUseCase getRatingContextUseCase(RatingStatisticsReadPort statistics) {
        return new RatingContextService(statistics);
    }

    @Bean
    PersonalRatingService personalRatingService(
            PersonalRatingStore ratings, GetGameDetailsUseCase games, Clock clock) {
        return new PersonalRatingService(ratings, games, clock);
    }

    record RatingJdbcExecution(NamedParameterJdbcTemplate jdbc, TransactionTemplate transaction) {}
}
