# Architecture

The initial product problem, journey, MVP boundary, and provider constraints are
explicit. The [prototype journey gate](../research/simulated-round-synthesis.md) is
`PASS`, so minimum contract discovery for the first vertical slice can begin. No
production framework, database, deployment model, or distributed architecture is
approved yet.

The only approved integration constraints are provider independence, local
synchronized reads, separate release and subscription-availability concepts, and no
direct browser calls to IGDB.

Define only the provider-independent domain and API contracts required by one
end-to-end vertical slice. Start with game identity, platform-region release,
date precision and provenance, rating eligibility, aggregate rating, and the
authenticated user's rating. Do not turn the Figma structure into an application
architecture.
