package com.videogameplatform.tools.igdb.sample;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public final class CsvSampleReader {

    private static final List<String> REQUIRED_HEADERS = List.of(
            "case_id", "category", "search_query", "expected_title", "expected_type",
            "expected_parent_title", "expected_platform", "expected_region",
            "expected_release_date", "expected_date_precision", "expected_status",
            "expected_alternative_title", "criticality", "evidence");

    public List<ExpectedCase> read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setTrim(true)
                     .get()
                     .parse(reader)) {
            requireHeaders(parser);
            List<ExpectedCase> cases = parser.stream().map(this::map).toList();
            validateCases(cases);
            return cases;
        }
    }

    private void requireHeaders(CSVParser parser) {
        Set<String> headers = parser.getHeaderMap().keySet();
        if (!headers.containsAll(REQUIRED_HEADERS)) {
            Set<String> missing = new HashSet<>(REQUIRED_HEADERS);
            missing.removeAll(headers);
            throw new IllegalArgumentException("Sample CSV is missing headers: " + missing);
        }
    }

    private ExpectedCase map(CSVRecord row) {
        try {
            return new ExpectedCase(
                    required(row, "case_id"),
                    required(row, "category"),
                    required(row, "search_query"),
                    required(row, "expected_title"),
                    required(row, "expected_type"),
                    value(row, "expected_parent_title"),
                    value(row, "expected_platform"),
                    value(row, "expected_region"),
                    value(row, "expected_release_date"),
                    required(row, "expected_date_precision"),
                    required(row, "expected_status"),
                    value(row, "expected_alternative_title"),
                    required(row, "criticality"),
                    URI.create(required(row, "evidence")));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Invalid sample row " + row.getRecordNumber() + ": " + exception.getMessage(),
                    exception);
        }
    }

    private void validateCases(List<ExpectedCase> cases) {
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Sample CSV contains no cases");
        }
        Set<String> ids = new HashSet<>();
        for (ExpectedCase expected : cases) {
            if (!ids.add(expected.caseId())) {
                throw new IllegalArgumentException("Duplicate case_id: " + expected.caseId());
            }
            if (!Set.of("blocking", "non_blocking").contains(expected.criticality().toLowerCase())) {
                throw new IllegalArgumentException(
                        "Unsupported criticality for " + expected.caseId() + ": " + expected.criticality());
            }
        }
    }

    private String required(CSVRecord row, String header) {
        String value = value(row, header);
        if (value.isBlank()) {
            throw new IllegalArgumentException(header + " must not be blank");
        }
        return value;
    }

    private String value(CSVRecord row, String header) {
        String value = row.get(header);
        return value == null ? "" : value.trim();
    }
}
