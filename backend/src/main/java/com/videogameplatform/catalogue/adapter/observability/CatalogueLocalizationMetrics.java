package com.videogameplatform.catalogue.adapter.observability;

import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase.Batch;
import com.videogameplatform.catalogue.application.localization.port.LocalizationEvents;
import com.videogameplatform.catalogue.application.localization.port.LocalizationStore.Kind;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CatalogueLocalizationMetrics implements LocalizationEvents {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogueLocalizationMetrics.class);
    private final MeterRegistry registry;

    public CatalogueLocalizationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void content(Kind kind, Outcome outcome) {
        registry.counter(
                        "catalogue.localization.content",
                        "kind",
                        kind.name().toLowerCase(Locale.ROOT),
                        "outcome",
                        outcome.name().toLowerCase(Locale.ROOT))
                .increment();
        if (outcome == Outcome.FAILED) LOG.warn("Catalogue localization failed kind={}", kind);
    }

    @Override
    public void backfill(Batch batch) {
        registry.counter(
                        "catalogue.localization.backfill",
                        "outcome",
                        batch.report().failed() > 0
                                ? "retry"
                                : batch.completed() ? "completed" : "progress")
                .increment();
        LOG.info(
                "Catalogue localization batch inspected={} translated={} reused={} skipped={} failed={} completed={} afterKind={} afterId={}",
                batch.inspected(),
                batch.report().translated(),
                batch.report().reused(),
                batch.report().skipped(),
                batch.report().failed(),
                batch.completed(),
                batch.afterKind(),
                batch.afterId());
    }
}
