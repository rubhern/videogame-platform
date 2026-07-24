package com.videogameplatform.tools.igdb.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.support.SecretRedactor;

public final class EvidenceStore {

    private final Path output;
    private final ObjectMapper objectMapper;
    private final Collection<String> secrets;
    private int redactionCount;

    public EvidenceStore(Path output, ObjectMapper objectMapper, Collection<String> secrets) {
        this.output = output;
        this.objectMapper = objectMapper;
        this.secrets = secrets;
    }

    public void prepare() throws IOException {
        Files.createDirectories(output.resolve("raw"));
        Files.createDirectories(output.resolve("normalized"));
    }

    public void writeRaw(String caseId, String stage, String response) throws IOException {
        String safe = SecretRedactor.redact(response, secrets);
        if (!safe.equals(response)) {
            redactionCount++;
        }
        Files.writeString(
                output.resolve("raw").resolve(fileName(caseId) + "-" + fileName(stage) + ".json"),
                safe,
                StandardCharsets.UTF_8);
    }

    public void writeNormalized(ActualCase actual) throws IOException {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual);
        String safe = SecretRedactor.redact(json, secrets);
        if (!safe.equals(json)) {
            redactionCount++;
        }
        Files.writeString(
                output.resolve("normalized").resolve(fileName(actual.caseId()) + ".json"),
                safe,
                StandardCharsets.UTF_8);
    }

    public int redactionCount() {
        return redactionCount;
    }

    private String fileName(String caseId) {
        return caseId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
