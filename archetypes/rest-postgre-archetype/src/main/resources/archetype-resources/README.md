#set($hash = '#')
$hash ${entity} API

This project exposes a REST API to manage **${entity}** resources. It is generated from the `${artifactId}` Maven archetype and follows **Hexagonal / Clean Architecture**, the **CQRS pattern**, and an **API‑First** approach driven by OpenAPI.

---

$hash$hash 🧱 Architecture Overview

$hash$hash$hash ✅ Hexagonal / Clean Architecture

- Domain‑centric design with clear separation between **domain**, **application**, and **infrastructure** layers.
- All dependencies point inward, enabling easier testing and long‑term maintainability.

$hash$hash$hash ✅ CQRS (Command Query Responsibility Segregation)

- Read operations (queries) are handled separately via dedicated handlers.
- Separation between intent (query object) and execution (handler).

$hash$hash$hash ✅ API‑First + OpenAPI

- Contract defined in `src/main/resources/static/openapi/${uncapitalizedEntity}-api.yaml`.
- Interfaces & DTOs are generated automatically by the OpenAPI Maven plugin.

---

$hash$hash 🚀 How to Run the Application

$hash$hash$hash 🧪 Prerequisites

- **Java 21+**
- **Maven 3.8+**
- **PostgreSQL 16+** (local or Docker)
- (Optional) **Docker** for running the app in a container

$hash$hash$hash 🔧 Build and Start

    $hash Compile and run tests
    ./mvnw clean verify

    $hash Run the application (Spring Boot)
    ./mvnw spring-boot:run

`spring-boot:run` will start the API on **port ${serverPort}**.

$hash$hash$hash 🐳 Docker‑Compose

A ready‑to‑use **docker‑compose.yml** is provided to launch the application together with PostgreSQL:

    docker compose up --build

- API: <http://localhost:${serverPort}>
- PostgreSQL: `localhost:5432/${dbName}`

---

$hash$hash ⚙️ Database – PostgreSQL Configuration

The application uses an **PostgreSQL**, preconfigured for use during runtime.

$hash$hash$hash 🔐 Connection parameters:

- **URL**: `jdbc:postgresql://localhost:5432/${dbName}`
- **Username**: `${dbUser}`
- **Password**: ${dbPassword}

---

$hash$hash 📑 Logging – Configuration and Output

Logging is configured in:

src/main/resources/logback-spring.xml


- **Log directory**: `logs/`
- **Log filename**: `${uncapitalizedEntity}.log`
- Rotation: daily, 7 days history.

---

$hash$hash 🔍 API Endpoints

| Resource           | URL                                               | Description          |
| ------------------ |---------------------------------------------------| -------------------- |
| Application URL    | `http://localhost:${serverPort}`                  | Root                 |
| Swagger UI         | `http://localhost:${serverPort}/swagger-ui.html`  | Interactive API docs |
| Actuator – Health  | `http://localhost:${serverPort}/actuator/health`  | Health probe         |
| Actuator – Info    | `http://localhost:${serverPort}/actuator/info`    | Build / env info     |
| Actuator – Metrics | `http://localhost:${serverPort}/actuator/metrics` | Micrometer metrics   |

---

$hash$hash 🧪 Testing Strategy – Test Pyramid

1. **Unit Tests** – JUnit5 + Mockito
2. **Integration Tests** – H2 in memory database
3. **Acceptance Tests** – Cucumber / Postman (optional)
4. **Mutation Tests** – PIT (see details below)

---

$hash$hash$hash Mutation Testing with PIT

> Mutation testing inserts tiny faults (mutants) into the code to verify that the existing test suite detects them, providing a far more reliable quality signal than line or branch coverage alone.

- **Tool**: [PIT](https://pitest.org/) `1.19.5` (latest stable, June 2025).
- **Plugin**: `pitest-maven` with automatic JUnit5 detection.
- **Threshold**: The CI build fails if the mutation score drops below **85%** (configurable).
- **Incremental analysis**: Enabled – only mutated code since the last successful build is analysed, making execution ∼5–10× faster on PRs.

$hash$hash$hash$hash Quick Start (local)

```bash
$hash Run PIT with the configuration in pom.xml
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage

$hash Or skip history for a full run
./mvnw -DwithHistory=false test-compile org.pitest:pitest-maven:mutationCoverage
```

HTML reports are generated in `target/pit-reports/<timestamp>/index.html`.

---

$hash$hash 📦 Running Postman Acceptance Tests with Newman

$hash$hash$hash 🧰 Prerequisites
Make sure Node.js and Newman are installed:

```bash
npm install -g newman newman-reporter-html
```

$hash$hash$hash ▶️ Run Tests

```bash
newman run postman/${uncapitalizedEntity}-acceptance.postman_collection.json \
  --reporters cli,html \
  --reporter-html-export postman/report.html
```

$hash$hash$hash 📄 Output

- Terminal summary of all test cases
- HTML report generated in `postman/report.html`

---

$hash$hash 📁 Project Structure

```
${uncapitalizedEntity}/
├── src/
│ ├── main/
│ │ ├── domain/ $hash Business models, exceptions
│ │ ├── application/ $hash Services, query handlers
│ │ ├── infrastructure/ $hash Adapters: REST, DB, mappers
│ │ └── resources/
│ └── test/
│ ├── unit/ $hash Unit tests
│ ├── integration/ $hash Integration tests (Testcontainers)
│ └── features/ $hash Cucumber & Postman
├── postman/ $hash Postman collections & reports
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

$hash$hash 🧩 Tools & Frameworks Used

- SpringBoot 3.4.4
- SpringDoc OpenAPI
- JUnit5, Mockito, Cucumber, Postman
- **PostgreSQL** (production & tests)
- Testcontainers‑JUnit for DB integration tests

---

$hash$hash 🔗 Useful Commands

| Purpose                | Command                               |
| ---------------------- | ------------------------------------- |
| Build & unit tests     | `./mvnw clean verify`                 |
| Run Spring Boot        | `./mvnw spring-boot:run`              |
| Build Docker image     | `docker build -t ${uncapitalizedEntity}-api .` |
| Start all with Compose | `docker compose up --build`           |
| Tail logs              | `tail -f logs/${uncapitalizedEntity}.log`      |

---