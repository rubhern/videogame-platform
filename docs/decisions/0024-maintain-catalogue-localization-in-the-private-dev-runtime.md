# ADR-0024: Maintain catalogue localization in the private-dev runtime

- **Status:** Accepted
- **Date:** 2026-10-06
- **Owner:** Ruben Hernandez
- **Decision authority:** Explicit owner instruction and [#257](https://github.com/rubhern/videogame-platform/issues/257)
- **Partially supersedes:** Private-dev optional activation in [ADR-0022](0022-localize-catalogue-content-during-acquisition.md) and the localization-overlay promotion restriction in [ADR-0023](0023-automate-owner-approved-private-dev-application-promotion.md)

## Context

The initial localization overlay made application acquisition configuration depend
on a separate operator invocation. Standard application promotion either rejected an
application using that overlay or recreated a base application without its translation
endpoint. The owner approved localization as a required operational dependency of
private-dev acquisition, while preserving PostgreSQL-only product reads and the
failure semantics adopted in ADR-0022.

## Decision

Include the localizer in the standard private-dev Compose runtime and always give the
application its internal translation endpoint. Remove the private-dev overlay.
Share helper mechanics with the optional workstation overlay through one service
definition; this does not create a second private-dev deployment mode.

Require an already running, healthy localizer with the expected resolved configuration
and local image before application migration or activation. Application promotion
inspects the helper and recreates only application; it never builds, starts or upgrades
the localizer. Translation health stays outside HTTP product readiness and the user
request path. Failures preserve source and last-valid localized content.

Keep model weights in immutable directories outside Git, mounted read-only from the
protected runtime selection. Real helper configuration, image/runtime or model changes
require their explicit reviewed rollout before owner acknowledgement of the new
deployment contract. Accept an existing application's retired localization-overlay
label only to transition to the equivalent base configuration; retain refusal of
other overlays.

The [platform design](../architecture/deployment/mvp-platform-and-delivery.md#private-dev-runtime-boundary)
owns current policy, the [private-dev README](../../deploy/private-dev/README.md#catalogue-localization-helper)
owns the one-time migration and rollout commands, and the
[runbook](../development/operations-runbook.md#catalogue-localization) owns evidence.
This decision approves the lifecycle, not execution on the real host.

## Alternatives and consequences

Retaining an optional private-dev overlay requires two application invocation forms
and makes the endpoint fragile between deployments. Starting/upgrading the helper
inside every promotion hides runtime changes and can disrupt acquisition.
Adding translation to product readiness would block valid PostgreSQL serving.

Standard host restart recovers the helper through its persistent restart policy.
An acquisition outage now refuses a new application deployment while the current
application continues serving local content. Model rollback preserves directories
and derived database content. Shared-host capacity and reboot persistence require
real-host evidence; limits and local doubles do not establish acceptance.
Reconsider only for measured host constraints or an explicit change to acquisition
requirements, preserving the private topology and request boundary.
