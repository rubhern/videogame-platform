package com.videogameplatform.catalogue.adapter.provider.igdb.model;

/**
 * IGDB company-credit transport shape: one company's roles in one game.
 *
 * <p>Only the developer and publisher roles are read; porting and supporting credits stay unread.
 */
public record IgdbInvolvedCompanyPayload(
        Long id, IgdbCompanyPayload company, Boolean developer, Boolean publisher) {}
