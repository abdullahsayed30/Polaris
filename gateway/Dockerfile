FROM maven:3.9.15-eclipse-temurin-25 AS build
WORKDIR /workspace

COPY pom.xml .
COPY proto-contracts/pom.xml proto-contracts/pom.xml
COPY shared/pom.xml shared/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY gateway/src gateway/src

RUN mvn -pl gateway -am -DskipTests package

FROM gcr.io/distroless/java25-debian13:nonroot
WORKDIR /app
COPY --from=build /workspace/gateway/target/gateway.jar /app/gateway.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
