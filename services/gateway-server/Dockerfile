FROM gradle:9.2.1-jdk21 AS build
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle :services:gateway-server:bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN groupadd --system cashflow && useradd --system --gid cashflow cashflow
WORKDIR /app
COPY --from=build /workspace/services/gateway-server/build/libs/gateway-server-0.0.1-SNAPSHOT.jar app.jar
USER cashflow
EXPOSE 5001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
