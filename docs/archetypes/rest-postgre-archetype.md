# ⛵️ rest‑postgre‑archetype

A Maven archetype that spins up a production‑ready **Spring Boot REST API** backed by **PostgreSQL**, following Hexagonal Architecture & CQRS.  
Generate fully wired services in seconds with consistent code style, Docker assets, Integration and Acceptance tests and OpenAPI contracts.

---

## 1 What is a Maven archetype?

A *Maven archetype* is a project template.  
It packages:

* a directory structure
* source files with `${placeholders}`
* a descriptor (`archetype-metadata.xml`) that tells Maven how to replace those placeholders when you *generate* a new project.

Think of it as `spring‑init` on steroids and totally customisable for your organisation.

---

## 2 What does **rest‑postgre‑archetype** give you?

* **Spring Boot 3.4.x** starter with MapStruct, SpringDoc.
* **PostgreSQL** datasource (H2 in tests).
* **Dockerfile** and **docker‑compose.yml** for local / CI runs.
* Hexagonal folder layout (`domain/`, `application/`, `infrastructure/`).
* Pre‑wired **OpenAPI** spec & generated interfaces.
* Opinionated logging, health checks and metrics.

---

## 3 Installing the archetype

```bash
# inside the mono‑repo root
cd rest-postgre-archetype
mvn clean install         # puts the archetype jar in your ~/.m2 repository
mvn archetype:update-local-catalog
```

## 4 Generating a new service from the archetype

```bash
mvn archetype:generate
  -DarchetypeGroupId=com.acme
  -DarchetypeArtifactId=rest-postgre-archetype
  -DarchetypeVersion=1.0.0
  -DarchetypeCatalog=remote
  -DarchetypeRepository=http://localhost:8081/repository/archetypes-releases
```
Maven will prompt for each property (see table below).
Everything is interactive unless you pass -Dproperty=value on the CLI.

## 5 Archetype variables (archetype-metadata.xml)

| Placeholder         | Meaning                                                                                                                      |
|---------------------|------------------------------------------------------------------------------------------------------------------------------|
| entity              | Business aggregate root; used for class names (GameService, GameRepository) and OpenAPI tags. Make sure to write capitalized |
| uncapitalizedEntity | Same as entity but uncapitalized                                                                                             |
| serverPort          | Port Spring Boot listens on (and is published in docker-compose.yml)                                                         |
| dbName              | PostgreSQL database name for dev/test.                                                                                       |
| dbUser              | DB username injected in application.yaml & compose file.                                                                     |
| dbPassword          | DB password (change it!).                                                                                                    |

All of them live under <requiredProperties> in archetype-metadata.xml and are marked as filtered in the descriptor, so they propagate to .java, .yaml, .md, Docker assets, etc.                                                     

## 6 Example: non‑interactive generation

```bash
mvn archetype:generate \
  -DarchetypeGroupId=com.acme \
  -DarchetypeArtifactId=rest-postgre-archetype \
  -DarchetypeVersion=1.0.0 \
  -DarchetypeCatalog=local \
  -DgroupId=com.acme.games \
  -DartifactId=game-service \
  -Dversion=1.0.0-SNAPSHOT \
  -Dpackage=com.acme.games \
  -Dentity=Game \
  -DserverPort=8082 \
  -DdbName=gamesdb \
  -DdbUser=gameuser \
  -DdbPassword=changeit \
  -B
```
The -B flag is batch mode—ideal for CI/CD pipelines.