# Product glossary

- **Status:** Active
- **Owner:** Ruben Hernandez
- **Last updated:** 2026-07-24

Terms are provisional until product and domain discovery confirms them.

| Term | Working definition | Notes |
|---|---|---|
| Game | The conceptual video-game work represented in the catalogue | Must not be confused with a platform-specific release or edition |
| Release | The commercial release of a game for one platform and region, represented with a date and explicit precision | A game may have multiple releases; subscription arrival is not a release |
| Availability | Access to a game through a subscription, rotating catalogue, promotion, or similar service | Must not overwrite or be validated as the original platform release |
| Date precision | The granularity known for a release or availability date: day, month, quarter, year, or unknown | Never invent a precise day from an imprecise provider value |
| Verification status | Whether a release value is provider-only, verified, stale, or requires review | Recent, upcoming, or conflicting displayed dates may require manual verification |
| Edition | A commercially distinct version or package of a game | Deferred from the learning MVP unless provider data requires minimal handling |
| Platform | Hardware or distribution environment on which a game is released | In technical documents, use `deployment platform` when ambiguity is possible |
| Game page | The user-facing view of essential information about one game | Previously referred to as a game detail or catalogue page |
| Rating | A structured score submitted by a user for a game | Different from a written review |
| Aggregate rating | A calculated summary of eligible user ratings | The learning MVP uses an arithmetic mean to one decimal, rating count, and distribution, with no weighting or platform-specific aggregate |
| Review | Written user or professional commentary about a game | Outside the learning MVP |
| Catalogue | The internally represented set of games and related release information | External provider data is an input, not the domain model itself |
| External provider | An authorised source of catalogue or release data | IGDB is approved with explicit limitations for the private learning MVP; RAWG remains a fallback |
| MVP | The smallest product capable of testing the most important assumptions with real users | Not a reduced version of the entire long-term vision |
| Learning MVP | The smallest end-to-end product increment that exercises the intended product journey and the selected architectural learning goals | It is not evidence of product–market fit and has no commercial launch commitment |
| Spanish-first | Product behaviour designed around Spanish-speaking users, including interface language, regional release context, source provenance, and terminology | More than translating an English-first interface |
| Mis puntuaciones | The minimal personal view where a signed-in user can find, sort, edit, and delete their ratings | Part of the MVP rating loop; not a full game library |
| Synthetic research | Fictional research material used to rehearse methods and expose plausible patterns | It may inform owner decisions but cannot support or reject a demand hypothesis |
| Owner | The person accountable for a decision and its consequences | Always Ruben Hernandez in this personal project; AI may assist but does not own or approve decisions |
| Vertical slice | A releasable capability implemented across every necessary layer | Includes relevant quality, security, data, and operational work |
| Product Brief | The concise alignment document defining direction, audience, problem, value, boundaries, assumptions, and risks | It is not a detailed PRD or implementation backlog |
