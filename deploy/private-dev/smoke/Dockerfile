# syntax=docker/dockerfile:1.20@sha256:26147acbda4f14c5add9946e2fd2ed543fc402884fd75146bd342a7f6271dc1d

FROM mcr.microsoft.com/playwright@sha256:dcc5531e97840b9b5e794f2814476b21571c5124a3fca2267d73041f56e7580e

WORKDIR /smoke

COPY package.json package-lock.json ./

RUN npm ci --ignore-scripts \
    && npm cache clean --force

COPY deployment-smoke.mjs releases-outcome.mjs ./

USER pwuser

ENTRYPOINT ["node", "/smoke/deployment-smoke.mjs"]
