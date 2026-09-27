# ---------------------------------------------------
# Stage 1: Extract JAR layers using Spring's tools
# ---------------------------------------------------
FROM eclipse-temurin:25-jre-noble AS builder
WORKDIR /builder

# Copy the pre-built JAR from your target folder
ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} application.jar

# Spring Boot built-in layer extractor (Spring Boot 3.3+ / 4.x)
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---------------------------------------------------
# Stage 2: Production Runtime Image
# ---------------------------------------------------
FROM eclipse-temurin:25-jre-noble
WORKDIR /app

# Run as non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring
USER spring:spring

# Copy each layer in order of change frequency (least frequent to most frequent)
COPY --from=builder /builder/extracted/dependencies/ ./
COPY --from=builder /builder/extracted/spring-boot-loader/ ./
COPY --from=builder /builder/extracted/snapshot-dependencies/ ./
COPY --from=builder /builder/extracted/application/ ./

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseSerialGC", \
  "-XX:+ExitOnOutOfMemoryError", \
  "org.springframework.boot.loader.launch.JarLauncher"]