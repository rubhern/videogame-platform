package com.videogameplatform.ratings.application.port;

import com.videogameplatform.ratings.application.PersonalRatingsPage;
import java.util.List;

public interface PersonalRatingsReadPort {
    PersonalRatingsPage read(String userId, Criteria criteria);

    enum Sort {
        UPDATED,
        TITLE,
        VALUE
    }

    /** {@code gameIds}, when not empty, restricts the user's ratings to those Games. */
    record Criteria(
            List<String> tokens,
            Sort sort,
            boolean descending,
            int page,
            int pageSize,
            List<String> gameIds) {
        public Criteria {
            tokens = List.copyOf(tokens);
            gameIds = List.copyOf(gameIds);
        }

        public Criteria(
                List<String> tokens, Sort sort, boolean descending, int page, int pageSize) {
            this(tokens, sort, descending, page, pageSize, List.of());
        }

        public long offset() {
            return ((long) page - 1) * pageSize;
        }
    }
}
