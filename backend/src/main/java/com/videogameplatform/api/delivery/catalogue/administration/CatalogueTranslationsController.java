package com.videogameplatform.api.delivery.catalogue.administration;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.generated.CatalogueTranslationsApi;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.TranslationCoverage;
import com.videogameplatform.api.generated.model.TranslationCoverageRow;
import com.videogameplatform.api.generated.model.TranslationExecution;
import com.videogameplatform.api.generated.model.TranslationExecutionCommand;
import com.videogameplatform.catalogue.application.administration.CatalogueTranslations;
import java.time.ZoneOffset;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CatalogueTranslationsController implements CatalogueTranslationsApi {
    private final CatalogueTranslations translations;

    CatalogueTranslationsController(CatalogueTranslations translations) {
        this.translations = translations;
    }

    @Override
    public ResponseEntity<TranslationCoverage> getTranslationCoverage() {
        var coverage = translations.coverage();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        new TranslationCoverage(
                                coverage.observedAt().atOffset(ZoneOffset.UTC),
                                coverage.rows().stream()
                                        .map(CatalogueTranslationsController::map)
                                        .toList()));
    }

    @Override
    public ResponseEntity<TranslationExecution> getTranslationExecution() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(map(translations.execution()));
    }

    @Override
    public ResponseEntity<TranslationExecution> controlTranslationExecution(
            String xCsrfToken, TranslationExecutionCommand command) {
        try {
            var action = CatalogueTranslations.Action.valueOf(command.getAction().name());
            var result = translations.control(action, command.getExecutionId());
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(map(result));
        } catch (CatalogueTranslations.Conflict conflict) {
            throw new ApiRequestException(
                    ProblemCode.TRANSLATION_EXECUTION_CONFLICT, "/executionId");
        }
    }

    private static TranslationCoverageRow map(CatalogueTranslations.CoverageRow row) {
        var mapped =
                new TranslationCoverageRow(
                        TranslationCoverageRow.KindEnum.fromValue(row.kind()),
                        TranslationCoverageRow.StatusEnum.fromValue(row.status()),
                        row.lastValidRetained(),
                        row.items());
        if (row.origin() != null)
            mapped.setOrigin(TranslationCoverageRow.OriginEnum.fromValue(row.origin()));
        return mapped;
    }

    private static TranslationExecution map(CatalogueTranslations.Execution execution) {
        var body =
                new TranslationExecution(
                        TranslationExecution.StateEnum.fromValue(
                                execution.state().name().toLowerCase(Locale.ROOT)));
        if (execution.id() == null) return body;
        body.setExecutionId(execution.id());
        if (!execution.afterKind().isEmpty()) {
            body.setCurrentKind(
                    TranslationExecution.CurrentKindEnum.fromValue(
                            execution.afterKind().toLowerCase(Locale.ROOT)));
        }
        body.setEstimatedTotal(execution.estimatedTotal());
        body.setProcessed(execution.processed());
        body.setSucceeded(execution.succeeded());
        body.setSkipped(execution.skipped());
        body.setFailed(execution.failed());
        body.setStartedAt(execution.startedAt().atOffset(ZoneOffset.UTC));
        body.setLastActivityAt(execution.lastActivityAt().atOffset(ZoneOffset.UTC));
        if (execution.reason() != null) {
            body.setReason(
                    TranslationExecution.ReasonEnum.fromValue(
                            execution.reason().name().toLowerCase(Locale.ROOT)));
        }
        return body;
    }
}
