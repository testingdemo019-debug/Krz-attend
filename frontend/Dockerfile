# syntax=docker/dockerfile:1
# KRZ Attend web image (F12): the built React app behind nginx (service "web"). Build context = the project root,
# which holds docs/api/openapi.json (the API types) and deploy/nginx (the server configuration):
#   docker build -f frontend/Dockerfile -t krz-attend/web:<VERSION> .
# Lint, unit and E2E tests run on the host before the build; this build type-checks and bundles.

FROM node:24.14.0-alpine AS build
WORKDIR /build/frontend
# Dependencies first, so they are cached until the lock file changes.
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY docs/api/openapi.json /build/docs/api/openapi.json
COPY frontend/ ./
# prebuild self-hosts MediaPipe (public/mediapipe, model checked by SHA-256); then the type check and the bundle.
RUN npm run gen:api && npm run build

FROM nginxinc/nginx-unprivileged:1.30.0-alpine
# Only our server configuration: the image's default site is removed.
USER root
RUN rm -f /etc/nginx/conf.d/default.conf
USER 101
COPY deploy/nginx/nginx.conf /etc/nginx/nginx.conf
COPY deploy/nginx/security-headers.conf deploy/nginx/proxy.conf /etc/nginx/
COPY --chmod=755 deploy/nginx/40-go2rtc-auth.sh /docker-entrypoint.d/40-go2rtc-auth.sh
COPY --from=build /build/frontend/dist/ /usr/share/nginx/html/
# 80 redirects to 443 (TLS). Docker lets an unprivileged process bind ports below 1024 inside the container.
EXPOSE 80 443
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q --spider --no-check-certificate https://127.0.0.1/healthz || exit 1
