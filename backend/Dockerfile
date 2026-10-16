# syntax=docker/dockerfile:1
# KRZ Attend backend image (B6). Build context = the project root, which holds database/ (migrations and seeds)
# and docs/test-vectors/ (crypto tests):
#   docker build -f backend/Dockerfile -t krz-attend/backend:<VERSION> .
# The integration tests need Docker (Testcontainers) and run on the host before the build with `mvnw verify`;
# this build runs the formatting and lint checks, the unit tests and the architecture rules (DECISIONS D-046).

FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
# unzip: without it the wrapper fetches the .tar.gz, whose checksum is not the pinned one (the .zip's).
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /build/backend
# Wrapper and pom first, so the dependency download is cached until the pom changes.
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn/ .mvn/
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline
COPY database/ /build/database/
COPY docs/test-vectors/ /build/docs/test-vectors/
COPY backend/ ./
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw \
    && ./mvnw -B -ntp verify -DexcludedGroups=integration -Djacoco.skip=true \
    && cp target/krz-attend-backend-*.jar /build/application.jar
WORKDIR /build
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN groupadd --system --gid 10001 krz \
    && useradd --system --uid 10001 --gid krz --no-create-home --shell /usr/sbin/nologin krz
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
# 8080 API (reached through nginx only), 8081 health (internal).
EXPOSE 8080 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8081/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Duser.timezone=UTC", "-Dfile.encoding=UTF-8", \
    "-Dsun.net.client.defaultConnectTimeout=5000", "-Dsun.net.client.defaultReadTimeout=20000", "-jar", "application.jar"]
