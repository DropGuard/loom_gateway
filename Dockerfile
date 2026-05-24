# Build stage
FROM maven:3.9.9-eclipse-temurin-25-alpine AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -q

# Runtime stage
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S gateway && adduser -S gateway -G gateway
WORKDIR /app
COPY --from=build /build/target/quarkus-app/lib/ lib/
COPY --from=build /build/target/quarkus-app/*.jar app.jar
COPY --from=build /build/target/quarkus-app/quarkus/ quarkus/

RUN chown -R gateway:gateway /app
USER gateway

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseZGC -Xms64m -Xmx256m -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
