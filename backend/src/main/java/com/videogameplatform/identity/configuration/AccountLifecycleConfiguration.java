package com.videogameplatform.identity.configuration;

import com.videogameplatform.identity.adapter.operator.AccountDeletionExecutorEndpoint;
import com.videogameplatform.identity.adapter.operator.DeletionExecutorCredential;
import com.videogameplatform.identity.adapter.persistence.JdbcAccountDeletionStore;
import com.videogameplatform.identity.adapter.web.SessionRevocation;
import com.videogameplatform.identity.application.AccountDeletionStore;
import com.videogameplatform.identity.application.DeletionExecutorAvailability;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

@Configuration(proxyBeanMethods = false)
class AccountLifecycleConfiguration {
    @Bean
    AccountDeletionStore accountDeletionStore(
            NamedParameterJdbcOperations jdbc,
            @Value("${identity.deletion-executor.completion-policy:independent}") String policy,
            @Value("${identity.oidc.issuer-uri}") String issuer,
            org.springframework.core.env.Environment environment) {
        return new JdbcAccountDeletionStore(
                jdbc,
                DeletionCompletionPolicy.validate(policy, issuer, environment.getActiveProfiles()));
    }

    @Bean
    DeletionExecutorCredential deletionExecutorCredential(
            @Value("${identity.deletion-executor.enabled:false}") boolean enabled,
            @Value("${identity.deletion-executor.credential-file:}") String file) {
        return new DeletionExecutorCredential(enabled, file);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            DeletionExecutorAvailability.class)
    DeletionExecutorAvailability accountErasure(
            AccountDeletionStore store, DeletionExecutorCredential credential, Clock clock) {
        return () -> credential.enabled() && store.executorReady(clock.instant());
    }

    @Bean
    @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            prefix = "identity.deletion-executor",
            name = "enabled",
            havingValue = "true")
    AccountDeletionExecutorEndpoint accountDeletionExecutorEndpoint(
            AccountDeletionStore store,
            SessionRevocation sessions,
            DeletionExecutorCredential credential,
            HttpServletRequest request,
            Clock clock,
            @Value("${identity.oidc.issuer-uri}") String issuer,
            io.micrometer.core.instrument.MeterRegistry metrics) {
        return new AccountDeletionExecutorEndpoint(
                store,
                sessions,
                credential,
                () -> request.getHeader("Authorization"),
                clock,
                issuer,
                metrics);
    }
}
