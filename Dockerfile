FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S gateway && adduser -S gateway -G gateway
WORKDIR /app
COPY target/quarkus-app/ ./

RUN chown -R gateway:gateway /app
USER gateway

EXPOSE 8080

ENV JAVA_OPTS="-Xms384m -Xmx384m -XX:+AlwaysPreTouch -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar quarkus-run.jar"]
