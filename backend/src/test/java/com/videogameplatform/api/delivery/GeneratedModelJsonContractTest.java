package com.videogameplatform.api.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.videogameplatform.api.generated.model.ActiveFilters;
import com.videogameplatform.api.generated.model.AvailableRatingStatistics;
import com.videogameplatform.api.generated.model.CompactReleaseSummary;
import com.videogameplatform.api.generated.model.FallbackCover;
import com.videogameplatform.api.generated.model.FallbackFeaturedImage;
import com.videogameplatform.api.generated.model.FeaturedReleaseItem;
import com.videogameplatform.api.generated.model.FeaturedSelection;
import com.videogameplatform.api.generated.model.GameSummary;
import com.videogameplatform.api.generated.model.Problem;
import com.videogameplatform.api.generated.model.RatingDeleteResult;
import com.videogameplatform.api.generated.model.Release;
import com.videogameplatform.api.generated.model.UnknownReleaseDate;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GeneratedModelJsonContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void absentOptionalNonNullableValuesAreOmitted() {
        assertThat(json(new Problem().violations(null)).has("eligibilityReason")).isFalse();
        assertThat(json(new Problem().violations(null)).has("violations")).isFalse();
        JsonNode release = json(new Release());
        assertThat(release.has("providerUpdatedAt")).isFalse();
        assertThat(release.has("lastVerifiedAt")).isFalse();
        assertThat(json(new GameSummary()).has("matchedAlias")).isFalse();
        assertThat(json(new FeaturedReleaseItem()).has("logo")).isFalse();
        JsonNode selection = json(new FeaturedSelection());
        assertThat(selection.has("popularityFreshness")).isFalse();
        assertThat(selection.has("popularityObservedAt")).isFalse();
        JsonNode summary = json(new CompactReleaseSummary());
        assertThat(summary.has("earliestKnownYear")).isFalse();
        assertThat(summary.has("latestKnownYear")).isFalse();
    }

    @Test
    void requiredNullValuesAndEmptyCollectionsRemainPresent() {
        assertNullProperty(json(new UnknownReleaseDate("unknown", null)), "value");
        assertNullProperty(
                json(new FallbackCover("fallback", "/cover.svg", "Cover", null)), "attribution");
        assertNullProperty(
                json(new AvailableRatingStatistics().status("available").count(0)), "mean");
        assertNullProperty(json(new FallbackFeaturedImage()), "attribution");
        assertNullProperty(json(new RatingDeleteResult()), "personalRating");
        JsonNode filters = json(new ActiveFilters());
        assertThat(filters.has("platformIds")).isTrue();
        assertThat(filters.get("platformIds").isArray()).isTrue();
        assertThat(filters.get("platformIds").isEmpty()).isTrue();
        assertThat(filters.has("regionIds")).isTrue();
        assertThat(filters.get("regionIds").isArray()).isTrue();
        assertThat(filters.get("regionIds").isEmpty()).isTrue();
    }

    @Test
    void explicitNullPreservesOptionalCollectionDefaultDuringDeserialization() {
        Problem absent = mapper.readValue("{}", Problem.class);
        Problem explicitNull = mapper.readValue("{\"violations\":null}", Problem.class);
        assertThat(absent.getViolations()).isNotNull().isEmpty();
        assertThat(explicitNull.getViolations()).isNotNull().isEmpty();
    }

    @Test
    void nullableRatingMeanRetainsItsRangeValidation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var statistics = new AvailableRatingStatistics();
            assertThat(validator.validateProperty(statistics, "mean")).isEmpty();
            statistics.mean(new BigDecimal("0.9"));
            assertThat(validator.validateProperty(statistics, "mean")).hasSize(1);
        }
    }

    private JsonNode json(Object model) {
        return mapper.readTree(mapper.writeValueAsString(model));
    }

    private static void assertNullProperty(JsonNode model, String property) {
        assertThat(model.has(property)).as(property + " must be present").isTrue();
        assertThat(model.get(property).isNull()).as(property + " must be null").isTrue();
    }
}
