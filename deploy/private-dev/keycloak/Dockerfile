# syntax=docker/dockerfile:1.20@sha256:26147acbda4f14c5add9946e2fd2ed543fc402884fd75146bd342a7f6271dc1d
# check=error=true

ARG KEYCLOAK_IMAGE=quay.io/keycloak/keycloak:26.7.4@sha256:82a77884f3af238beab1e7afd63b5f530e1b5c0590bd7aa60b40a40463e29b2c

FROM ${KEYCLOAK_IMAGE}

ARG KEYCLOAK_EVENT_METRICS_USER_ENABLED=false

# Compile the private runtime capabilities before start --optimized. Compose bounds
# event collection; the image contains no realm, environment-specific state or secret.
ENV KC_CACHE=local \
    KC_DB=postgres \
    KC_HEALTH_ENABLED=true \
    KC_METRICS_ENABLED=true \
    KC_EVENT_METRICS_USER_ENABLED=${KEYCLOAK_EVENT_METRICS_USER_ENABLED}

RUN /opt/keycloak/bin/kc.sh build

# One theme source, with brand/font/art assets copied from their canonical frontend owners.
# The build context is the repository root in both local and private-dev Compose.
COPY --chown=keycloak:keycloak docker/keycloak/themes/gameometro /opt/keycloak/themes/gameometro
COPY --chown=keycloak:keycloak frontend/src/shared/brand/gameometro-logo.svg frontend/src/shared/brand/gameometro-mark.svg /opt/keycloak/themes/gameometro/login/resources/img/
COPY --chown=keycloak:keycloak frontend/public/assets/night-hero.webp frontend/public/assets/night-hero-980.webp /opt/keycloak/themes/gameometro/login/resources/img/
COPY --chown=keycloak:keycloak frontend/public/assets/fonts/mona-sans-latin.woff2 frontend/public/assets/fonts/instrument-serif-italic-latin.woff2 frontend/public/assets/fonts/ibm-plex-mono-400-latin.woff2 frontend/public/assets/fonts/*OFL.txt /opt/keycloak/themes/gameometro/login/resources/fonts/
