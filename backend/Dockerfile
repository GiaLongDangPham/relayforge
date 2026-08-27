# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

COPY src src
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-noble
RUN groupadd --system relayforge \
    && useradd --system --gid relayforge --home-dir /app --shell /usr/sbin/nologin relayforge
WORKDIR /app

COPY --from=build --chown=relayforge:relayforge /workspace/target/relayforge-backend-*.jar /app/relayforge.jar

USER relayforge
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/relayforge.jar"]

