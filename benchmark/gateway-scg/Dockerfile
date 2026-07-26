# Multi-stage build: compile the SCG benchmark app in a Maven image, then
# package it into a slim JRE runtime image.
#
# The Maven stage uses a settings.xml (mirror -> Aliyun) so it works behind
# networks where repo.maven.apache.org is blocked. If you have a corporate
# Nexus, drop its mirror into settings.xml instead.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml settings.xml ./
COPY src ./src
RUN mvn -s settings.xml -B -DskipTests package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S gateway && adduser -S gateway -G gateway
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
RUN chown -R gateway:gateway /app
USER gateway
EXPOSE 8080
ENV JAVA_OPTS="-Xms384m -Xmx384m -XX:+AlwaysPreTouch"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
