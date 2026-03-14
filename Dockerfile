# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and all modules
COPY pom.xml .
COPY opendaimon-common/pom.xml ./opendaimon-common/
COPY opendaimon-spring-ai/pom.xml ./opendaimon-spring-ai/
COPY opendaimon-ui/pom.xml ./opendaimon-ui/
COPY opendaimon-rest/pom.xml ./opendaimon-rest/
COPY opendaimon-telegram/pom.xml ./opendaimon-telegram/
COPY opendaimon-gateway-mock/pom.xml ./opendaimon-gateway-mock/
COPY opendaimon-app/pom.xml ./opendaimon-app/

# Copy source code
COPY opendaimon-common/src ./opendaimon-common/src
COPY opendaimon-spring-ai/src ./opendaimon-spring-ai/src
COPY opendaimon-ui/src ./opendaimon-ui/src
COPY opendaimon-rest/src ./opendaimon-rest/src
COPY opendaimon-telegram/src ./opendaimon-telegram/src
COPY opendaimon-gateway-mock/src ./opendaimon-gateway-mock/src
COPY opendaimon-app/src ./opendaimon-app/src

# Build project
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/opendaimon-app/target/opendaimon-app-1.0-SNAPSHOT.jar app.jar

# Expose port
EXPOSE 8080

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]

