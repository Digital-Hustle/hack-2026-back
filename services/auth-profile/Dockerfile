FROM gradle:9.2.1-jdk21 AS build
USER root
WORKDIR /workspace
RUN chown gradle:gradle /workspace
USER gradle
COPY --chown=gradle:gradle . .
RUN gradle :services:auth-profile:bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN groupadd --system cashflow && useradd --system --gid cashflow cashflow
WORKDIR /app
COPY --from=build /workspace/services/auth-profile/build/libs/auth-profile-0.1.0.jar app.jar
USER cashflow
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
