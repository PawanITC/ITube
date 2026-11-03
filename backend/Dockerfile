# syntax=docker/dockerfile:1

# ITube backend: Spring Boot 4 (Java 21) + FFmpeg for the video transcode worker.
# Build from the repo root:  docker build -t itube-backend backend

# ---- Build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /workspace

# Maven wrapper + pom first, so the dependency layer is cached until pom.xml changes.
# mvnw is not executable in git, hence `sh mvnw`.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sh mvnw -B -q dependency:go-offline

COPY src/ src/
RUN sh mvnw -B -DskipTests package \
 && cp target/*.jar /workspace/app.jar

# ---- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre-noble

# FFmpeg creates the 360p/720p renditions (itube.video.ffmpeg-path, default `ffmpeg` on PATH)
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

# Fixed numeric UID/GID so Kubernetes can verify runAsNonRoot
RUN groupadd --system --gid 10001 itube \
 && useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin itube

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

USER 10001:10001

# Heap sized from the container memory limit; leave room for metaspace, threads and buffers.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=25 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp" \
    FFMPEG_PATH=ffmpeg

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
