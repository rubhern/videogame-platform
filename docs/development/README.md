# Development environment

- [Codex workspace setup](codex-setup.md): verified tools, configuration,
  rationale, risks, and deferred capabilities.
- [OpenAPI contract validation](openapi-validation.md): syntax, lint, references,
  schemas, examples, local execution, and CI integration.
- [OpenAPI web documentation tutorial](openapi-web-documentation.md): regenerate,
  inspect, and update the static Redoc API reference.
- [Learning MVP delivery lifecycle](delivery-lifecycle.md): readiness, Git and pull
  requests, quality gates, acceptance, releases, and Definition of Done.
- [IGDB provider PoC](../../tools/igdb-poc/README.md): isolated Java CLI,
  local-fixture validation, and authenticated execution instructions.

This section documents reproducible development and delivery setup. Technical
environment and deployment mechanics belong in the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md). It must
not contain credentials, tokens, personal data, or machine-specific secrets.

The current Phase 1 gate is the technology baseline. Approve supported versions and
durable implementation choices before creating the local application skeleton.
