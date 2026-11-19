# ITube API gateway: nginx routing /api/** to the services (see templates/default.conf.template).
# Unprivileged image: listens on 8080 as UID 101, so it runs under Kubernetes Pod Security "restricted".
FROM nginxinc/nginx-unprivileged:1.30-alpine

# Upstreams (host:port). Defaults are the Kubernetes Service names; docker-compose overrides them.
# Rate limits are per client IP; a load test from a single machine raises them (loadtest/README.md).
ENV IDENTITY_UPSTREAM=identity:8080 \
    CATALOG_UPSTREAM=catalog:8080 \
    ENGAGEMENT_UPSTREAM=engagement:8080 \
    PAYMENTS_UPSTREAM=payments:8080 \
    API_RATE_LIMIT=30r/s \
    AUTH_RATE_LIMIT=10r/m \
    CONNECTIONS_PER_IP=100

COPY templates/ /etc/nginx/templates/

EXPOSE 8080
