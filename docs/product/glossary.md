# Product glossary

- **Status:** Active
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-29

Terms are provisional until product and domain discovery confirms them.

| Term | Working definition | Notes |
|---|---|---|
| Game | The conceptual video-game work represented in the catalogue | Must not be confused with a platform-specific release or edition |
| Release | The commercial release of a game for one platform and region, represented with a date and explicit precision | A game may have multiple releases; subscription arrival is not a release |
| Availability | Access to a game through a subscription, rotating catalogue, promotion, or similar service | Must not overwrite or be validated as the original platform release |
| Date precision | The granularity known for a release or availability date: day, month, quarter, year, or unknown | Never invent a precise day from an imprecise provider value |
| Cover reference | The normalized pointer used to resolve a game's primary cover without making the provider image binary part of the product's stored assets | May be a provider `image_id` or a product-owned asset reference |
| Cover usage mode | The approved delivery method for a cover: direct provider-CDN reference or product-owned asset | Approval is scoped to the usage mode and release mode; it does not assert ownership of third-party artwork |
| Verification level | Whether release evidence is provider-only or verified against an accepted official source | It remains independent from review need and freshness |
| Review status | Whether unresolved ambiguity, conflict, incompleteness, or material change requires manual review | Review can be required whether evidence is provider-only or previously verified |
| Freshness status | Whether synchronized data remains within the applicable operational freshness threshold | It is derived from timestamps and policy; stale data does not erase its verification level |
| Edition | A commercially distinct version or package of a game | Deferred from the learning MVP unless provider data requires minimal handling |
| Platform | Hardware or distribution environment on which a game is released | In technical documents, use `deployment platform` when ambiguity is possible |
| Game page | The user-facing view of essential information about one game | Previously referred to as a game detail or catalogue page |
| Rating | One active integer score from 1 to 10 submitted by a user for a released game | Different from a written review; displayed without `/10`; editable and removable |
| Rating eligibility | A dated decision explaining whether at least one qualifying commercial release currently permits creating or changing a rating | It uses explicit release status, date precision, verification, and review rules |
| Rating selector | The compact in-context control that exposes integers 1–10 when the personal-rating field is activated | It is not a separate page; it remains disabled until the game is released |
| Aggregate rating | A calculated summary of eligible user ratings, displayed separately from the current user's rating | The learning MVP uses an arithmetic mean to one decimal, rating count, and distribution, with no weighting or platform-specific aggregate; Spanish copy uses a decimal comma and shows `No disponible` rather than inventing a number |
| Review | Written user or professional commentary about a game | Outside the learning MVP |
| Catalogue | The explicitly bounded set of curated, supported, and visible games and their release information | Provider results and import candidates remain outside the domain until curation succeeds |
| External provider | An authorised source of catalogue, release, or referenced-cover data | IGDB is approved with explicit limitations for the private learning MVP; RAWG remains a fallback |
| MVP | The smallest product capable of testing the most important assumptions through the evidence standard chosen for the current release mode | Not a reduced version of the entire long-term vision; this private learning project may accept explicitly labelled simulation for internal decisions |
| Learning MVP | The smallest end-to-end product increment that exercises the intended product journey and the selected architectural learning goals | It is not evidence of product–market fit and has no commercial launch commitment |
| Spanish-first | Product behaviour designed around Spanish-speaking users, including interface language, regional release context, source provenance, and terminology | More than translating an English-first interface |
| Mis puntuaciones | The minimal personal view where a signed-in user can find, sort, edit, and delete their ratings | Part of the MVP rating loop; not a full game library |
| Synthetic research | Fictional research material used to rehearse methods and expose plausible patterns | The owner may accept it as decision-grade internal evidence for this private learning project, but it remains synthetic and cannot substantiate real-user behaviour, demand, retention, or product–market fit |
| Journey gate | The decision boundary before implementation contracts: at least four of five accepted sessions complete the core journey unaided and no blocking issue remains | Current result is `PASS`: the accepted simulated round reached 4/5 and the focused simulated regression resolved F-01 |
| Owner | The person accountable for a decision and its consequences | Always Ruben Hernandez in this personal project; AI may assist but does not own or approve decisions |
| Vertical slice | A releasable capability implemented across every necessary layer | Includes relevant quality, security, data, and operational work |
| Product Brief | The concise alignment document defining direction, audience, problem, value, boundaries, assumptions, and risks | It is not a detailed PRD or implementation backlog |
