package com.videogameplatform.catalogue.domain;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The product display label of a Region: what a visitor reads in release filters, discovery cards
 * and the game page (CAT-008).
 *
 * <p>A label is presentation only. Region identity is the product ID and its typed provider
 * reference (CAT-007), so a label never identifies, merges, filters or resolves a region. Like a
 * catalogue slug, it is derived once, when an accepted release first introduces the region, and
 * then left alone: a later provider rename neither changes what the product shows nor creates a
 * second region.
 *
 * <p>A provider descriptor with an approved Spanish label uses it whatever its case or separators,
 * so {@code new_zealand} and {@code New Zealand} both read {@code Nueva Zelanda}. Any other
 * descriptor degrades to a readable form of itself instead of failing acquisition or showing a
 * technical token: {@code middle_east} reads {@code Middle East}, while a name the provider already
 * wrote for people, such as {@code Asia-Pacific}, keeps its spelling. Only a descriptor whose
 * readable form is not already its Spanish label needs an approved label, which keeps this a small
 * product vocabulary rather than a mirror of the provider's regions.
 */
public record RegionLabel(String value) {

    private static final String UNNAMED = "Región sin nombre";
    private static final Pattern WORD_SEPARATORS = Pattern.compile("[\\s\\p{Cntrl}_-]+");
    private static final Pattern SPACING = Pattern.compile("[\\s\\p{Cntrl}]+");

    /** Approved Spanish labels, keyed by the descriptor's lowercase words. */
    private static final Map<String, String> APPROVED_SPANISH_LABELS =
            Map.of(
                    "worldwide", "Mundial",
                    "europe", "Europa",
                    "north america", "Norteamérica",
                    "japan", "Japón",
                    "new zealand", "Nueva Zelanda",
                    "korea", "Corea",
                    "brazil", "Brasil");

    public RegionLabel {
        if (value == null
                || value.isBlank()
                || !value.equals(value.strip())
                || value.indexOf('_') >= 0) {
            throw new IllegalArgumentException("A region label must be readable presentation text");
        }
    }

    /** Derives the label of a newly acquired region from its provider descriptor; never fails. */
    public static RegionLabel fromDescriptor(String descriptor) {
        String text = descriptor == null ? "" : descriptor;
        String words =
                WORD_SEPARATORS.matcher(text).replaceAll(" ").strip().toLowerCase(Locale.ROOT);
        String approved = APPROVED_SPANISH_LABELS.get(words);
        if (approved != null) {
            return new RegionLabel(approved);
        }
        if (words.isEmpty()) {
            return new RegionLabel(UNNAMED);
        }
        // A lowercase or underscored descriptor is a technical token; a cased one was written for
        // people and only loses irregular spacing.
        boolean technical =
                text.indexOf('_') >= 0 || text.codePoints().noneMatch(Character::isUpperCase);
        return new RegionLabel(
                technical ? capitalized(words) : SPACING.matcher(text).replaceAll(" ").strip());
    }

    private static String capitalized(String words) {
        StringBuilder label = new StringBuilder(words.length());
        boolean wordStart = true;
        for (int offset = 0; offset < words.length(); ) {
            int codePoint = words.codePointAt(offset);
            label.appendCodePoint(wordStart ? Character.toTitleCase(codePoint) : codePoint);
            wordStart = codePoint == ' ';
            offset += Character.charCount(codePoint);
        }
        return label.toString();
    }
}
