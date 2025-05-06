# ${entity} API

This project exposes a REST API to manage **${entity}** resources. It is generated from the `${artifactId}` Maven archetype and follows **Hexagonal / Clean Architecture**, the **CQRS pattern**, and an **API‑First** approach driven by OpenAPI.

---

## 🧱 Architecture Overview

### ✅ Hexagonal / Clean Architecture

- Domain‑centric design with clear separation between **domain**, **application**, and **infrastructure** layers.
- All dependencies point inward, enabling easier testing and long‑term maintainability.

### ✅ CQRS (Command Query Responsibility Segregation)

- Read operations (queries) are handled separately via dedicated handlers.
- Separation between intent (query object) and execution (handler).

### ✅ API‑First + OpenAPI

- Contract defined in `src/main/resources/static/openapi/${uncapitalizedEntity}-api.yaml`.
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

`spring-boot:run` will start the API on **port ${serverPort}**.

### 🐳 Docker‑Compose

A ready‑to‑use **docker‑compose.yml** is provided to launch the application together with PostgreSQL:

    docker compose up --build

- API: <http://localhost:${serverPort}>
- PostgreSQL: `localhost:5432/${dbName}`

---

## ⚙️ Database – PostgreSQL Configuration

The application uses an **PostgreSQL**, preconfigured for use during runtime.

### 🔐 Connection parameters:

- **URL**: `jdbc:postgresql://localhost:5432/${dbName}`
- **Username**: `${dbUser}`
- **Password**: ${dbPassword}

---

## 📑 Logging – Configuration and Output

Logging is configured in:

src/main/resources/logback-spring.xml


- **Log directory**: `logs/`
- **Log filename**: `${uncapitalizedEntity}.log`
- Rotation: daily, 7 days history.

---

## 🔍 API Endpoints

| Resource           | URL                                               | Description          |
| ------------------ |---------------------------------------------------| -------------------- |
| Application URL    | `http://localhost:${serverPort}`                  | Root                 |
| Swagger UI         | `http://localhost:${serverPort}/swagger-ui.html`  | Interactive API docs |
| Actuator – Health  | `http://localhost:${serverPort}/actuator/health`  | Health probe         |
| Actuator – Info    | `http://localhost:${serverPort}/actuator/info`    | Build / env info     |
| Actuator – Metrics | `http://localhost:${serverPort}/actuator/metrics` | Micrometer metrics   |

---

## 🧪 Testing Strategy – Test Pyramid

1. **Unit Tests** – JUnit5 + Mockito
2. **Integration Tests** – H2 in memory database
3. **Acceptance Tests** – Cucumber / Postman (optional)

---

## 📦 Running Postman Acceptance Tests with Newman

### 🧰 Prerequisites
Make sure Node.js and Newman are installed:

```bash
npm install -g newman newman-reporter-html
```

### ▶️ Run Tests

```bash
newman run postman/${uncapitalizedEntity}-acceptance.postman_collection.json \
  --reporters cli,html \
  --reporter-html-export postman/report.html
```

### 📄 Output

- Terminal summary of all test cases
- HTML report generated in `postman/report.html`

---

## 📁 Project Structure

```
${uncapitalizedEntity}/
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
| Build Docker image     | `docker build -t ${uncapitalizedEntity}-api .` |
| Start all with Compose | `docker compose up --build`           |
| Tail logs              | `tail -f logs/${uncapitalizedEntity}.log`      |

---