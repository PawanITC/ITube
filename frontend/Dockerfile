# syntax=docker/dockerfile:1

# ITube frontend: Vite build served by unprivileged nginx on 8080.
# nginx also proxies /api/ to the backend Service, like the Vite dev proxy, so the browser sees one origin.
# Build from the repo root:  docker build -t itube-frontend frontend

# ---- Build stage -------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /app

# Dependency layer, cached until package.json / package-lock.json change
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- Runtime stage -----------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.30-alpine

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/build/ /usr/share/nginx/html/

# The base image already runs as UID 101 (nginx); keep it numeric for runAsNonRoot checks
USER 101
EXPOSE 8080
