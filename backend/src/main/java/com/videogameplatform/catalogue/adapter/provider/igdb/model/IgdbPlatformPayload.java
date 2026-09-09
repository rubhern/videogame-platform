package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/** IGDB platform transport shape; the slug is the stable identifier the product maps from. */
public record IgdbPlatformPayload(String slug, String name) {}
