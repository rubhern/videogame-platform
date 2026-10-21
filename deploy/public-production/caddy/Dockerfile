# syntax=docker/dockerfile:1
# Public-production edge (#275): the stock Caddy release plus one owner-approved module,
# mholt/caddy-ratelimit, pinned to an exact commit. Built, inspected and scanned only by
# scripts/validate-dependency-image.sh and published by trusted main; never on the host.
# Go verifies every module of the resolved graph against the public checksum database.
# scripts/check-dependency-image-content.py owns the module policy, including the security
# override below, which is removed as soon as stock Caddy ships the fixed version.
ARG CADDY_BUILDER_IMAGE=caddy:2.11.7-builder-alpine@sha256:d718a1d1431403c4a46698f33ad1c0f9dd5dd7fe2bae654d1027d275b6fb5fd7
ARG CADDY_IMAGE=caddy:2.11.7-alpine@sha256:d8542f48d34a9cf4e4c11a478865229840e87e4c96ea3f439101f31a5d35f75f

FROM ${CADDY_BUILDER_IMAGE} AS build
ARG CADDY_VERSION=v2.11.7
ARG CADDY_RATELIMIT_MODULE=github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db
# Caddy's public HTTP/2 server is golang.org/x/net/http2; v0.59.0 has reachable denial-of-service
# advisories (GO-2026-6603, -6610, -6611, -6612, -6617), fixed in v0.60.0.
ARG CADDY_NET_OVERRIDE=golang.org/x/net=golang.org/x/net@v0.60.0
ENV CGO_ENABLED=0 \
    GOFLAGS=-trimpath \
    GOPROXY=https://proxy.golang.org \
    GONOSUMDB= \
    GOPRIVATE= \
    GOSUMDB=sum.golang.org
RUN xcaddy build "$CADDY_VERSION" --output /out/caddy --with "$CADDY_RATELIMIT_MODULE" \
        --replace "$CADDY_NET_OVERRIDE" \
    && /out/caddy list-modules | grep -qx 'http.handlers.rate_limit'

FROM ${CADDY_IMAGE}
# Only the binary changes; the stock image's user, paths and Caddyfile command are kept.
COPY --from=build /out/caddy /usr/bin/caddy
