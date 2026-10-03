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

# Set environment variable for dynamic port
ENV SERVER_PORT=${PORT:-8080}

# Expose the port
EXPOSE $SERVER_PORT

# Set the entrypoint to use the dynamic port
ENTRYPOINT ["java", "-jar", "app.jar"]
