## MERMAID DIAGRAM

```mermaid
C4Context
title Video-Game Platform – System Context

    Person(webUser, "User (Web)", "Navigates from browser")
    Person(mobileUser, "User (Mobile)", "Navigates from smartphone / tablet")
    System_Boundary(videoGamePlatform, "Video-Game Platform") {
        Container(apiGw,       "API Gateway",        "NGINX / Kong",        "Routes and rate-limits calls")
        Container(keycloak,    "Identity Provider",  "Keycloak",            "Auth / token introspection")
        Container_Ext(igdb,    "IGDB API",           "REST",                "Third-party game metadata")
        Boundary(appLayer, "Application Services") {
            Container(allSvcs, "All Micro-services", "Logical")
        }
    }

    Rel(webUser,  apiGw,  "HTTPS")
    Rel(mobileUser, apiGw, "HTTPS")
    Rel(apiGw, keycloak, "JWT introspection")
    Rel(apiGw, allSvcs,  "REST")
    Rel_R(allSvcs, igdb,  "pulls", "HTTPS")
```

## PLANTUML DIAGRAM

```puml
@startuml
!includeurl https://raw.githubusercontent.com/plantuml-stdlib/C4-PlantUML/master/C4_Context.puml
LAYOUT_WITH_LEGEND()

Person(webUser,    "Player (Web)")
Person(mobileUser, "Player (Mobile)")

System_Boundary(videoGame, "Video-Game Platform") {
    System(apiGw,   "API Gateway", "NGINX / Kong")
    System(keycloak,"Keycloak",    "OIDC")
    System(kafka,   "Kafka",       "Event Bus")
    System_Ext(igdb,"IGDB API",    "Third-party")
    Boundary(svcs, "Application Services") {
        System(userSvc,   "User Svc",   "Spring Boot")
        System(gameSvc,   "Game Svc",   "Spring Boot")
        System(ratingSvc, "Rating Svc", "Spring Boot")
        System(reviewSvc, "Review Svc", "Spring Boot")
        System(launchSvc, "Launch Svc", "Spring Boot")
    }
}

Rel(webUser,    apiGw, "HTTPS")
Rel(mobileUser, apiGw, "HTTPS")
Rel(apiGw, keycloak, "JWT introspection")
Rel(apiGw, svcs,   "REST / JSON")
Rel_L(ratingSvc, kafka, "rating-created")
Rel_L(reviewSvc, kafka, "review-created")
Rel_R(launchSvc, igdb,  "pulls metadata")

SHOW_LEGEND()
@enduml

```