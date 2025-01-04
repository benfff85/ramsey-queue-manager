# Use a Maven base image for building the application
FROM maven:3.9-eclipse-temurin-21 AS build

# Set the working directory
WORKDIR /app

# Copy only the Maven build file for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy the source code after caching dependencies
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests

# Use a smaller JRE image for runtime
FROM eclipse-temurin:21-jre-alpine AS final

# Set the working directory
WORKDIR /app

# Copy the built JAR file from the build stage
COPY --from=build /app/target/ramsey-queue-manager-*.jar /app/ramsey-queue-manager.jar

# Add a non-root user and switch to it
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

# Specify the command to run the application with JAVA_OPTS from the environment
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/ramsey-queue-manager.jar"]
