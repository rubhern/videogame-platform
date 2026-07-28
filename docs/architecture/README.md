# Architecture

The initial product problem, journey, MVP boundary, and provider constraints are
explicit, but minimum architecture work is deliberately paused until the
[prototype journey gate](../research/prototype-usability-test-guide.md) passes. No
production framework, database, deployment model, or distributed architecture is
approved yet.

The only approved integration constraints are provider independence, local
synchronized reads, separate release and subscription-availability concepts, and no
direct browser calls to IGDB.

After the journey gate passes, define only the provider-independent domain and API
contracts required by one end-to-end vertical slice. Do not turn the Figma structure
into an application architecture.
