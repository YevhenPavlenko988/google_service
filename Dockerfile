FROM maven:3.9-eclipse-temurin-21-alpine AS build

# Build and install shared library first
WORKDIR /common
COPY common/pom.xml .
COPY common/src ./src
RUN mvn install -q

# Build google_service (common is now in local .m2)
WORKDIR /app
COPY google_service/pom.xml .
RUN mvn dependency:go-offline -q
COPY google_service/src ./src
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
