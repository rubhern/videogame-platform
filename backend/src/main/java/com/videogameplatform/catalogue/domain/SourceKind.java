package com.videogameplatform.catalogue.domain;

/** Closed provenance-source vocabulary owned by the product. */
public enum SourceKind {
    EXTERNAL_PROVIDER("external_provider"),
    PRODUCT_CURATED("product_curated"),
    OFFICIAL_SOURCE("official_source");

    private final String value;

    SourceKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SourceKind fromValue(String value) {
        for (SourceKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unsupported source kind: " + value);
    }
}
