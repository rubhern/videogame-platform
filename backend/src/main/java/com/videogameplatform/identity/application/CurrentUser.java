package com.videogameplatform.identity.application;

import java.util.Optional;

/** Supplies only a server-derived product identity to collaborating inbound adapters. */
public interface CurrentUser {
    Optional<String> currentUserId();
}
