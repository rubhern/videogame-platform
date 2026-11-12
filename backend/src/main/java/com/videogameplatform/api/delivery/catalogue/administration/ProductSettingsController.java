package com.videogameplatform.api.delivery.catalogue.administration;

import com.videogameplatform.api.delivery.ApiRequestException;
import com.videogameplatform.api.generated.ProductSettingsApi;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.ProductSettingChange;
import com.videogameplatform.api.generated.model.ProductSettingKey;
import com.videogameplatform.api.generated.model.ProductSettingValue;
import com.videogameplatform.api.generated.model.ProductSettingsCoverage;
import com.videogameplatform.api.generated.model.ProductSettingsOverview;
import com.videogameplatform.api.generated.model.ProductSettingsUpdate;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings.Overview;
import com.videogameplatform.catalogue.application.administration.ManageProductSettings.UpdateOutcome;
import com.videogameplatform.catalogue.application.settings.ProductSetting;
import com.videogameplatform.identity.application.CurrentUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The catalogue operator's product settings (UC-024, #450). The security boundary has already
 * required the operator authority, and CSRF proof for every command. The operator who changes a
 * setting is the server-derived product identity of the session, never a client claim, and the
 * change runs inside the session's revocation fence. Responses are operational state and are never
 * stored.
 */
@RestController
@RequestMapping("/api/v1")
public class ProductSettingsController implements ProductSettingsApi {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProductSettingsController.class);
    private static final String CHANGES_POINTER = "/changes";

    private final ManageProductSettings settings;
    private final CurrentUser currentUser;

    ProductSettingsController(ManageProductSettings settings, CurrentUser currentUser) {
        this.settings = settings;
        this.currentUser = currentUser;
    }

    @Override
    public ResponseEntity<ProductSettingsOverview> getProductSettings() {
        return ok(settings.overview());
    }

    @Override
    public ResponseEntity<ProductSettingsOverview> updateProductSettings(
            String xCsrfToken, ProductSettingsUpdate update) {
        List<ManageProductSettings.Change> changes =
                update.getChanges().stream()
                        .map(
                                change ->
                                        new ManageProductSettings.Change(
                                                change.getKey(),
                                                Optional.ofNullable(change.getValue())))
                        .toList();
        UpdateOutcome outcome =
                currentUser.changeAsCurrentUser(
                        operatorId -> settings.update(update.getRevision(), changes, operatorId));
        return switch (outcome) {
            case UpdateOutcome.Updated updated -> {
                // Registry keys are a closed vocabulary; the operator identity stays in the audit.
                LOGGER.atInfo()
                        .addKeyValue("settings.revision", updated.overview().revision())
                        .addKeyValue(
                                "settings.keys",
                                changes.stream()
                                        .map(ManageProductSettings.Change::key)
                                        .sorted()
                                        .toList()
                                        .toString())
                        .log(
                                "Product settings changed: revision={} changes={}",
                                updated.overview().revision(),
                                changes.size());
                yield ok(updated.overview());
            }
            case UpdateOutcome.Stale ignored ->
                    throw new ApiRequestException(
                            ProblemCode.PRODUCT_SETTINGS_REVISION_CONFLICT, "/revision");
            case UpdateOutcome.UnknownSetting unknown ->
                    throw invalid(CHANGES_POINTER + "/" + unknown.index() + "/key");
            case UpdateOutcome.ValueInvalid value ->
                    throw invalid(CHANGES_POINTER + "/" + value.index() + "/value");
            case UpdateOutcome.Inconsistent ignored -> throw invalid(CHANGES_POINTER);
            case UpdateOutcome.ChangesInvalid ignored -> throw invalid(CHANGES_POINTER);
        };
    }

    private static ApiRequestException invalid(String pointer) {
        return new ApiRequestException(ProblemCode.PRODUCT_SETTING_INVALID, pointer);
    }

    private static ResponseEntity<ProductSettingsOverview> ok(Overview overview) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(toResponse(overview));
    }

    private static ProductSettingsOverview toResponse(Overview overview) {
        ManageProductSettings.Coverage coverage = overview.coverage();
        return new ProductSettingsOverview(
                overview.revision(),
                overview.settings().stream().map(ProductSettingsController::value).toList(),
                new ProductSettingsCoverage(
                        coverage.evaluatedOn(),
                        coverage.schedulingEnabled(),
                        coverage.acquisitionForwardDays(),
                        coverage.acquisitionEnd(),
                        coverage.featuredLastMonth().toString(),
                        coverage.featuredBeyondAcquisition(),
                        coverage.upcomingHorizonEnd(),
                        coverage.upcomingBeyondAcquisition()),
                overview.recentChanges().stream()
                        .map(
                                change ->
                                        new ProductSettingChange(
                                                change.revision(),
                                                change.key(),
                                                plain(change.previous()),
                                                plain(change.next()),
                                                change.restoredDefault(),
                                                timestamp(change.changedAt())))
                        .toList());
    }

    private static ProductSettingValue value(ManageProductSettings.Entry entry) {
        ProductSetting setting = entry.setting();
        ProductSettingValue value =
                new ProductSettingValue(
                        ProductSettingKey.fromValue(setting.key()),
                        ProductSettingValue.GroupEnum.fromValue(lower(setting.group().name())),
                        ProductSettingValue.KindEnum.fromValue(lower(setting.kind().name())),
                        plain(entry.value()),
                        plain(entry.defaultValue()),
                        plain(setting.minimum()),
                        plain(setting.maximum()),
                        ProductSettingValue.OriginEnum.fromValue(lower(entry.origin().name())));
        entry.changedAt().map(ProductSettingsController::timestamp).ifPresent(value::setChangedAt);
        return value;
    }

    /** Never exponent notation on the wire: 100, not 1E+2. */
    private static BigDecimal plain(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
