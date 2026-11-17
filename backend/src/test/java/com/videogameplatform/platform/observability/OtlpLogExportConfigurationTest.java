package com.videogameplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OtlpLogExportConfigurationTest {

    private final Recording exported = new Recording();
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(OtlpLogExportConfiguration.class)
                    .withBean(OpenTelemetry.class, this::openTelemetry);

    @Test
    void exportIsOffUnlessExplicitlyEnabled() {
        runner.run(
                context -> {
                    assertThat(context)
                            .doesNotHaveBean(OtlpLogExportConfiguration.OtlpLogAppender.class);
                    assertThat(rootAppenderAttached()).isFalse();
                });
    }

    @Test
    void enabledExportSendsApplicationEventsWithTheCorrelationIdAndDetachesOnClose() {
        runner.withPropertyValues("management.logging.export.otlp.enabled=true")
                .run(
                        context -> {
                            assertThat(rootAppenderAttached()).isTrue();
                            Logger logger = LoggerFactory.getLogger("export-probe");
                            MDC.put(CorrelationIdFilter.CORRELATION_ID_NAME, "corr-123");
                            try {
                                logger.warn("Feedback SMTP acceptance could not be confirmed");
                            } finally {
                                MDC.remove(CorrelationIdFilter.CORRELATION_ID_NAME);
                            }
                            LogRecordData record =
                                    exported.records.stream()
                                            .filter(
                                                    r ->
                                                            "export-probe"
                                                                    .equals(
                                                                            r.getInstrumentationScopeInfo()
                                                                                    .getName()))
                                            .findFirst()
                                            .orElseThrow();
                            assertThat(record.getBodyValue().asString())
                                    .isEqualTo("Feedback SMTP acceptance could not be confirmed");
                            assertThat(record.getSeverityText()).isEqualTo("WARN");
                            assertThat(
                                            record.getAttributes()
                                                    .get(
                                                            AttributeKey.stringKey(
                                                                    CorrelationIdFilter
                                                                            .CORRELATION_ID_NAME)))
                                    .isEqualTo("corr-123");
                        });
        assertThat(rootAppenderAttached()).isFalse();
    }

    private OpenTelemetry openTelemetry() {
        return OpenTelemetrySdk.builder()
                .setLoggerProvider(
                        SdkLoggerProvider.builder()
                                .addLogRecordProcessor(SimpleLogRecordProcessor.create(exported))
                                .build())
                .build();
    }

    private static boolean rootAppenderAttached() {
        return ((LoggerContext) LoggerFactory.getILoggerFactory())
                        .getLogger(Logger.ROOT_LOGGER_NAME)
                        .getAppender(OtlpLogExportConfiguration.APPENDER_NAME)
                != null;
    }

    private static final class Recording implements LogRecordExporter {
        private final List<LogRecordData> records = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<LogRecordData> logs) {
            records.addAll(logs);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
