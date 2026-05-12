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

FROM eclipse-temurin:25-jre AS local-runtime
WORKDIR /app
COPY deploy/scripts/wait-for-it.sh /usr/local/bin/wait-for-it
RUN chmod +x /usr/local/bin/wait-for-it \
    && groupadd --system --gid 10001 polaris \
    && useradd --system --uid 10001 --gid polaris --home-dir /app --shell /usr/sbin/nologin polaris
COPY --from=build /workspace/gateway/target/gateway.jar /app/gateway.jar
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]

FROM gcr.io/distroless/java25-debian13:nonroot AS production
WORKDIR /app
COPY --from=build /workspace/gateway/target/gateway.jar /app/gateway.jar
EXPOSE 8080
USER 65532:65532
ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]
