FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ledgerline && adduser -S ledgerline -G ledgerline
WORKDIR /app
COPY --from=build /build/target/ledgerline.jar /app/ledgerline.jar
USER ledgerline
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-Dfile.encoding=UTF-8", "-jar", "/app/ledgerline.jar"]
