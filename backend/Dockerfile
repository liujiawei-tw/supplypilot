# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY backend/.mvn/ backend/.mvn/
COPY backend/mvnw backend/pom.xml ./backend/
WORKDIR /workspace/backend
RUN chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests dependency:go-offline

COPY backend/src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -DskipTests package \
    && test -s target/supply-pilot-api-0.0.1-SNAPSHOT.jar \
    && jar tf target/supply-pilot-api-0.0.1-SNAPSHOT.jar | grep -q '^BOOT-INF/'

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S -g 10001 supplypilot && adduser -S -D -H -u 10001 -G supplypilot supplypilot \
    && mkdir -p /app/data/documents /app/data/demo-documents \
    && chown -R supplypilot:supplypilot /app
COPY --from=build --chown=10001:10001 /workspace/backend/target/supply-pilot-api-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:InitialRAMPercentage=25.0 -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/urandom"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
