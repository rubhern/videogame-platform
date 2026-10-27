# syntax=docker/dockerfile:1.20@sha256:26147acbda4f14c5add9946e2fd2ed543fc402884fd75146bd342a7f6271dc1d

FROM mcr.microsoft.com/playwright@sha256:47b510dd9230bf0b9bfc9b20102241e577ea7a99334ab4c3ca19f19770e9b8ea

WORKDIR /smoke

COPY package.json package-lock.json ./

RUN npm ci --ignore-scripts \
    && npm cache clean --force

COPY deployment-smoke.mjs releases-outcome.mjs ./

USER pwuser

ENTRYPOINT ["node", "/smoke/deployment-smoke.mjs"]
