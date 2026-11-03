# syntax=docker/dockerfile:1

# Image du backend, construite en deux étapes : compilation sur le JDK 25, exécution sur le
# JRE 25 seul. La clé d'API n'est jamais dans l'image : elle est passée au lancement.
#   docker build -t dashboard-foot-backend .
#   docker run -p 8080:8080 -e FOOTBALL_DATA_API_TOKEN=... dashboard-foot-backend

# ─── Compilation ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# Dépendances d'abord : cette couche reste en cache tant que le pom.xml ne change pas.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# Les tests tournent en CI (mvn verify), pas pendant la construction de l'image.
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/dashboard-foot-backend-*.jar app.jar

# ─── Exécution ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine

RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar app.jar
USER app

EXPOSE 8080

# Même endpoint que le contrôle de santé de l'hébergeur ; il n'appelle pas football-data.org.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1

# La JVM dimensionne son tas sur la mémoire du conteneur (offres gratuites : 256 à 512 Mo).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
