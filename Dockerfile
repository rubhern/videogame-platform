# syntax=docker/dockerfile:1.20@sha256:26147acbda4f14c5add9946e2fd2ed543fc402884fd75146bd342a7f6271dc1d

ARG NODE_IMAGE=node:24.19.0-bookworm-slim@sha256:3638d9a6fe4030bd716be989438248074489337ba3275657f93595428be4fc03
ARG JDK_IMAGE=eclipse-temurin:25.0.4_7-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3
ARG JRE_IMAGE=eclipse-temurin:25.0.4_7-jre-alpine-3.22@sha256:824157b4a5a674632b3464eea9cc47beaf73629c727ec676b400292fe391b471

FROM --platform=$BUILDPLATFORM ${NODE_IMAGE} AS frontend-build

WORKDIR /workspace

COPY package.json package-lock.json ./
COPY frontend/package.json frontend/package.json

RUN --mount=type=cache,target=/root/.npm \
    npm ci --ignore-scripts

COPY docs/architecture/api/openapi.yaml docs/architecture/api/openapi.yaml
COPY frontend frontend

RUN node --version \
    && npm --version \
    && npm run frontend:generate-api \
    && npm run frontend:build

FROM --platform=$BUILDPLATFORM ${JDK_IMAGE} AS backend-build

ARG SOURCE_REVISION=local-development

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY docs/architecture/api/openapi.yaml docs/architecture/api/openapi.yaml
COPY --from=frontend-build /workspace/frontend/dist frontend/dist

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress \
      -Pwith-frontend,production-image \
      -DskipTests \
      -Dsource.revision="${SOURCE_REVISION}" \
      clean package

FROM ${JRE_IMAGE} AS runtime

ARG APPLICATION_VERSION=0.7.3-SNAPSHOT
ARG SOURCE_REVISION=local-development
ARG SOURCE_URL=https://github.com/rubhern/videogame-platform

LABEL org.opencontainers.image.title="VideoGame Platform" \
      org.opencontainers.image.description="VideoGame Platform frontend, same-origin BFF/API and Spring Boot modular monolith" \
      org.opencontainers.image.source="${SOURCE_URL}" \
      org.opencontainers.image.url="${SOURCE_URL}" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.version="${APPLICATION_VERSION}" \
      org.opencontainers.image.vendor="VideoGame Platform"

RUN addgroup -S -g 10001 application \
    && adduser -S -D -H -u 10001 -G application application

WORKDIR /application

COPY --from=backend-build --chown=10001:10001 \
    /workspace/backend/target/videogame-platform-backend-*.jar \
    /application/application.jar

USER 10001:10001

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/application/application.jar"]
