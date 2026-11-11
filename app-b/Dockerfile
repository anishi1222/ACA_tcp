FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -Dmaven.test.skip=true

FROM eclipse-temurin:25-jre
RUN groupadd --gid 10001 app \
 && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/target/app.jar app.jar
USER 10001:10001

# HTTP port (context path: /api/tcp)
EXPOSE 8080

# Host name of the TCP server (container app A) is read from the TCP_SERVER_HOST environment variable.
ENTRYPOINT ["java", "-jar", "app.jar"]
