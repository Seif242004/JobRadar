# One image definition for the three deployables. Build the jars first (./mvnw package), then:
#   docker build --build-arg APP=api -t jobradar-api .
FROM eclipse-temurin:25-jre

# APP is one of: api, ingestion-worker, tailoring-worker
ARG APP
WORKDIR /app
COPY ${APP}/target/${APP}.jar app.jar

# Run as an unprivileged user.
RUN useradd --system --no-create-home jobradar
USER jobradar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
