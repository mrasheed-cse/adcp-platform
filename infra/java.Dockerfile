# Builds any Java service:  docker build -f infra/java.Dockerfile --build-arg SERVICE=trafficking-api .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY services/${SERVICE}/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline
COPY services/${SERVICE}/src ./src
# Tests run in CI (they read shared fixtures from contracts/, outside this build context).
RUN mvn -B -q package -DskipTests && cp target/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home app
COPY --from=build /app.jar /app.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
