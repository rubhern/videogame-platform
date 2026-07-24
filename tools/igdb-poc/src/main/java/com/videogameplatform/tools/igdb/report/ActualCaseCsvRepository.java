package com.videogameplatform.tools.igdb.report;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.model.ActualRelease;

public final class ActualCaseCsvRepository {

    private static final String[] HEADERS = {
            "case_id", "found", "provider_id", "title", "type", "parent_title",
            "alternative_titles_json", "releases_json", "cover_available",
            "genre_available", "company_available", "provider_updated_at",
            "synchronized_at", "provenance", "candidate_count",
            "exact_title_match_count", "normalization_error"
    };

    private final ObjectMapper objectMapper;

    public ActualCaseCsvRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path path, List<ActualCase> cases) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
             CSVPrinter printer = CSVFormat.DEFAULT.builder()
                     .setHeader(HEADERS)
                     .get()
                     .print(writer)) {
            for (ActualCase actual : cases) {
                printer.printRecord(
                        actual.caseId(),
                        actual.found(),
                        value(actual.providerId()),
                        actual.title(),
                        actual.type(),
                        actual.parentTitle(),
                        objectMapper.writeValueAsString(actual.alternativeTitles()),
                        objectMapper.writeValueAsString(actual.releases()),
                        actual.coverAvailable(),
                        actual.genreAvailable(),
                        actual.companyAvailable(),
                        value(actual.providerUpdatedAt()),
                        value(actual.synchronizedAt()),
                        actual.provenance(),
                        actual.candidateCount(),
                        actual.exactTitleMatchCount(),
                        actual.normalizationError());
            }
        }
    }

    public List<ActualCase> read(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .get()
                     .parse(reader)) {
            List<ActualCase> cases = new ArrayList<>();
            for (CSVRecord row : parser) {
                cases.add(new ActualCase(
                        row.get("case_id"),
                        Boolean.parseBoolean(row.get("found")),
                        nullableLong(row.get("provider_id")),
                        row.get("title"),
                        row.get("type"),
                        row.get("parent_title"),
                        objectMapper.readValue(
                                row.get("alternative_titles_json"),
                                new TypeReference<List<String>>() { }),
                        objectMapper.readValue(
                                row.get("releases_json"),
                                new TypeReference<List<ActualRelease>>() { }),
                        Boolean.parseBoolean(row.get("cover_available")),
                        Boolean.parseBoolean(row.get("genre_available")),
                        Boolean.parseBoolean(row.get("company_available")),
                        nullableInstant(row.get("provider_updated_at")),
                        nullableInstant(row.get("synchronized_at")),
                        row.get("provenance"),
                        Integer.parseInt(row.get("candidate_count")),
                        Integer.parseInt(row.get("exact_title_match_count")),
                        row.get("normalization_error")));
            }
            return List.copyOf(cases);
        }
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private Long nullableLong(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    private Instant nullableInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
