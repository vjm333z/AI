# Stage 1: Build
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: Run
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /build/target/recall-ai-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p /app/data
ENTRYPOINT ["java", "-jar", "app.jar"]
