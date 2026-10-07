ARG JAVA_IMAGE=eclipse-temurin:17.0.9_9-jdk-jammy
ARG MAVEN_IMAGE=maven:3.9.9-eclipse-temurin-17
ARG TOMCAT10_IMAGE=tomcat:10.1-jdk17-temurin-jammy
ARG TOMCAT9_IMAGE=tomcat:9.0-jdk17-temurin-jammy

# ---- build: all lab applications (the extension is downloaded from its GitHub release below) ------
FROM ${MAVEN_IMAGE} AS build
WORKDIR /src
# Build context is the repository root (see lab/docker-compose.yml). The extension module stays in
# the reactor (the parent pom lists it) but is excluded from the build: the lab runs the released jar.
COPY pom.xml ./
COPY appserver-deployment appserver-deployment
COPY lab/apps lab/apps
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -pl '!appserver-deployment' package

# ---- the OTel Java agent, shared by all servers ---------------------------------------------------
FROM curlimages/curl:8.10.1 AS agent
ARG OTEL_JAVA_AGENT_VERSION=2.21.0
RUN curl --fail --silent --show-error --location \
      "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar" \
      --output /tmp/opentelemetry-javaagent.jar

# ---- the extension, downloaded from the GitHub release and checked against its SHA256SUMS --------
# Override to test another release: docker compose build --build-arg EXTENSION_VERSION=0.1.1
FROM curlimages/curl:8.10.1 AS extension
ARG EXTENSION_VERSION=0.1.0
ARG EXTENSION_RELEASE_URL=https://github.com/antreklabs/iyzitrace-otel-java-extensions/releases/download/v${EXTENSION_VERSION}
WORKDIR /tmp/ext
RUN curl --fail --silent --show-error --location --remote-name \
      "${EXTENSION_RELEASE_URL}/appserver-deployment-extension-${EXTENSION_VERSION}.jar" \
 && curl --fail --silent --show-error --location --remote-name \
      "${EXTENSION_RELEASE_URL}/SHA256SUMS" \
 && sha256sum -c SHA256SUMS \
 && mv "appserver-deployment-extension-${EXTENSION_VERSION}.jar" appserver-deployment-extension.jar

# ---- WildFly 27: orders.war, inventory.war, shop.ear, reports.jar -------------------------------
FROM ${JAVA_IMAGE} AS wildfly

ARG WILDFLY_VERSION=27.0.1.Final

# JAVA_OPTS (agent, extension and all -Dotel.* settings) is set in docker-compose.yml.
ENV WILDFLY_HOME=/opt/wildfly \
    JAVA_HOME=/opt/java/openjdk \
    JBOSS_HOME=/opt/wildfly \
    LAUNCH_JBOSS_IN_BACKGROUND=true

RUN set -eux; \
    apt-get update; \
    apt-get install --no-install-recommends --yes ca-certificates curl; \
    rm -rf /var/lib/apt/lists/*; \
    curl --fail --silent --show-error --location \
      "https://github.com/wildfly/wildfly/releases/download/${WILDFLY_VERSION}/wildfly-${WILDFLY_VERSION}.tar.gz" \
      --output /tmp/wildfly.tar.gz; \
    mkdir -p /opt/otel/extensions; \
    tar -xzf /tmp/wildfly.tar.gz -C /opt; \
    mv "/opt/wildfly-${WILDFLY_VERSION}" "${WILDFLY_HOME}"; \
    useradd --system --home-dir "${WILDFLY_HOME}" --shell /usr/sbin/nologin wildfly; \
    chown -R wildfly:wildfly "${WILDFLY_HOME}" /opt/otel; \
    rm /tmp/wildfly.tar.gz

COPY --from=agent --chown=wildfly:wildfly /tmp/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar
COPY --from=extension --chown=wildfly:wildfly /tmp/ext/appserver-deployment-extension.jar /opt/otel/extensions/
COPY --from=build --chown=wildfly:wildfly \
     /src/lab/apps/orders/target/orders.war \
     /src/lab/apps/inventory/target/inventory.war \
     /src/lab/apps/shop/shop-ear/target/shop.ear \
     /src/lab/apps/reports/target/reports.jar \
     /opt/wildfly/standalone/deployments/

USER wildfly
WORKDIR ${WILDFLY_HOME}

EXPOSE 8080 8443 9990

CMD ["/opt/wildfly/bin/standalone.sh", "-c", "standalone.xml", "-b", "0.0.0.0", "-bmanagement", "0.0.0.0"]

# ---- Tomcat 10.1 (jakarta.servlet): catalog.war, pricing.war -------------------------------------
FROM ${TOMCAT10_IMAGE} AS tomcat10
COPY --from=agent /tmp/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar
COPY --from=extension /tmp/ext/appserver-deployment-extension.jar /opt/otel/extensions/
COPY --from=build \
     /src/lab/apps/tomcat/catalog/target/catalog.war \
     /src/lab/apps/tomcat/pricing/target/pricing.war \
     /usr/local/tomcat/webapps/

# ---- Tomcat 9 (javax.servlet): legacy.war --------------------------------------------------------
FROM ${TOMCAT9_IMAGE} AS tomcat9
COPY --from=agent /tmp/opentelemetry-javaagent.jar /opt/opentelemetry-javaagent.jar
COPY --from=extension /tmp/ext/appserver-deployment-extension.jar /opt/otel/extensions/
COPY --from=build /src/lab/apps/tomcat9/legacy/target/legacy.war /usr/local/tomcat/webapps/
