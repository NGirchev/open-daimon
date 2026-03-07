# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy pom.xml and all modules
COPY pom.xml .
COPY aibot-common/pom.xml ./aibot-common/
COPY aibot-spring-ai/pom.xml ./aibot-spring-ai/
COPY aibot-ui/pom.xml ./aibot-ui/
COPY aibot-rest/pom.xml ./aibot-rest/
COPY aibot-telegram/pom.xml ./aibot-telegram/
COPY aibot-gateway-mock/pom.xml ./aibot-gateway-mock/
COPY aibot-app/pom.xml ./aibot-app/

# Copy source code
COPY aibot-common/src ./aibot-common/src
COPY aibot-spring-ai/src ./aibot-spring-ai/src
COPY aibot-ui/src ./aibot-ui/src
COPY aibot-rest/src ./aibot-rest/src
COPY aibot-telegram/src ./aibot-telegram/src
COPY aibot-gateway-mock/src ./aibot-gateway-mock/src
COPY aibot-app/src ./aibot-app/src

# Build project
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/aibot-app/target/aibot-app-1.0-SNAPSHOT.jar app.jar

# Expose port
EXPOSE 8080

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]

