# 📝 MVP Functional Requirements – Videogame Platform

> Scope: minimal set needed to go live and validate product/market fit.

| ID | User Story                                                                                                 | Acceptance Criteria | Priority |
|----|------------------------------------------------------------------------------------------------------------|---------------------|----------|
| AUTH-001 | *As a* guest user *I want* to register with email/password or Google OAuth so that I can create an account | – Registration form validates email format & password strength. – Confirmation email with one-click link. – Reject duplicate emails. – Stores password hashed (argon2). | Must |
| AUTH-002 | *As a* registered user *I want* to log in so that I can access personalised features                       | – OIDC session cookie issued. – 3 failed attempts → captcha. – “Remember me” 30-day token. | Must |
| RATE-001 | *As a* logged-in gamer *I want* to rate a videogame 1-10 stars so that my opinion is counted               | – Hover + click UI updates instantly (optimistic). – Only one rating per user per game; update allowed. – Average rating recalculated in ≤200 ms. | Must |
| SRCH-001 | *As a* visitor *I want* to search for a videogame by title, genre, platform or release year                | – Search bar auto-suggests top 5 titles. – Results paginated 20 per page. – Response time < 150 ms P95 under 500 RPS. | Must |
| REV-001 | *As a* logged-in gamer *I want* to write a textual review for a game so others can read it                 | – Rich-text editor (markdown). – Length 50-2000 chars. – Profanity filter rejects offensive words. – Each user can edit/delete their own review. | Should |
| HOME-001 | *As a* visitor *I want* a home page that showcases new videogame launches so I can discover recent titles  | – Carousel of latest 10 releases (sorted by launch_date desc). – “View all” link leads to `/new-releases` list. – Launch data pulled from external API (e.g., IGDB) every 6 h. | Must |
