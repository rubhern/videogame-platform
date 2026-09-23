package com.videogameplatform.ratings.application.internal;

import com.videogameplatform.catalogue.application.details.CatalogueQueryText;
import com.videogameplatform.ratings.application.ListPersonalRatingsUseCase;
import com.videogameplatform.ratings.application.PersonalRatingsPage;
import com.videogameplatform.ratings.application.PersonalRatingsQueryInvalidException;
import com.videogameplatform.ratings.application.PersonalRatingsQueryInvalidException.Field;
import com.videogameplatform.ratings.application.port.PersonalRatingsReadPort;
import com.videogameplatform.ratings.application.port.PersonalRatingsReadPort.Sort;
import java.util.List;

public final class PersonalRatingsService implements ListPersonalRatingsUseCase {
    private final PersonalRatingsReadPort read;

    public PersonalRatingsService(PersonalRatingsReadPort read) {
        this.read = read;
    }

    @Override
    public PersonalRatingsPage list(String userId, Query query) {
        List<String> tokens =
                query.search() == null ? List.of() : CatalogueQueryText.tokens(query.search());
        if (query.search() != null
                && (tokens.isEmpty()
                        || query.search().codePointCount(0, query.search().length()) > 100)) {
            throw new PersonalRatingsQueryInvalidException(Field.SEARCH);
        }
        Sort sort =
                switch (query.sort() == null ? "updatedAt" : query.sort()) {
                    case "updatedAt" -> Sort.UPDATED;
                    case "canonicalTitle" -> Sort.TITLE;
                    case "ratingValue" -> Sort.VALUE;
                    default -> throw new PersonalRatingsQueryInvalidException(Field.SORT);
                };
        String direction =
                query.direction() == null
                        ? (sort == Sort.UPDATED ? "desc" : "asc")
                        : query.direction();
        if (!direction.equals("asc") && !direction.equals("desc"))
            throw new PersonalRatingsQueryInvalidException(Field.DIRECTION);
        if (query.page() < 1 || query.pageSize() < 1 || query.pageSize() > 100)
            throw new PersonalRatingsQueryInvalidException(Field.PAGINATION);
        return read.read(
                userId,
                new PersonalRatingsReadPort.Criteria(
                        tokens, sort, direction.equals("desc"), query.page(), query.pageSize()));
    }
}
