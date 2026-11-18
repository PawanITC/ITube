# syntax=docker/dockerfile:1

# One Dockerfile for every ITube service. Pick the service with --build-arg SERVICE:
#   docker build --build-arg SERVICE=catalog-service  -t itube-catalog  backend
#   docker build --build-arg SERVICE=transcode-worker -t itube-transcode backend
# SERVICE is one of: identity-service, catalog-service, engagement-service, payments-service, transcode-worker

# ---- Build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build
ARG SERVICE
WORKDIR /workspace

COPY . .
# Builds the service and the shared modules it depends on (-am). The cache mount keeps downloaded Maven
# dependencies between builds on the same machine. mvnw is not executable in git, hence `sh mvnw`.
RUN --mount=type=cache,target=/root/.m2 \
    test -n "$SERVICE" || { echo "Pass --build-arg SERVICE=<module>" >&2; exit 1; } \
 # A checkout made before .gitattributes existed can have CRLF line endings, which sh can't run
 && sed -i 's/\r$//' mvnw \
 && sh mvnw -B -q -pl "$SERVICE" -am -DskipTests package \
 && cp "$SERVICE"/target/*.jar /workspace/app.jar

# ---- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre-noble
ARG SERVICE

# Only the transcode worker needs FFmpeg (360p/720p renditions); the other images stay small
RUN if [ "$SERVICE" = "transcode-worker" ]; then \
      apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*; \
    fi

# Fixed numeric UID/GID so Kubernetes can verify runAsNonRoot
RUN groupadd --system --gid 10001 itube \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin itube

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

USER 10001:10001

# Heap sized from the container memory limit; leave room for metaspace, threads and buffers.
# All services listen on 8080 inside the container (local `java -jar` runs use 8081-8085).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=25 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp" \
    SERVER_PORT=8080 \
    FFMPEG_PATH=ffmpeg

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
