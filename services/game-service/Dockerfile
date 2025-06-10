FROM maven:3.9.9-eclipse-temurin-21 AS builder

WORKDIR /app

COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline

COPY src ./src
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:21-jre

# Drop root
RUN adduser --system --uid 1001 spring
USER spring

WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

EXPOSE ${serverPort}
ENTRYPOINT ["java", "-jar", "app.jar"]
