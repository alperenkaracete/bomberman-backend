# Multi-stage build for Java 21
FROM maven:3.9.4-amazoncorretto-21 AS builder

WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the jar file from the builder stage
COPY --from=builder /app/target/*.jar app.jar

# The port is resolved at runtime from $PORT (see application.properties)
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
