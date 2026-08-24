# Learning MVP API conventions

- **Status:** Approved
- **Owner:** Ruben Hernandez
- **Contract:** [OpenAPI 3.1.2](openapi.yaml)

OpenAPI is authoritative for operations, schemas, parameters, examples, security,
headers, and status responses. This document retains only cross-operation policy not
usefully expressed once in the contract.

## Boundary and naming

- Same-origin HTTPS outside loopback; HTTP/JSON under `/api/v1`.
- Success JSON uses `application/json`; errors use RFC 9457
  `application/problem+json`.
- Paths use lowercase plural nouns/kebab-case; JSON/query names `lowerCamelCase`;
  enums `lower_snake_case`; stable codes `UPPER_SNAKE_CASE`.
- Product IDs are opaque strings. Slugs are navigation, not identity. The browser
  never supplies rating owner identity.
- Public catalogue/game and personal/session resources remain separate so shared
  caches never vary by personal state.
- Unknown query parameters and command properties are rejected, not ignored.

## HTTP semantics

- `GET` is safe, reads normalized local state, and never calls IGDB.
- Collections use one-based `page` (default 1) and `pageSize` (default 20, max 100),
  with `items` plus `number`, `size`, `totalItems`, `totalPages`.
- Authorization/filter/search/sort precede count/page. Every order ends in a unique
  identifier (`releaseId` for release rows; `gameId` for one-row-per-game results).
- Page/offset remains approved until measured deep-offset/count cost justifies a
  compatibility decision for keyset/cursor semantics.
- Release windows/evaluation date use application time in `Europe/Madrid`; clients do
  not supply them. Day/month/quarter/year/unknown remain a closed representation.
- Catalogue search is trimmed, non-blank, bounded to 100 Unicode code points,
  case/diacritic-insensitive, all-token, non-fuzzy, and searches only canonical title
  plus approved aliases.

Personal rating `PUT` uses `If-None-Match: *` to create and strong `If-Match` to
update. `DELETE` requires strong `If-Match`. Missing/contradictory preconditions
return `428`; stale/existing state returns `412`. Successful commands return coherent
personal and aggregate state. The browser reads current state before retrying an
ambiguous command; no public idempotency key is required for this same-origin MVP.

## Errors

Problem Details contains stable `type`, developer-facing `title`, actual `status`,
safe `detail`, opaque `instance`, stable `code`, `category`, and a `correlationId`
equal to `X-Correlation-ID`. Validation may add code/pointer violations. Clients
parse codes, never messages. No stack trace, SQL, provider payload, token, claim, or
credential is exposed.

Use HTTP semantics consistently: parse errors `400`; unauthenticated `401`; forbidden
`403`; scoped absence `404`; method/representation/media errors `405/406/415`;
conflict/precondition `409/412/428`; semantic validation/business rejection `422`;
unexpected internal failure `500`; unavailable/not-ready local catalogue `503`.
OpenAPI owns the exact code-to-status mapping.

## Session, CSRF, and privacy

The BFF is the confidential OAuth/OIDC client. Tokens stay server-side; identity maps
from validated `issuer + subject`. HTTPS environments use an opaque `Secure`,
`HttpOnly`, `SameSite=Lax`, host-only session cookie. Plain loopback HTTP may use the
documented non-`Secure` local cookie only.

`GET /session` reveals only authentication state and, when authenticated, opaque
session-bound CSRF material. Authenticated state changes require `X-CSRF-Token` and
same-origin browser metadata; CORS is disabled by default. API fetches return `401`
rather than redirecting. Login navigation and callback destinations are allowlisted.
Session/personal/command responses use `Cache-Control: no-store`.

## Caching, observability, and compatibility

Public reads may use explicit `Cache-Control`, `ETag`, and `If-None-Match`; validators
represent the actual response, not merely a catalogue version. Every response has
`X-Correlation-ID`; W3C trace context may propagate. Logs/metrics use route templates
and bounded outcomes, never raw paths, IDs, input, or secrets.

Within `v1`, additive optional response fields/endpoints/parameters with safe defaults
are compatible. Removing/renaming/changing type, requiredness, nullability, enum,
identity, auth, error, or pagination semantics is breaking and requires an explicit
version/migration decision. Deprecation records replacement, measurement, removal
condition, and rollback.

OpenAPI `3.1.2` remains the authoring line until validators and generators support a
later specification. Generated code is disposable; the reviewed YAML remains the
contract. Follow the [OpenAPI workflow](../../development/openapi.md).
