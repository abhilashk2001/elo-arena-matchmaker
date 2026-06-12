# syntax=docker/dockerfile:1

# Build stage: compile and package with the Maven wrapper on JDK 21.
# Dependencies are resolved in their own layer so source-only changes do not
# re-download the world on every rebuild.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -DskipTests package

# Run stage: slim JRE with only the built jar.
FROM eclipse-temurin:21-jre AS run
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
