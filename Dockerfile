FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src

RUN chmod +x gradlew \
    && ./gradlew bootJar warmupModel -x test --no-daemon

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

ENV DJL_CACHE_DIR=/app/.djl-cache
ENV APP_UPLOAD_DIR=/app/uploads
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/uploads /app/.djl-cache

COPY --from=build /app/build/libs/*.jar app.jar
COPY --from=build /app/build/djl-cache/ /app/.djl-cache/

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=5 \
  CMD curl -f http://localhost:8080/login || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
