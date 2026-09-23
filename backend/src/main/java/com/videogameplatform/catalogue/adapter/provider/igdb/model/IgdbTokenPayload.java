package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * Twitch client-credentials response.
 *
 * <p>The token never reaches a log, a metric, an exception message or the browser.
 */
public record IgdbTokenPayload(String accessToken, Long expiresIn, String tokenType) {}
