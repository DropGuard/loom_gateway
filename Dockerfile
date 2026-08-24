# --- Stage 1: Build ---
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /build

COPY pom.xml settings.xml* ./
COPY src ./src

RUN mvn -B -s settings.xml clean package -DskipTests

# --- Stage 2: Runtime ---
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S gateway && adduser -S gateway -G gateway

WORKDIR /app

COPY --from=builder --chown=gateway:gateway /build/target/quarkus-app/lib/ /app/lib/
COPY --from=builder --chown=gateway:gateway /build/target/quarkus-app/app/ /app/app/
COPY --from=builder --chown=gateway:gateway /build/target/quarkus-app/quarkus/ /app/quarkus/
COPY --from=builder --chown=gateway:gateway /build/target/quarkus-app/quarkus-run.jar /app/quarkus-run.jar

USER gateway

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx256m -XX:+AlwaysPreTouch -XX:+UseG1GC -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar quarkus-run.jar"]

