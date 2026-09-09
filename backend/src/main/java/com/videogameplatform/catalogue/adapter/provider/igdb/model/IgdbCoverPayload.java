package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/** IGDB cover transport shape. Only the opaque image identifier ever leaves the adapter. */
public record IgdbCoverPayload(String imageId) {}
