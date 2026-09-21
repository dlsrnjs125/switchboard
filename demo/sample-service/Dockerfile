FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S -g 10001 switchboard \
    && adduser -S -D -H -u 10001 -G switchboard switchboard

WORKDIR /app
COPY demo/sample-service/build/install/sample-service/ /app/

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
USER 10001:10001
ENTRYPOINT ["/app/bin/sample-service"]

