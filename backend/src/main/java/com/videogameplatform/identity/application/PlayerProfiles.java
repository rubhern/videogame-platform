package com.videogameplatform.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Owns the public alias and consent. Scope owner IDs are for internal application use only. */
public interface PlayerProfiles {
    Optional<Settings> settings(String userId);

    Settings save(String userId, Change change);

    Optional<Published> published(String alias);

    record Settings(
            String alias,
            Instant chosenAt,
            UUID revision,
            boolean published,
            boolean shareRatings,
            boolean shareWishlist) {}

    record Change(
            String alias,
            UUID revision,
            boolean published,
            boolean shareRatings,
            boolean shareWishlist) {
        public Change {
            if (!published) {
                shareRatings = false;
                shareWishlist = false;
            } else if (!shareRatings && !shareWishlist) {
                throw new Rejected(Rejected.Reason.SECTION_REQUIRED);
            }
        }
    }

    record Published(String userId, String alias, boolean shareRatings, boolean shareWishlist) {}

    final class Rejected extends RuntimeException {
        private final Reason reason;

        public Rejected(Reason reason) {
            super(reason.name());
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }

        public enum Reason {
            INVALID_ALIAS,
            SECTION_REQUIRED,
            ALIAS_UNAVAILABLE,
            CHANGE_TOO_SOON,
            STALE_REVISION,
            NOT_FOUND
        }
    }
}
