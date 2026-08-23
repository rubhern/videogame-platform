---
name: videogame-platform-frontend-development
description: Implement, modify, refactor, debug, test, or review VideoGame Platform frontend code using the project's React 19, strict TypeScript, Vite, React Router, TanStack Query, Tailwind CSS, OpenAPI-generated types, same-origin BFF, accessibility, Vitest, React Testing Library, and Playwright decisions. Use for frontend React/TypeScript work. Do not use for backend-only or product-alignment-only tasks.
---

# VideoGame Platform Frontend Development

Implement frontend changes according to the repository's approved product,
architecture, API, and technology decisions.

This skill is project-specific. Repository sources of truth override generic
external skills.

## Authority

Before making a material frontend change:

1. Read `AGENTS.md`.
2. Identify the approved user journey or use case affected.
3. Read `frontend/README.md`.
4. Read only the relevant approved architecture, API, and product sources.
5. Inspect the existing frontend implementation and tests.
6. Inspect generated OpenAPI types before inventing transport types.
7. Inspect current dependency versions before applying version-specific framework
   advice.

When sources conflict, report the conflict instead of choosing silently.

Do not change an approved product or architecture decision merely because an
external skill recommends another pattern.

## Approved frontend baseline

Preserve the current approved baseline unless an explicit owner decision changes it:

- React 19.2.x.
- TypeScript strict mode.
- Vite 8.1.x.
- Node.js 24 LTS.
- npm with committed `package-lock.json`.
- React Router.
- TanStack Query.
- Tailwind CSS 4.x.
- `openapi-typescript`.
- `openapi-fetch` behind a product-facing API layer.
- Vitest.
- React Testing Library.
- Playwright.
- axe-core accessibility checks.

Use exact executable versions from repository manifests rather than duplicating
patch versions here.

## Explicitly deferred or rejected by default

Do not introduce merely because a generic skill recommends them:

- Next.js;
- SSR;
- React Server Components;
- server actions;
- SWR;
- Redux;
- Zustand;
- Jotai;
- another global state library;
- shadcn/ui;
- another component library;
- CSS-in-JS;
- another routing library;
- another HTTP client;
- GraphQL;
- another E2E framework;
- another test runner;
- Storybook;
- a full design system.

Any of these require a real product or technical need, or an explicit bounded
learning decision.

## Rendering and architecture

The current frontend is a client-rendered React SPA.

It uses the same-origin backend/BFF boundary.

Do not migrate it to SSR, Next.js, or another rendering architecture as an
incidental refactor.

Browser navigation belongs to React Router. Server-owned paths remain server-owned.
Do not create frontend routes under:

- `/api`;
- `/auth`;
- `/actuator`.

Authentication navigation goes through the server-side BFF. The browser must not
implement its own OAuth token lifecycle.

## State ownership

Use the smallest correct owner for state.

### Server state

TanStack Query owns remote server state:

- loading;
- cached data;
- invalidation;
- mutations;
- deliberate retry;
- freshness.

Do not duplicate server state in React component state or a global store. Do not
copy query results into `useState` merely to keep a synchronized duplicate.

### Local UI state

Use component state for transient, local interaction state.

Use React context only when state genuinely belongs to a subtree or cross-component
capability. Do not create a global state store until concrete state ownership
problems justify it.

### URL state

Use route or search parameters for state that must be navigable, shareable,
bookmarkable, or restored through browser navigation when consistent with the
approved API and product contract.

Do not use the URL for sensitive personal state or credentials.

## API boundary

The authoritative browser API contract is:

```text
docs/architecture/api/openapi.yaml
```

Frontend contract flow is:

```text
OpenAPI
    ->
openapi-typescript
    ->
generated schema types
    ->
openapi-fetch transport
    ->
product-facing API functions/hooks
    ->
React UI
```

Rules:

- generated OpenAPI types are disposable generated source;
- never edit generated types manually;
- use the repository generation command;
- UI components must not call the raw transport client directly;
- route and page components must consume product-facing functions or hooks;
- do not let generated operation shapes spread through arbitrary UI components;
- map transport failures into explicit product and UI states;
- never create parallel hand-written API DTOs merely to avoid the reviewed contract;
- if the required contract does not exist, change OpenAPI through the approved
  process rather than inventing an undocumented request.

Regenerate types after relevant OpenAPI changes.

## BFF and security

The frontend uses same-origin browser communication. Preserve:

```text
credentials: same-origin
```

or the current equivalent repository implementation.

OAuth access and refresh tokens never enter JavaScript-accessible application state.

Do not store OAuth tokens, session secrets, personal responses, or sensitive user
state in `localStorage` or `sessionStorage`.

Do not trust browser-supplied identity. State-changing authenticated flows must
preserve the approved CSRF and BFF design. Do not weaken security controls for
development convenience.

## Component design

Prefer simple, cohesive components with a clear reason to exist. Prefer composition
over large configurable components when complexity genuinely exists.

Avoid:

- components with many unrelated responsibilities;
- boolean-prop proliferation;
- deeply coupled presentation, business, and network logic;
- premature generic component abstractions;
- components extracted only because a file reached an arbitrary number of lines.

Extract reusable components when behavior or presentation is genuinely repeated,
the abstraction has a meaningful semantic role, or separation improves testing or
comprehension.

Do not introduce compound-component or context patterns for simple leaf components.

Use React 19 idioms compatible with the current project. Do not mechanically
optimize everything with `memo`, `useMemo`, or `useCallback`. Use them when identity
stability or measured render cost justifies them.

Prefer deriving values during render over synchronizing derived state through
`useEffect`. Effects are for synchronization with external systems, not general
application control flow.

## TypeScript

Keep strict TypeScript clean.

Prefer:

- meaningful domain and UI types;
- discriminated unions for closed UI states;
- exhaustive handling where it improves safety;
- inference where obvious;
- explicit types at important boundaries;
- `unknown` over `any` for untrusted values;
- immutable values where practical.

Avoid:

- `any`;
- unsafe casts used to silence errors;
- non-null assertions without invariant evidence;
- duplicating generated OpenAPI types;
- complex type-level programming with little product value.

Clarity wins.

## Product-facing UI states

Every server-backed product slice must deliberately model meaningful user-visible
states. Depending on the use case, consider:

- initial or loading;
- success;
- empty;
- stale but usable;
- degraded or unavailable;
- rejected business action;
- authentication required;
- technical failure.

Do not collapse valid product states into generic error screens. Do not hide stale
or degraded states when the approved contract makes them explicit. Do not invent UI
states that the product or application contract does not support.

## Error handling

Transport errors, Problem Details, and application error codes must be converted
into explicit frontend behavior.

Do not expose stack traces, internal persistence details, provider payloads, raw
exception messages, tokens, or credentials.

User-visible Spanish copy belongs to the UI boundary. Stable backend error codes
remain machine-facing contract values.

## TanStack Query

Use TanStack Query for server interactions. Preserve the repository's deliberate
retry policy. Do not blindly enable retries for mutations or hide unavailable server
state through aggressive retry.

Choose query keys that are deterministic, serializable, and scoped to the actual
server state. Invalidate or refetch only state affected by a mutation. Avoid broad
cache invalidation merely because it is easy.

Do not introduce optimistic updates unless their failure and reconciliation
semantics are understood.

## Tailwind CSS

Use the current Tailwind CSS 4 CSS-first configuration and preserve the existing
Vite integration. Do not introduce a JavaScript Tailwind configuration merely
because an older guide expects one.

Prefer consistent semantic styling. Avoid uncontrolled arbitrary values, repeated
near-identical utility blobs when a real reusable component exists, and hard-coded
visual decisions scattered across many files.

Do not create an elaborate design-token hierarchy, component library, or theming
system until repetition and product design justify it. Refactor recurring visual
primitives incrementally when they emerge.

## Accessibility

Accessibility is an MVP quality gate. Use native HTML semantics first.

Prefer:

- `<button>` for actions;
- `<a>` for navigation;
- labelled form controls;
- correct heading hierarchy;
- landmarks;
- meaningful alternative text;
- visible focus;
- keyboard-operable interaction.

Use ARIA only when native semantics are insufficient.

Every interactive feature must consider keyboard access, focus order, focus movement
after significant UI transitions, accessible name, screen-reader meaning, error and
status announcements, contrast, zoom, responsive behavior, and reduced-motion or
other user preferences where applicable.

Automated axe checks are necessary but not sufficient. Do not claim accessibility
purely because axe reports zero violations.

## Responsive design

The product is mobile-first. Preserve usability from the repository's supported
minimum viewport, currently 320px unless the authoritative source changes it.

Do not implement desktop-only interaction patterns. Review important screens at
representative phone and desktop sizes. Avoid horizontal overflow except for
deliberately scrollable content. Touch targets must remain usable.

## Testing philosophy

Test behavior that users and contracts can observe. The primary frontend testing
seams are:

- pure utility behavior;
- component behavior;
- product-facing API mapping;
- route and page behavior;
- critical browser journey;
- accessibility.

For behavior changes and bug fixes, prefer:

```text
failing test
-> minimal implementation
-> passing test
-> refactor
```

Do not force ceremonial TDD for generated files, formatting, documentation, or
mechanical configuration changes.

## Vitest and React Testing Library

Use component tests for behavior that does not require a real browser layout engine.

Prefer accessible queries in this order:

1. role;
2. label;
3. visible text or another semantic query;
4. test IDs only as a last resort.

Use realistic user interactions. Prefer `userEvent` or the project's current
equivalent over direct low-level event firing when representing user behavior.

Assert what the user can see, enabled or disabled state, navigation, visible result,
accessible status, and observable callback or network outcome.

Avoid testing component internals, testing private hooks through implementation
details, asserting child props merely to prove wiring, snapshot-heavy suites, DOM
structure assertions unrelated to behavior, mocking React, and excessive child
mocking.

Use a fresh TanStack Query client where isolation requires it. Keep retries disabled
in tests unless a test explicitly verifies retry semantics.

## Playwright

Use Playwright for behavior that requires the actual browser or complete user
journey. Use the project's existing TypeScript Playwright setup. Do not introduce
Python Playwright, Cypress, or a second browser-testing harness.

Prefer resilient selectors based on accessible roles and names. Avoid arbitrary
sleeps. Use auto-waiting and observable conditions. Critical browser tests should
cover meaningful journeys rather than every component permutation.

The repository browser tests and axe-core checks remain authoritative.

## API testing versus browser testing

Do not use E2E tests to prove behavior that a focused component or API-mapping test
can prove faster. Use the lowest-cost test that gives the required confidence.

Conversely, do not pretend JSDOM proves layout, CSS behavior, responsive behavior,
browser navigation, focus behavior requiring real browser integration, real
accessibility contrast, or complete BFF journeys. Use Playwright for those.

## Performance

Correctness and clarity come before speculative micro-optimization.

Apply high-impact React performance practices, especially avoiding unnecessary
waterfalls, accidental duplicate requests, unnecessary large dependencies, render
loops and effects, and lazy-loading genuinely heavy route or feature code where
justified. Keep server-state ownership clear.

Do not introduce memoization or complex caching without evidence. When performance
matters, measure before and after.

Generic Vercel recommendations that depend on Next.js or RSC do not apply to this
SPA.

## External skills

The following external skills may provide complementary guidance:

- `vercel-react-best-practices`;
- `vercel-composition-patterns`;
- `react-testing`;
- `frontend-accessibility-best-practices`;
- `tdd`.

They are advisory. This project skill and repository sources of truth always take
precedence.

Examples:

- Next.js and RSC advice from a generic React skill does not apply.
- SWR recommendations do not replace TanStack Query.
- A generic component pattern does not justify premature compound components.
- Coverage percentages from an external testing skill do not override the project's
  coverage policy.
- Generic accessibility guidance complements but does not replace the repository's
  Playwright, axe, and manual accessibility gates.

## Change workflow

For a material frontend change:

1. Inspect `git status`.
2. Read the issue and acceptance criteria when available.
3. Identify the approved use case or journey.
4. Read the minimum relevant sources.
5. Inspect existing UI, API layer, and tests.
6. Determine state ownership.
7. Determine the cheapest meaningful test seam.
8. Add or adapt a failing test first when appropriate.
9. Make the smallest coherent implementation.
10. Run focused tests.
11. Refactor only while tests stay green.
12. Run typecheck and lint.
13. Run browser and accessibility checks when affected.
14. Apply the risk-based local validation policy in
    `docs/development/delivery-lifecycle.md`: run the smallest meaningful frontend and
    related contract checks locally, then use the applicable pull-request gates and
    trusted `main` integration CI. Do not run backend, persistence, identity,
    container, or provider suites unless the change or a concrete cross-boundary
    risk affects them.
15. Inspect the complete diff.
16. Update frontend README and docs when behavior, architecture, or setup changes.
17. Report changes, evidence, risks, and intentionally deferred improvements.

Do not combine unrelated visual or architectural refactoring with the requested
change.

## Review checklist

Before declaring frontend work complete, inspect for:

- violation of the SPA or BFF architecture;
- accidental Next.js, SSR, or RSC introduction;
- raw API client calls from UI;
- generated OpenAPI types manually edited;
- duplicated handwritten transport contracts;
- incorrect TanStack Query ownership;
- duplicated server state in component or global state;
- unnecessary global stores;
- `useEffect` used for derived state or control flow;
- excessive memoization;
- boolean-prop or component API complexity;
- premature abstractions;
- inaccessible interactions;
- missing keyboard or focus behavior;
- invalid or redundant ARIA;
- missing loading, empty, error, or degraded states;
- incorrect browser routing;
- token or session data in browser storage;
- weak TypeScript casts or `any`;
- tests coupled to implementation;
- overuse of snapshots or test IDs;
- browser behavior falsely tested only in JSDOM;
- missing Playwright evidence;
- responsiveness regressions;
- Tailwind repetition or premature design-system abstraction;
- unnecessary dependencies;
- accidental performance regressions;
- unrelated cleanup.

Prefer simple, explicit, accessible, and maintainable UI code over clever framework
patterns.
