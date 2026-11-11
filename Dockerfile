# One image definition for the three deployables. Build the jars first (./mvnw package), then:
#   docker build --build-arg APP=api -t jobradar-api .
FROM eclipse-temurin:25-jre

# APP is one of: api, ingestion-worker, tailoring-worker
ARG APP
WORKDIR /app
COPY ${APP}/target/${APP}.jar app.jar

# Only the tailoring-worker renders PDFs: it needs the Typst CLI and the CV font (Carlito).
ARG TYPST_VERSION=0.15.1
RUN if [ "$APP" = "tailoring-worker" ]; then \
      apt-get update && apt-get install -y --no-install-recommends curl xz-utils fonts-crosextra-carlito \
      && ARCH="$(uname -m)" \
      && curl -fsSL "https://github.com/typst/typst/releases/download/v${TYPST_VERSION}/typst-${ARCH}-unknown-linux-musl.tar.xz" \
         | tar -xJ --strip-components=1 -C /usr/local/bin "typst-${ARCH}-unknown-linux-musl/typst" \
      && apt-get purge -y curl xz-utils && rm -rf /var/lib/apt/lists/*; \
    fi

# Run as an unprivileged user.
RUN useradd --system --no-create-home jobradar
USER jobradar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
