FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Cache dependency download first for faster rebuilds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY src/ src/
COPY application.yml ./
RUN ./mvnw -q clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /workspace/target/demo-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build /workspace/application.yml ./application.yml
COPY --from=build /workspace/data ./data

EXPOSE 8080

CMD ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar /app/app.jar"]
