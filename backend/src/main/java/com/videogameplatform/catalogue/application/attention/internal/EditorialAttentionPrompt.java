package com.videogameplatform.catalogue.application.attention.internal;

import com.videogameplatform.catalogue.application.administration.EditorialAttentionLists.Prompt;

/**
 * The prompt an operator gives a language model with web access to obtain an editorial
 * anticipation file. It owns the file schema together with {@code EditorialAttentionLists}: a
 * change to either is a new prompt version.
 */
final class EditorialAttentionPrompt {

    static final String VERSION = "editorial-anticipation@2";

    static final String TEMPLATE =
            """
            You are an editor of a Spanish video game release guide. Build the list of the most \
            anticipated video game releases between {{FROM}} and {{TO}} (both inclusive), as the \
            gaming press and the public see them at the time you answer.

            Research
            - Search the web for "most anticipated games" articles, release roundups and monthly \
            or weekly release calendars covering that range, from at least four independent \
            outlets (for example IGN, GameSpot, Eurogamer, VGC, Push Square, Nintendo Life, \
            Vandal, 3DJuegos, MeriStation). Prefer articles published within the last 60 days.
            - Count a game when its first release, or its first release on a new platform, falls \
            inside the range. Ignore DLC, season passes, updates and bundles; a remake, remaster, \
            port or expanded edition counts when the sources treat it as a launch.
            - Score each game from 1 to 100 by how essential it is that it appears at the top of \
            the range's highlights: how many outlets feature it, how prominently, and how much \
            public expectation they report. 100 is the most anticipated game of the range. Do not \
            give two games the same score unless the evidence is truly equal. Leave out games \
            below 20.

            Identify every game on IGDB
            - igdbSlug MUST be copied from the address of an IGDB page you opened \
            (https://www.igdb.com/games/<igdbSlug>); never derive it from the title. Use null \
            when you cannot open the page.
            - The IGDB entry must be the main game, remake, remaster, port, expanded game or \
            standalone expansion. When the release is sold as a bundle or collection, use the \
            entry of the game that carries the release date inside the range, and say so in \
            the rationale.
            - releaseDate is the date IGDB shows for this release inside the range (YYYY-MM-DD), \
            or null when IGDB shows none. When your sources disagree with IGDB, keep IGDB's date \
            and mention the other date and its source in the rationale.

            Output
            Deliver the result as a downloadable file named \
            editorial-anticipation-{{FROM}}-{{TO}}.json, containing one JSON document and nothing \
            else: no prose and no Markdown fence inside the file. If you cannot create files, \
            answer with that JSON document alone instead. At most 50 entries, ordered by rank \
            (1 = highest score). It must follow this shape exactly:

            {
              "schemaVersion": 1,
              "range": { "from": "{{FROM}}", "to": "{{TO}}" },
              "generatedAt": "<ISO-8601 instant of your answer>",
              "generator": { "prompt": "editorial-anticipation@2", "model": "<your model name>" },
              "entries": [
                {
                  "rank": 1,
                  "igdbSlug": "<slug or null>",
                  "title": "<title as IGDB writes it>",
                  "releaseDate": "<YYYY-MM-DD or null>",
                  "platforms": ["<platform>", "..."],
                  "score": 100,
                  "rationale": "<at most 200 characters: why it is anticipated>",
                  "sources": ["<URL of an article you read>", "<another URL>"]
                }
              ]
            }

            Every entry needs at least two source URLs you actually read. Do not invent games, \
            dates, slugs or sources: leaving a game out is better than guessing.
            """;

    private EditorialAttentionPrompt() {}

    static Prompt prompt() {
        return new Prompt(VERSION, TEMPLATE);
    }
}
