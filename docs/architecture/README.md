# Architecture

Architecture records describe the approved current system. Future capabilities and
revisit triggers are labelled; they are not implementation commitments.

| Record | Responsibility |
|---|---|
| [Solution architecture](mvp-solution-architecture.md) | System/container structure, modules, dependencies, trust and evolution rules |
| [Domain model](domain/mvp-domain-model.md) | Provider-independent concepts, policies, and canonical invariants |
| [Application use cases](application/mvp-use-cases.md) | Operations, actors, guarantees, and errors |
| [API conventions](api/api-conventions.md) | Cross-operation HTTP policy |
| [OpenAPI](api/openapi.yaml) | Authoritative browser-facing paths, schemas, examples, security, and responses |
| [Technology baseline](technology/mvp-technology-baseline.md) | Approved stack and technology policy |
| [Platform and delivery](deployment/mvp-platform-and-delivery.md) | Environments, deployment topology, secrets, recovery, and technical platform behaviour |
| [Diagrams](diagrams/README.md) | Diagram ownership, catalogue, and editing workflow |
| [ADRs](../decisions/README.md) | Durable decisions, alternatives, consequences, and revisit triggers |

The architecture is one same-origin React/Spring application, modular monolith,
PostgreSQL boundary, Keycloak identity provider, and local normalized catalogue.
Provider calls are outside user request paths. Public production and distributed
infrastructure remain deferred.
