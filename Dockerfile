FROM eclipse-temurin:25-jre-noble
WORKDIR /app

# Run as non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

# Copy the pre-built JAR from the GitHub Actions runner
COPY target/*.jar app.jar

ENV PORT=8080
EXPOSE 8080

# Tuned JVM parameters for 512MB RAM constraints
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseSerialGC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-jar", "app.jar"]