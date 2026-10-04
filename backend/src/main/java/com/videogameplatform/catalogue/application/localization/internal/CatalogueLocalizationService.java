package com.videogameplatform.catalogue.application.localization.internal;

import com.videogameplatform.catalogue.application.localization.LocalizeCatalogueUseCase;
import com.videogameplatform.catalogue.application.localization.port.CatalogueTranslationPort;
import com.videogameplatform.catalogue.application.localization.port.LocalizationEvents;
import com.videogameplatform.catalogue.application.localization.port.LocalizationEvents.Outcome;
import com.videogameplatform.catalogue.application.localization.port.LocalizationStore;
import com.videogameplatform.catalogue.application.localization.port.LocalizationStore.Target;
import com.videogameplatform.catalogue.application.localization.port.TranslationFailure;
import java.util.Set;
import java.util.UUID;

public final class CatalogueLocalizationService implements LocalizeCatalogueUseCase {
    private final LocalizationStore store;
    private final CatalogueTranslationPort translator;
    private final LocalizationEvents events;

    public CatalogueLocalizationService(
            LocalizationStore store,
            CatalogueTranslationPort translator,
            LocalizationEvents events) {
        this.store = store;
        this.translator = translator;
        this.events = events;
    }

    @Override
    public Report localizeGame(UUID gameId) {
        return localizeGame(gameId, () -> {});
    }

    @Override
    public Report localizeGame(UUID gameId, Runnable progress) {
        // Enrichment cannot turn an already committed valid Game into a failed synchronization.
        try {
            Report report = new Report(0, 0, 0, 0);
            for (Target target : store.gameTargets(gameId)) {
                report = report.plus(localize(target));
                progress.run();
            }
            return report;
        } catch (RuntimeException failure) {
            events.content(LocalizationStore.Kind.SUMMARY, Outcome.FAILED);
            return new Report(0, 0, 0, 1);
        }
    }

    @Override
    public Batch backfill(String kind, UUID id, int limit) {
        if ((!kind.isEmpty() && !Set.of("GENRE", "GAME_MODE", "SUMMARY").contains(kind))
                || limit < 1
                || limit > 100) throw new IllegalArgumentException("Invalid localization batch");
        var targets = store.targetsAfter(kind, id, limit + 1);
        Report report = new Report(0, 0, 0, 0);
        int inspected = Math.min(limit, targets.size());
        String lastKind = kind;
        UUID lastId = id;
        for (Target target : targets.subList(0, inspected)) {
            report = report.plus(localize(target));
            lastKind = target.kind().name();
            lastId = target.id();
        }
        // A failed/busy target retains the batch cursor: replay successes and retry failures.
        if (report.failed() > 0) {
            lastKind = kind;
            lastId = id;
        }
        Batch batch =
                new Batch(
                        lastKind,
                        lastId,
                        inspected,
                        targets.size() <= limit && report.failed() == 0,
                        report);
        events.backfill(batch);
        return batch;
    }

    private Report localize(Target target) {
        UUID claim = null;
        try {
            if (target.current()) return result(target, Outcome.SKIPPED);
            if (store.translated(target.fingerprint()).isPresent()) {
                return result(target, store.publish(target) ? Outcome.REUSED : Outcome.SKIPPED);
            }
            var acquired = store.claim(target);
            if (acquired.isEmpty()) {
                // Another process might have completed between lookup and reservation.
                if (store.translated(target.fingerprint()).isPresent() && store.publish(target))
                    return result(target, Outcome.REUSED);
                return result(target, Outcome.BUSY);
            }
            claim = acquired.orElseThrow();
            var translation = translator.translate(normalize(target.source()));
            int bound = target.kind() == LocalizationStore.Kind.SUMMARY ? 20000 : 200;
            if (translation == null
                    || translation.text() == null
                    || translation.text().isBlank()
                    || translation.text().length() > bound
                    || translation.text().indexOf('\u0000') >= 0
                    || translation.revision() == null
                    || translation.revision().isBlank()
                    || translation.revision().length() > 200)
                throw new IllegalArgumentException("Invalid translation output");
            if (!store.complete(target, claim, translation)) return result(target, Outcome.BUSY);
            if (!store.publish(target)) return result(target, Outcome.SKIPPED);
            if (target.kind() != LocalizationStore.Kind.SUMMARY)
                events.content(target.kind(), Outcome.FALLBACK);
            return result(target, Outcome.TRANSLATED);
        } catch (RuntimeException failure) {
            if (claim != null
                    && !(failure instanceof TranslationFailure ambiguous
                            && ambiguous.mayStillBeRunning())) {
                try {
                    store.release(target.fingerprint(), claim);
                } catch (RuntimeException ignored) {
                    /* lease expires */
                }
            }
            return result(target, Outcome.FAILED);
        }
    }

    private Report result(Target target, Outcome outcome) {
        events.content(target.kind(), outcome);
        return switch (outcome) {
            case TRANSLATED -> new Report(1, 0, 0, 0);
            case REUSED -> new Report(0, 1, 0, 0);
            case SKIPPED -> new Report(0, 0, 1, 0);
            case BUSY, FAILED -> new Report(0, 0, 0, 1);
            case FALLBACK -> throw new IllegalArgumentException("Fallback is an additional event");
        };
    }

    public static String normalize(String source) {
        return source.replaceAll("[ \t\r\n]+", " ").trim();
    }
}
