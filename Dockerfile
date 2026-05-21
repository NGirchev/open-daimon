# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

ARG APP_VERSION=1.1.1-SNAPSHOT

# Copy pom.xml and all modules
COPY pom.xml .
COPY opendaimon-common/pom.xml ./opendaimon-common/
COPY opendaimon-spring-ai/pom.xml ./opendaimon-spring-ai/
COPY opendaimon-mcp/pom.xml ./opendaimon-mcp/
COPY opendaimon-spring-boot-starter/pom.xml ./opendaimon-spring-boot-starter/
COPY opendaimon-ui/pom.xml ./opendaimon-ui/
COPY opendaimon-rest/pom.xml ./opendaimon-rest/
COPY opendaimon-telegram/pom.xml ./opendaimon-telegram/
COPY opendaimon-gateway-mock/pom.xml ./opendaimon-gateway-mock/
COPY opendaimon-app/pom.xml ./opendaimon-app/

# Copy source code
COPY opendaimon-common/src ./opendaimon-common/src
COPY opendaimon-spring-ai/src ./opendaimon-spring-ai/src
COPY opendaimon-mcp/src ./opendaimon-mcp/src
COPY opendaimon-ui/src ./opendaimon-ui/src
COPY opendaimon-rest/src ./opendaimon-rest/src
COPY opendaimon-telegram/src ./opendaimon-telegram/src
COPY opendaimon-gateway-mock/src ./opendaimon-gateway-mock/src
COPY opendaimon-app/src ./opendaimon-app/src

# Build project
RUN mvn -Drevision=${APP_VERSION} clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache nodejs npm \
    && npm install -g @modelcontextprotocol/server-filesystem@0.2.0 \
    && npm install -g zod-to-json-schema@3.23.5 \
    && npm cache clean --force

ARG APP_VERSION=1.1.1-SNAPSHOT

# Copy JAR from build stage
COPY --from=build /app/opendaimon-app/target/opendaimon-app-${APP_VERSION}.jar app.jar

RUN mkdir -p /app/mcp-filesystem

# Expose port
EXPOSE 8080

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
