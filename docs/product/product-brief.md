# Product Brief — VideoGame Platform

- **Status:** Draft
- **Version:** 0.1
- **Owner:** Unassigned
- **Last updated:** 2026-07-22
- **Phase:** 0 — Product alignment
- **Primary source:** [VideoGame Platform vision](../reference/video-game-platform-vision.pdf)
- **Source reviewed:** 2026-07-22

> This document separates current direction from decisions that still require
> validation. Text marked **Working hypothesis** must not be treated as evidence.

## 1. Executive summary

VideoGame Platform is intended to become a web product where people can discover
video games, understand recent and upcoming releases, access information aggregated
from authorised sources, and maintain their own ratings. The long-term vision
includes richer catalogue, personal, community, recommendation, and analytical
capabilities, but the first product increment should remain deliberately small.

The current direction is to start with one complete user journey: discover recent or
upcoming releases, open a game page, and record a personal rating. This direction is
not yet approved as the MVP; Phase 0 must confirm the target user, the problem, the
value proposition, and the boundary of the first validation.

## 2. Product purpose

The product aims to give video-game players a clear place to:

- discover relevant releases;
- consult consistent, useful information about a game;
- express and retain a personal rating;
- eventually build a richer history of their relationship with video games.

The product should solve a focused user problem before expanding into reviews,
social features, recommendations, advanced rankings, or comprehensive catalogue
management.

## 3. Learning purpose

The primary purpose of the initiative is to improve solution-architecture and
technical-leadership capability through the progressive design and construction of
a complete platform. It should develop practical experience in:

- turning a broad vision into validated product increments;
- software architecture, Domain-Driven Design, and hexagonal architecture;
- delivering thin vertical slices from user interface to operations;
- modelling a catalogue without coupling the domain to an external provider;
- designing explicit, stable API, event, and data contracts;
- distributed systems, asynchronous processing, and event-driven architecture when
  justified;
- applying security, testing, observability, and automation from the start;
- persistence, identity, resilience, performance, and cloud-native delivery;
- technical team leadership and architectural decision-making;
- documenting significant decisions and evolving architecture incrementally.

Architectural learning has explicit priority, but it must support product outcomes or
a deliberate learning objective. It is not justification for artificial complexity.

## 4. Long-term vision

VideoGame Platform may evolve into a trusted product for video-game discovery,
tracking, ratings, and analysis. Potential future capabilities include:

- a broad game and release catalogue;
- personal libraries, lists, and following;
- written reviews and community interaction;
- professional or external ratings where licensing permits their use;
- recommendations, rankings, statistics, and trend analysis;
- moderation and editorial administration.

This is directional context, not committed scope. Each capability must earn its place
through evidence, value, cost, risk, and operational viability.

## 5. Context and opportunity

**Status: Requires research.**

Information about releases, game details, ratings, and personal tracking is available
through multiple existing products. The opportunity hypothesis is that a focused,
clear journey can reduce fragmentation or friction for a specific group of players.

Phase 1 must validate whether this problem is meaningful and whether the proposed
journey is sufficiently useful or differentiated from existing alternatives.

## 6. Initial product problem

**Working hypothesis:** Regular video-game players find it unnecessarily difficult to
identify relevant weekly releases, obtain concise and reliable information about
them, and keep their own opinion organised in one place.

Evidence for this statement has not yet been collected. User interviews should focus
on recent behaviour rather than stated intent.

## 7. Priority user

**Working hypothesis:** A regular player who actively checks releases and wants to
rate games they know.

Visitors, moderators, administrators, professional critics, creators, studios, and
publishers may matter later, but they should not shape the first journey unless
research changes this priority.

## 8. Initial value proposition

**Working hypothesis:** Discover the video games that matter this week, consult a
clear game page, and keep your personal rating in one place.

The value proposition needs competitor analysis and user evidence before approval.

## 9. Objectives

### Product objectives

- Validate that the selected user segment values the proposed core journey.
- Validate that useful, current catalogue data can be obtained and displayed legally.
- Define a small MVP capable of testing the riskiest product assumptions.

### Learning objectives

- Practise incremental product discovery and delivery.
- Establish clear domain boundaries without premature distribution.
- Make delivery, quality, security, and operational concerns part of each slice.

## 10. Preliminary MVP boundary

**Status: Candidate scope; not approved.**

### Candidate inclusion

- Weekly or recent releases.
- Title search.
- A game page with essential catalogue and release information.
- Registration and sign-in through an established identity approach.
- Create, change, and remove one personal rating per user and game.
- Aggregate rating and rating count.
- Import from one authorised game-data provider.
- Basic analytics and operational visibility for the core journey.

### Explicitly deferred

- Written reviews, comments, social features, and advanced moderation.
- Personal libraries, custom lists, and following.
- Recommendations and complex rankings.
- Professional reviews or third-party scores without explicit authorised access.
- Detailed editions, DLC, expansions, and exhaustive technical requirements.
- Native mobile applications.
- Multiple simultaneous game-data providers.
- Microservices, event streaming, data lakes, and multi-region deployment.

## 11. Candidate primary journey

1. A visitor opens the release view.
2. They select a game and read its game page.
3. They register or sign in when they decide to rate it.
4. They create or update their rating.
5. They see their rating and the aggregate result.

## 12. Hypotheses

The prioritised hypothesis register is maintained in
[assumptions.md](assumptions.md). The most consequential current hypotheses concern:

- the relevance of release discovery;
- the value of combining discovery, information, and personal ratings;
- the availability and licensing of external catalogue data;
- the choice of primary user segment.

## 13. Candidate success signals

Thresholds must be defined after baseline research. Candidate signals are:

- proportion of searches or release views that lead to a game page;
- proportion of registered users who rate at least one game;
- average number of games rated by an active user;
- repeat use of the release view;
- user success and observed friction in usability sessions;
- catalogue freshness and successful synchronisation rate;
- error rate across the primary journey.

Visits and registrations alone are not sufficient evidence of product value.

## 14. Risks and constraints

| Risk | Why it matters | Initial response |
|---|---|---|
| Data licensing and availability | The product may not be allowed to store or display essential data or images | Complete legal and technical provider research before catalogue implementation |
| Weak differentiation | A correct catalogue may still be irrelevant to users | Validate the problem and compare real user behaviour across alternatives |
| Scope expansion | The long-term vision can overwhelm the first validation | Approve an explicit in/out MVP boundary |
| Provider coupling | An external model could dictate the internal product model | Use internal identifiers and isolate provider-specific concepts when implementation begins |
| Premature architecture | Operational cost may grow without corresponding product value | Prefer the smallest deployable architecture that supports the approved journey |
| User-generated content | Reviews and community features introduce abuse, privacy, and moderation duties | Keep them outside the first MVP |

Known budget, staffing, delivery-date, market, privacy, and regulatory constraints have
not yet been documented.

## 15. Product and technology principles

- Start with one user and one meaningful problem.
- Deliver capabilities as complete vertical slices.
- Prefer simple, modular, observable solutions.
- Keep domain logic independent from external providers and delivery technology.
- Treat security, privacy, accessibility, testing, and operations as product quality.
- Make data provenance and synchronisation state explicit.
- Add complexity only in response to demonstrated needs.
- Introduce technology when it delivers product value or a relevant, explicit
  learning outcome.
- Record significant, durable decisions; avoid documentation ceremony.

## 16. Open questions

The live register is [open-questions.md](open-questions.md). Phase 0 cannot be approved
until its decision questions are resolved or explicitly accepted as hypotheses for
the next phase.

## 17. Approval record

| Role | Person | Decision | Date |
|---|---|---|---|
| Product owner | Unassigned | Pending | — |
| Technical lead | Unassigned | Pending | — |
