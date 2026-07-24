package com.videogameplatform.tools.igdb.validation;

public record FieldCheck(
        String field,
        String expected,
        String actual,
        CheckOutcome outcome,
        boolean blocking,
        String detail) {
}
