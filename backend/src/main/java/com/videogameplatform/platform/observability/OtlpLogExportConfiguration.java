package com.videogameplatform.platform.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sends the application's own log events to the OTLP log exporter when log export is enabled.
 *
 * <p>Spring Boot creates the exporter; it does not attach Logback to it. The appender carries the
 * same events the console prints, plus the bounded correlation identifier, and adds no new
 * content. The console stays the primary stream, so export is best effort and never blocks a
 * request.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("management.logging.export.otlp.enabled")
class OtlpLogExportConfiguration {

    static final String APPENDER_NAME = "OTLP";

    @Bean
    OtlpLogAppender otlpLogAppender(OpenTelemetry openTelemetry) {
        return new OtlpLogAppender(openTelemetry);
    }

    /** Attaches one exporting appender to the root logger for the context's lifetime. */
    static final class OtlpLogAppender implements InitializingBean, DisposableBean {

        private final OpenTelemetry openTelemetry;

        OtlpLogAppender(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @Override
        public void afterPropertiesSet() {
            var root = root();
            if (root.getAppender(APPENDER_NAME) != null) {
                return;
            }
            var appender = new OpenTelemetryAppender();
            appender.setContext(root.getLoggerContext());
            appender.setName(APPENDER_NAME);
            appender.setCaptureMdcAttributes(CorrelationIdFilter.CORRELATION_ID_NAME);
            appender.setOpenTelemetry(openTelemetry);
            appender.start();
            root.addAppender(appender);
        }

        @Override
        public void destroy() {
            var root = root();
            var appender = root.getAppender(APPENDER_NAME);
            if (appender != null) {
                root.detachAppender(appender);
                appender.stop();
            }
        }

        private static Logger root() {
            return ((LoggerContext) LoggerFactory.getILoggerFactory())
                    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        }
    }
}
