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

    record Criteria(List<String> tokens, Sort sort, boolean descending, int page, int pageSize) {
        public Criteria {
            tokens = List.copyOf(tokens);
        }

        public long offset() {
            return ((long) page - 1) * pageSize;
        }
    }
}
