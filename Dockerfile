# syntax=docker/dockerfile:1.7
# One image, two roles (SPRING_PROFILES_ACTIVE=api | worker). Built from source, so a fresh clone
# needs only `docker compose up`.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY queue-service/pom.xml queue-service/
COPY chaos-harness/pom.xml chaos-harness/
COPY queue-service/src queue-service/src
COPY chaos-harness/src chaos-harness/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests -pl queue-service -am package

# glibc-based JRE (Ubuntu), not Alpine: libfaketime needs glibc for the clock-skew run (design D14).
FROM eclipse-temurin:21-jre-noble
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl libfaketime \
 && rm -rf /var/lib/apt/lists/* \
 && ln -s "$(find /usr/lib -name 'libfaketime.so.1' | head -n1)" /usr/local/lib/libfaketime.so.1
WORKDIR /app
COPY --from=build /src/queue-service/target/queue-service.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
