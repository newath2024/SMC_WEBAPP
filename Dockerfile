FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace

# Cache dependency download first for faster rebuilds.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -q clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /workspace/target/tradejournal-0.0.1-SNAPSHOT.jar app.jar

RUN mkdir -p /app/data/uploads

ENV PORT=8081

EXPOSE 8081

VOLUME ["/app/data"]

CMD ["sh", "-c", "java -Dserver.port=${PORT:-8081} -jar /app/app.jar"]
