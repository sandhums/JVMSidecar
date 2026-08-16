# CQL / ELM JVM sidecar — build context is this repo root.
#
#   docker build -t atrius/cql-sidecar:staging .
#
# Local `docker run` keeps SIDECAR_ENV=development (admin cache-clear open, no FHIR allowlist).
# Staging / production MUST set:
#   SIDECAR_ENV=staging|production
#   SIDECAR_ADMIN_TOKEN=<secret>
#   SIDECAR_ALLOWED_FHIR_BASES=http://hfs:8080,http://hts:8090,http://kr:8079

FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN mkdir -p /out \
 && mvn -q -DskipTests package \
 && cp target/JVMsidecar-*-SNAPSHOT.jar /out/cql-sidecar.jar

FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -g 1000 sidecar && useradd -u 1000 -g sidecar -m sidecar
WORKDIR /app
COPY --from=build /out/cql-sidecar.jar /app/cql-sidecar.jar
ENV SIDECAR_PORT=8088 \
    SIDECAR_ENV=development \
    JAVA_OPTS="-Xms256m -Xmx1024m"
USER sidecar
EXPOSE 8088
HEALTHCHECK --interval=30s --timeout=5s --start-period=25s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SIDECAR_PORT}/health" || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/cql-sidecar.jar"]
