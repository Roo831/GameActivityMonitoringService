FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
# Исправлено: Gradle кладет jar в build/libs/
COPY build/libs/*.jar app.jar
RUN mkdir -p /app/logs
ENV JAVA_OPTS="-Xms128m -Xmx256m"
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]