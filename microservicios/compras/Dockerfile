# Stage 1: compilar con Maven
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# Stage 2: ejecutar en Tomcat 9
FROM tomcat:9.0-jdk11

RUN rm -rf /usr/local/tomcat/webapps/*

COPY --from=build /app/target/Compras.war /usr/local/tomcat/webapps/Compras.war

COPY setenv.sh /usr/local/tomcat/bin/setenv.sh
RUN chmod +x /usr/local/tomcat/bin/setenv.sh

ENV DB_HOST=localhost
ENV DB_USER=root
ENV DB_PASSWORD=password
ENV USUARIOS_URL=http://usuarios-svc:8080/Usuarios/rest/ws
ENV ARTICULOS_URL=http://articulos-svc:8080/Articulos/rest/ws

EXPOSE 8080
CMD ["catalina.sh", "run"]
