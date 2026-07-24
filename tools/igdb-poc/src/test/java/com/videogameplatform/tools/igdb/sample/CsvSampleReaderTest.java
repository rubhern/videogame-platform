package com.videogameplatform.tools.igdb.sample;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class CsvSampleReaderTest {

    @Test
    void readsTheFrozenSixtyCaseSample() throws Exception {
        Path sample = Path.of(System.getProperty("basedir"))
                .resolve("../../docs/research/igdb-poc-sample.csv")
                .normalize();

        var cases = new CsvSampleReader().read(sample);

        assertThat(cases).hasSize(60);
        assertThat(cases).extracting(ExpectedCase::caseId).doesNotHaveDuplicates();
        Map<String, Long> counts = cases.stream().collect(Collectors.groupingBy(
                ExpectedCase::category, Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(
                "recent_release", 10L,
                "upcoming_release", 10L,
                "localized_title", 10L,
                "indie", 10L,
                "legacy_multiplatform_version", 10L,
                "dlc_expansion", 5L,
                "delayed_imprecise", 5L));
    }
}
