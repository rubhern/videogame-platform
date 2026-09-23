package com.videogameplatform.catalogue.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.videogameplatform.catalogue.application.releases.port.ReleaseBrowseReadPort;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

class CataloguePersistenceConfigurationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            CataloguePersistenceConfiguration.class, TestDependencies.class)
                    .withPropertyValues("catalogue.jdbc.read-timeout=5s");

    @Test
    void configuresAnIsolatedBoundedJdbcClientAndReadTransaction() {
        contextRunner.run(
                context -> {
                    CataloguePersistenceConfiguration.CatalogueReadExecution execution =
                            context.getBean(
                                    CataloguePersistenceConfiguration.CatalogueReadExecution.class);
                    NamedParameterJdbcTemplate catalogueJdbc = execution.jdbcOperations();
                    JdbcTemplate sharedJdbc = context.getBean("jdbcTemplate", JdbcTemplate.class);
                    TransactionTemplate readTransaction = execution.readTransaction();

                    assertThat(catalogueJdbc.getJdbcOperations()).isNotSameAs(sharedJdbc);
                    assertThat(((JdbcTemplate) catalogueJdbc.getJdbcOperations()).getQueryTimeout())
                            .isEqualTo(5);
                    assertThat(sharedJdbc.getQueryTimeout()).isEqualTo(-1);
                    assertThat(readTransaction.isReadOnly()).isTrue();
                    assertThat(readTransaction.getIsolationLevel())
                            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
                    assertThat(readTransaction.getTimeout()).isEqualTo(5);
                    assertThat(context.getBeansOfType(NamedParameterJdbcTemplate.class)).isEmpty();
                    assertThat(context.getBeansOfType(TransactionTemplate.class)).isEmpty();
                    assertThat(context).hasSingleBean(ReleaseBrowseReadPort.class);
                });
    }

    @Test
    void rejectsReadTimeoutsThatCannotBeAppliedPrecisely() {
        assertThatThrownBy(() -> new CatalogueJdbcProperties(Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CatalogueJdbcProperties(Duration.ofSeconds(61)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:postgresql://localhost:1/not-used", "not-used", "not-used");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new JdbcTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
