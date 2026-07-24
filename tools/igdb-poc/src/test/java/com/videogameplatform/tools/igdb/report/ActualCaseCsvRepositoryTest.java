package com.videogameplatform.tools.igdb.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.videogameplatform.tools.igdb.PocRuntime;
import com.videogameplatform.tools.igdb.model.ActualCase;
import com.videogameplatform.tools.igdb.model.ActualRelease;

class ActualCaseCsvRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsCanonicalDataForOfflineValidation() throws Exception {
        ActualCase expected = new ActualCase(
                "CASE-01", true, 42L, "Example", "main_game", "",
                List.of("Localized Example"),
                List.of(new ActualRelease(
                        "PC", "Worldwide", "2026", "year", "announced", "")),
                true, true, true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-07-24T00:00:00Z"), "IGDB", 2, 1, "");
        ActualCaseCsvRepository repository = new ActualCaseCsvRepository(PocRuntime.objectMapper());
        Path path = temporaryDirectory.resolve("actual-results.csv");

        repository.write(path, List.of(expected));
        List<ActualCase> actual = repository.read(path);

        assertThat(actual).containsExactly(expected);
    }
}
