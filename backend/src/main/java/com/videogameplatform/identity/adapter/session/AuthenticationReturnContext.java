package com.videogameplatform.identity.adapter.session;

import java.io.Serializable;
import java.time.Instant;

/** One bounded local destination, kept exclusively in the existing BFF session. */
public record AuthenticationReturnContext(String path, Instant issuedAt) implements Serializable {}
