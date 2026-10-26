# syntax=docker/dockerfile:1.20@sha256:26147acbda4f14c5add9946e2fd2ed543fc402884fd75146bd342a7f6271dc1d
# check=error=true

ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.7.5@sha256:37dbaf6f0722c9ec246335f36e1ef8b2e6cb960f7c27e0d8c615121a3d475a85
ARG JDK_IMAGE=eclipse-temurin:25.0.4_7-jdk-noble@sha256:534968c051301957beae735e7ba1db54d99ddecf08746d3b9d4f318cc132dbc3

FROM ${KEYCLOAK_IMAGE} AS keycloak-base

FROM --platform=$BUILDPLATFORM ${JDK_IMAGE} AS mapper-build
WORKDIR /mapper
# Compile against the exact runtime's APIs; no downloaded or bundled dependencies.
COPY --from=keycloak-base /opt/keycloak/lib/lib/main /keycloak-libs
COPY docker/keycloak/remember-me-mapper/ ./
RUN mkdir classes \
    && javac --release 21 -cp '/keycloak-libs/*' -d classes src/com/videogameplatform/keycloak/RememberMeMapper.java \
    && jar --create --file remember-me-mapper.jar --date=2026-10-25T00:00:00Z -C classes . -C resources .

FROM keycloak-base

ARG KEYCLOAK_EVENT_METRICS_USER_ENABLED=false

# Compile the private runtime capabilities before start --optimized. Compose bounds
# event collection; the image contains no realm, environment-specific state or secret.
ENV KC_CACHE=local \
    KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_EVENT_METRICS_USER_ENABLED=${KEYCLOAK_EVENT_METRICS_USER_ENABLED}

COPY --from=mapper-build --chown=keycloak:keycloak /mapper/remember-me-mapper.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

# One theme source, with brand and font assets copied from their canonical frontend owners.
# The build context is the repository root in both local and private-dev Compose.
COPY --chown=keycloak:keycloak docker/keycloak/themes/gameometro /opt/keycloak/themes/gameometro
COPY --chown=keycloak:keycloak frontend/src/shared/brand/gameometro-logo.svg frontend/src/shared/brand/gameometro-mark.svg /opt/keycloak/themes/gameometro/login/resources/img/
COPY --chown=keycloak:keycloak frontend/public/assets/fonts/mona-sans-latin.woff2 frontend/public/assets/fonts/monasans-OFL.txt /opt/keycloak/themes/gameometro/login/resources/fonts/
