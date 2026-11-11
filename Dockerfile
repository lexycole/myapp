# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -U -B \
    -Dmaven.wagon.http.retryHandler.count=3 \
    -Dmaven.wagon.rto=120000 \
    dependency:go-offline

COPY src ./src
RUN mvn clean package -DskipTests && mv target/*.jar target/febaalert.jar

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /app/target/febaalert.jar app.jar
COPY src/main/resources/keystore/keystore.p12 /app/keystore/keystore.p12

EXPOSE 8131
ENTRYPOINT ["java", "-jar", "app.jar"]

