FROM maven:3.9.12-eclipse-temurin-17-alpine AS backend-build

WORKDIR /workspace/backend

COPY backend/ ./
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S duma && adduser -S -G duma duma

WORKDIR /opt/duma

COPY --from=backend-build /workspace/backend/target/*.jar app.jar

USER duma

EXPOSE 8082

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/opt/duma/app.jar"]
