# Use the Temurin base image for Java 21
FROM eclipse-temurin:21-jdk-alpine AS build

# Set the working directory
WORKDIR /app

# Copy the Maven or Gradle build file and the source code
COPY pom.xml .
COPY src ./src

# Install Maven or Gradle (if necessary) and build the application
RUN apk update && apk add --no-cache maven && mvn clean package -DskipTests

# Create a new stage for the final image
FROM eclipse-temurin:21-jdk-alpine AS final

# Set the working directoryins
WORKDIR /app

# Copy the built JAR file from the build stage
COPY --from=build /app/target/ramsey-queue-manager-*.jar /app/ramsey-queue-manager.jar

# Expose the port on which the app will run
EXPOSE 8080

# Specify the command to run the application
ENTRYPOINT ["java", "-jar", "/app/ramsey-queue-manager.jar"]
