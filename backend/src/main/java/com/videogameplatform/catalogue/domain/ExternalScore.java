package com.videogameplatform.catalogue.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One external source's aggregate player score of a game: the source's own mean on its declared
 * 0–100 scale and the positive number of that source's ratings behind it.
 *
 * <p>It is external sentiment with its own provenance, never a Gameómetro rating, and nothing
 * here combines it with one. A single rating is a usable score; a missing mean, zero ratings or a
 * value outside the declared scale is no score at all, so there is nothing to construct. The mean
 * is kept to a millionth of a point, far below any presented precision, and never truncated.
 */
public record ExternalScore(BigDecimal value, long count) {

    /** The upper end of the declared scale; the lower end is zero. */
    public static final BigDecimal SCALE_MAX = BigDecimal.valueOf(100);

    /** Fractional digits the product keeps of an external mean. */
    public static final int FRACTION_DIGITS = 6;

    public ExternalScore {
        if (value == null || value.signum() < 0 || value.compareTo(SCALE_MAX) > 0) {
            throw new IllegalArgumentException("An external score lies on its 0–100 scale");
        }
        if (count < 1) {
            throw new IllegalArgumentException("An external score needs at least one rating");
        }
        value = value.setScale(FRACTION_DIGITS, RoundingMode.HALF_UP);
    }
}
