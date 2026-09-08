package com.videogameplatform.ratings.configuration;

import com.videogameplatform.ratings.adapter.persistence.JdbcRatingStatisticsReadAdapter;
import com.videogameplatform.ratings.application.GetRatingContextUseCase;
import com.videogameplatform.ratings.application.internal.RatingContextService;
import com.videogameplatform.ratings.application.port.RatingStatisticsReadPort;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@Configuration(proxyBeanMethods = false)
class RatingsConfiguration {
    @Bean
    RatingStatisticsReadPort ratingStatisticsReadPort(DataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        return new JdbcRatingStatisticsReadAdapter(new NamedParameterJdbcTemplate(jdbc));
    }

    @Bean
    GetRatingContextUseCase getRatingContextUseCase(RatingStatisticsReadPort statistics) {
        return new RatingContextService(statistics);
    }
}
