FROM maven:3.9.15-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml .
COPY proto-contracts/pom.xml proto-contracts/pom.xml
COPY shared/pom.xml shared/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY shared/src shared/src
COPY notification-service/src notification-service/src

RUN mvn -pl notification-service -am -DskipTests package

FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=build /workspace/notification-service/target/notification-service.jar /app/notification-service.jar
ENTRYPOINT ["java", "-jar", "/app/notification-service.jar"]
