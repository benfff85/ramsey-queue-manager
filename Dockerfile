# Use a Maven base image for building the application
FROM maven:3.9-eclipse-temurin-25 AS build

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
FROM eclipse-temurin:25-jre AS final

# Set the working directory
WORKDIR /app

# Copy the built JAR file from the build stage
COPY --from=build /app/target/ramsey-queue-manager-*.jar /app/ramsey-queue-manager.jar

# JVM memory and container awareness settings
ENV JAVA_OPTS="-XX:InitialRAMPercentage=75.0 -XX:MaxRAMPercentage=75.0 -XX:+UseZGC -XX:+UseCompactObjectHeaders --enable-native-access=ALL-UNNAMED"

# Add a non-root user and switch to it
RUN groupadd -r appgroup && useradd -r -g appgroup appuser
USER appuser

# Expose the port on which the app will run
EXPOSE 8080

# Add a health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Specify the command to run the application with JAVA_OPTS from the environment
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/ramsey-queue-manager.jar"]
