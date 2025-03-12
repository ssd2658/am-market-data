# Build stage
FROM maven:3.8.4-openjdk-17-slim AS builder

# Set working directory
WORKDIR /app

# Copy Maven settings first
COPY settings.xml /root/.m2/settings.xml

# Copy Maven files first for better caching
COPY pom.xml .
COPY market-data-app/pom.xml market-data-app/
COPY market-data-service/pom.xml market-data-service/
COPY market-data-kafka/pom.xml market-data-kafka/
COPY market-data-scraper/pom.xml market-data-scraper/
COPY market-data-common/pom.xml market-data-common/
COPY market-data-api/pom.xml market-data-api/

# Copy source code
COPY market-data-app/src market-data-app/src/
COPY market-data-service/src market-data-service/src/
COPY market-data-kafka/src market-data-kafka/src/
COPY market-data-scraper/src market-data-scraper/src/
COPY market-data-common/src market-data-common/src/
COPY market-data-api/src market-data-api/src/

# Build the application with GitHub credentials
ARG GITHUB_PACKAGES_USERNAME
ARG GITHUB_PACKAGES_TOKEN
ENV GITHUB_PACKAGES_USERNAME=${GITHUB_PACKAGES_USERNAME}
ENV GITHUB_PACKAGES_TOKEN=${GITHUB_PACKAGES_TOKEN}

# Build the application
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Copy the built artifact from builder stage
COPY --from=builder /app/market-data-app/target/*.jar app.jar

# Install curl for healthcheck
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/* && \
    # Set timezone
    ln -sf /usr/share/zoneinfo/Asia/Kolkata /etc/localtime

# Add healthcheck
HEALTHCHECK --interval=30s --timeout=3s \
  CMD curl -f http://localhost:8084/actuator/health || exit 1

# Expose the application port
EXPOSE 8084

# Set entrypoint
ENTRYPOINT ["java", "-jar", "app.jar"]
