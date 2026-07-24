package com.videogameplatform.tools.igdb.validation;

public record MetricResult(
        String key,
        String description,
        String threshold,
        String actual,
        boolean blocking,
        MetricStatus status) {
}
