# Development documentation

These documents own stable workflows. Executable details remain in scripts,
manifests, configuration, migrations, and workflows.

| Document | Responsibility |
|---|---|
| [Local setup](local-setup.md) | Supported workstation, dependencies, local run modes, and reset boundary |
| [OpenAPI workflow](openapi.md) | Contract validation, generated clients/interfaces, and change sequence |
| [Database migrations](database-migrations.md) | Flyway authoring, privilege, seed, and validation policy |
| [Observability](observability.md) | Health, correlation, logs, metrics, tracing, and telemetry safety |
| [Continuous integration](continuous-integration.md) | CI selection, stable gates, and command mapping |
| [Delivery lifecycle](delivery-lifecycle.md) | Human workflow, risk, versioning, validation policy, acceptance, and Definition of Done |
| [Work management](work-management.md) | GitHub Issue/Project statuses and field meaning |

Immediate backend/frontend commands belong in their module READMEs. Environment and
deployment architecture belong in the
[platform design](../architecture/deployment/mvp-platform-and-delivery.md).
