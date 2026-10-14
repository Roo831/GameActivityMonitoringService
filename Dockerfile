FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/*-SNAPSHOT.jar app.jar
ENV JAVA_OPTS="-Xms128m -Xmx256m"
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]