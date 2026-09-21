FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S -g 10001 switchboard \
    && adduser -S -D -H -u 10001 -G switchboard switchboard

WORKDIR /app
COPY services/control-plane/build/libs/control-plane-0.1.0-SNAPSHOT.jar application.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]

