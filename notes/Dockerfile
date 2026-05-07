# =========================
# Stage 1: Build the app
# =========================
FROM maven:3.9.6-eclipse-temurin-17 AS build

WORKDIR /app

# Copy pom first (for better caching)
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies first (layer caching)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the project (skip tests for faster deploy; remove if you want tests)
RUN mvn clean package -DskipTests

# =========================
# Stage 2: Run the app
# =========================
FROM eclipse-temurin:17-jdk

WORKDIR /app

# Copy built jar from builder stage
COPY --from=build /app/target/*.jar app.jar

# Render provides dynamic port
EXPOSE 8080

# Important: Render injects PORT env variable
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:-8080}"]