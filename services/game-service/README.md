# Game API

This project exposes a REST API to manage **Game** resources. It is generated from the `game-service` Maven archetype and follows **Hexagonal / Clean Architecture**, the **CQRS pattern**, and an **API‑First** approach driven by OpenAPI.

---



- Domain‑centric design with clear separation between **domain**, **application**, and **infrastructure** layers.
- All dependencies point inward, enabling easier testing and long‑term maintainability.


- Read operations (queries) are handled separately via dedicated handlers.
- Separation between intent (query object) and execution (handler).


- Contract defined in `src/main/resources/static/openapi/game-api.yaml`.
- Interfaces & DTOs are generated automatically by the OpenAPI Maven plugin.

---



- **Java 21+**
- **Maven 3.8+**
- **PostgreSQL 16+** (local or Docker)
- (Optional) **Docker** for running the app in a container


    # Compile and run tests
    ./mvnw clean verify

    # Run the application (Spring Boot)
    ./mvnw spring-boot:run

`spring-boot:run` will start the API on **port 8090**.


A ready‑to‑use **docker‑compose.yml** is provided to launch the application together with PostgreSQL:

    docker compose up --build

- API: <http://localhost:8090>
- PostgreSQL: `172.22.233.21:30050/gamedb`

---


The application uses an **PostgreSQL**, preconfigured for use during runtime.


- **URL**: `jdbc:postgresql://172.22.233.21:30050/gamedb`
- **Username**: `user`
- **Password**: pass

---


Logging is configured in:

src/main/resources/logback-spring.xml


- **Log directory**: `logs/`
- **Log filename**: `game.log`
- Rotation: daily, 7 days history.

---


| Resource           | URL                                               | Description          |
| ------------------ |---------------------------------------------------| -------------------- |
| Application URL    | `http://localhost:8090`                  | Root                 |
| Swagger UI         | `http://localhost:8090/swagger-ui.html`  | Interactive API docs |
| Actuator – Health  | `http://localhost:8090/actuator/health`  | Health probe         |
| Actuator – Info    | `http://localhost:8090/actuator/info`    | Build / env info     |
| Actuator – Metrics | `http://localhost:8090/actuator/metrics` | Micrometer metrics   |

---


1. **Unit Tests** – JUnit5 + Mockito
2. **Integration Tests** – H2 in memory database
3. **Acceptance Tests** – Cucumber / Postman (optional)

---


Make sure Node.js and Newman are installed:

```bash
npm install -g newman newman-reporter-html
```


```bash
newman run postman/game-acceptance.postman_collection.json \
  --reporters cli,html \
  --reporter-html-export postman/report.html
```


- Terminal summary of all test cases
- HTML report generated in `postman/report.html`

---


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


- SpringBoot 3.4.4
- SpringDoc OpenAPI
- JUnit5, Mockito, Cucumber, Postman
- **PostgreSQL** (production & tests)
- Testcontainers‑JUnit for DB integration tests

---


| Purpose                | Command                               |
| ---------------------- | ------------------------------------- |
| Build & unit tests     | `./mvnw clean verify`                 |
| Run Spring Boot        | `./mvnw spring-boot:run`              |
| Build Docker image     | `docker build -t game-api .` |
| Start all with Compose | `docker compose up --build`           |
| Tail logs              | `tail -f logs/game.log`      |

---