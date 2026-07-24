package com.videogameplatform.tools.igdb.support;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class TextNormalizer {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    private static final Map<String, Set<String>> PLATFORM_ALIASES = Map.of(
            "xbox series x|s", Set.of("xbox series x|s", "xbox series"),
            "pc", Set.of("pc", "pc (microsoft windows)", "microsoft windows", "windows"),
            "playstation 5", Set.of("playstation 5", "ps5"),
            "playstation 4", Set.of("playstation 4", "ps4"),
            "playstation 3", Set.of("playstation 3", "ps3"),
            "nintendo gamecube", Set.of("nintendo gamecube", "gamecube"));

    private static final Map<String, Set<String>> REGION_ALIASES = Map.of(
            "worldwide", Set.of("worldwide", "global"),
            "europe", Set.of("europe", "eu"),
            "north america", Set.of("north america", "north_america", "na"),
            "japan", Set.of("japan", "jp"),
            "unknown", Set.of("", "unknown", "tbd"));

    private TextNormalizer() {
    }

    public static String title(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('’', '\'')
                .replace('‘', '\'')
                .replace('–', '-')
                .replace('—', '-')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    public static String identifier(String value) {
        return title(value)
                .replace('-', '_')
                .replace(' ', '_');
    }

    public static boolean titleMatches(String expected, String actual) {
        String expectedKey = titleKey(expected);
        String actualKey = titleKey(actual);
        return expectedKey.equals(actualKey)
                || expectedKey.equals("the " + actualKey)
                || actualKey.equals("the " + expectedKey);
    }

    public static boolean platformMatches(String expected, String actual) {
        return aliasMatches(expected, actual, PLATFORM_ALIASES);
    }

    public static boolean regionMatches(String expected, String actual) {
        String normalizedExpected = title(expected);
        String normalizedActual = title(actual);
        if ("unknown".equals(normalizedExpected)) {
            return true;
        }
        if ("worldwide".equals(normalizedExpected)) {
            return !normalizedActual.isBlank();
        }
        if ("worldwide".equals(normalizedActual)) {
            return true;
        }
        return aliasMatches(expected, actual, REGION_ALIASES);
    }

    private static String titleKey(String value) {
        return NON_ALPHANUMERIC.matcher(title(value))
                .replaceAll(" ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean aliasMatches(
            String expected,
            String actual,
            Map<String, Set<String>> aliases) {
        String normalizedExpected = title(expected);
        String normalizedActual = title(actual);
        Set<String> accepted = aliases.getOrDefault(normalizedExpected, Set.of(normalizedExpected));
        return accepted.contains(normalizedActual);
    }
}
