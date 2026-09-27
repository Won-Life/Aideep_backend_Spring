FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
ARG DD_JAVA_AGENT_VERSION=1.66.0
ADD https://repo.maven.apache.org/maven2/com/datadoghq/dd-java-agent/${DD_JAVA_AGENT_VERSION}/dd-java-agent-${DD_JAVA_AGENT_VERSION}.jar /app/dd-java-agent.jar
COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle
COPY src/main ./src/main
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/build/libs/app.jar ./app.jar
COPY --from=build --chmod=0444 /app/dd-java-agent.jar ./dd-java-agent.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-javaagent:/app/dd-java-agent.jar", "-jar", "/app/app.jar"]
