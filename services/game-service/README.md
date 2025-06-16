# Game API

This project exposes a REST API to manage **Game** resources. It is generated from the `game-service` Maven archetype and follows **Hexagonal / Clean Architecture**, the **CQRS pattern**, and an **API‑First** approach driven by OpenAPI.

---

## 🧱 Architecture Overview

### ✅ Hexagonal / Clean Architecture

- Domain‑centric design with clear separation between **domain**, **application**, and **infrastructure** layers.
- All dependencies point inward, enabling easier testing and long‑term maintainability.

### ✅ CQRS (Command Query Responsibility Segregation)

- Read operations (queries) are handled separately via dedicated handlers.
- Separation between intent (query object) and execution (handler).

### ✅ API‑First + OpenAPI

- Contract defined in `src/main/resources/static/openapi/game-api.yaml`.
- Interfaces & DTOs are generated automatically by the OpenAPI Maven plugin.

---

## 🚀 How to Run the Application

### 🧪 Prerequisites

- **Java 21+**
- **Maven 3.8+**
- **PostgreSQL 16+** (local or Docker)
- (Optional) **Docker** for running the app in a container

### 🔧 Build and Start

    # Compile and run tests
    ./mvnw clean verify

    # Run the application (Spring Boot)
    ./mvnw spring-boot:run

`spring-boot:run` will start the API on **port 8090**.

### 🐳 Docker‑Compose

A ready‑to‑use **docker‑compose.yml** is provided to launch the application together with PostgreSQL:

    docker compose up --build

- API: <http://localhost:8090>
- PostgreSQL: `172.22.233.21:30050/gamedb`

---

## ⚙️ Database – PostgreSQL Configuration

The application uses an **PostgreSQL**, preconfigured for use during runtime.

### 🔐 Connection parameters:

- **URL**: `jdbc:postgresql://172.22.233.21:30050/gamedb`
- **Username**: `user`
- **Password**: pass

---

## 📑 Logging – Configuration and Output

Logging is configured in:

src/main/resources/logback-spring.xml


- **Log directory**: `logs/`
- **Log filename**: `game.log`
- Rotation: daily, 7 days history.

---

## 🔍 API Endpoints

| Resource           | URL                                               | Description          |
| ------------------ |---------------------------------------------------| -------------------- |
| Application URL    | `http://localhost:8090`                  | Root                 |
| Swagger UI         | `http://localhost:8090/swagger-ui.html`  | Interactive API docs |
| Actuator – Health  | `http://localhost:8090/actuator/health`  | Health probe         |
| Actuator – Info    | `http://localhost:8090/actuator/info`    | Build / env info     |
| Actuator – Metrics | `http://localhost:8090/actuator/metrics` | Micrometer metrics   |

---

## 🧪 Testing Strategy – Test Pyramid

1. **Unit Tests** – JUnit5 + Mockito
2. **Integration Tests** – H2 in memory database
3. **Acceptance Tests** – Cucumber / Postman (optional)
4. **Mutation Tests** – PIT (see details below)

---

### Mutation Testing with PIT

> Mutation testing inserts tiny faults (mutants) into the code to verify that the existing test suite detects them, providing a far more reliable quality signal than line or branch coverage alone.

- **Tool**: [PIT](https://pitest.org/) `1.19.5` (latest stable, June 2025).
- **Plugin**: `pitest-maven` with automatic JUnit5 detection.
- **Threshold**: The CI build fails if the mutation score drops below **85%** (configurable).
- **Incremental analysis**: Enabled – only mutated code since the last successful build is analysed, making execution ∼5–10× faster on PRs.

#### Quick Start (local)

```bash
# Run PIT with the configuration in pom.xml
./mvnw test-compile org.pitest:pitest-maven:mutationCoverage

# Or skip history for a full run
./mvnw -DwithHistory=false test-compile org.pitest:pitest-maven:mutationCoverage
```

HTML reports are generated in `target/pit-reports/<timestamp>/index.html`.

---

## 📦 Running Postman Acceptance Tests with Newman

### 🧰 Prerequisites
Make sure Node.js and Newman are installed:

```bash
npm install -g newman newman-reporter-html
```

### ▶️ Run Tests

```bash
newman run postman/game-acceptance.postman_collection.json \
  --reporters cli,html \
  --reporter-html-export postman/report.html
```

### 📄 Output

- Terminal summary of all test cases
- HTML report generated in `postman/report.html`

---

## 📁 Project Structure

```
game/
├── src/
│ ├── main/
│ │ ├── domain/ # Business models, exceptions
│ │ ├── application/ # Services, query handlers
│ │ ├── infrastructure/ # Adapters: REST, DB, mappers
│ │ └── resources/
│ └── test/
│ ├── unit/ # Unit tests
│ ├── integration/ # Integration tests (Testcontainers)
│ └── features/ # Cucumber & Postman
├── postman/ # Postman collections & reports
├── docker-compose.yml
├── pom.xml
└── README.md
```

---

## 🧩 Tools & Frameworks Used

- SpringBoot 3.4.4
- SpringDoc OpenAPI
- JUnit5, Mockito, Cucumber, Postman
- **PostgreSQL** (production & tests)
- Testcontainers‑JUnit for DB integration tests

---

## 🔗 Useful Commands

| Purpose                | Command                               |
| ---------------------- | ------------------------------------- |
| Build & unit tests     | `./mvnw clean verify`                 |
| Run Spring Boot        | `./mvnw spring-boot:run`              |
| Build Docker image     | `docker build -t game-api .` |
| Start all with Compose | `docker compose up --build`           |
| Tail logs              | `tail -f logs/game.log`      |

---