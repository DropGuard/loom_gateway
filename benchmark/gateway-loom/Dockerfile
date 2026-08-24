# Runtime image. The app is built on the host by run.py (which `mvn install`s
# the java-gateway library into the local repo first, so this project can
# resolve it), then we just copy the produced quarkus-app here.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/quarkus-app/lib/ /app/lib/
COPY target/quarkus-app/app/ /app/app/
COPY target/quarkus-app/quarkus/ /app/quarkus/
COPY target/quarkus-app/quarkus-run.jar /app/quarkus-run.jar
EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx256m -XX:+AlwaysPreTouch -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/quarkus-run.jar"]
