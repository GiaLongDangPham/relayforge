# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace
COPY src/ReceiverApplication.java src/ReceiverApplication.java
RUN mkdir out && javac --add-modules jdk.httpserver -d out src/ReceiverApplication.java

FROM eclipse-temurin:25-jre-noble
RUN groupadd --system receiver \
    && useradd --system --gid receiver --home-dir /app --shell /usr/sbin/nologin receiver
WORKDIR /app
COPY --from=build --chown=receiver:receiver /workspace/out /app

USER receiver
EXPOSE 8081
ENTRYPOINT ["java", "--add-modules", "jdk.httpserver", "-cp", "/app", "ReceiverApplication"]

