FROM maven:3.9.15-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml .
COPY shared/pom.xml shared/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY proto proto
COPY shared/src shared/src
COPY order-service/src order-service/src

RUN mvn -pl order-service -am -DskipTests package

FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=build /workspace/order-service/target/order-service.jar /app/order-service.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/order-service.jar"]
