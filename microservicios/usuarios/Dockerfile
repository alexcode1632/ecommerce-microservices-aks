# Stage 1: compilar con Maven
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

# Stage 2: ejecutar en Tomcat 9
FROM tomcat:9.0-jdk11

# Eliminar aplicaciones por defecto
RUN rm -rf /usr/local/tomcat/webapps/*

# Copiar el WAR generado
COPY --from=build /app/target/Usuarios.war /usr/local/tomcat/webapps/Usuarios.war

# setenv.sh: exporta env vars de K8s como propiedades JVM para context.xml
COPY setenv.sh /usr/local/tomcat/bin/setenv.sh
RUN chmod +x /usr/local/tomcat/bin/setenv.sh

# Variables de entorno para la conexión a MySQL (sobreescribir en K8s)
ENV DB_HOST=localhost
ENV DB_USER=root
ENV DB_PASSWORD=password

EXPOSE 8080
CMD ["catalina.sh", "run"]
