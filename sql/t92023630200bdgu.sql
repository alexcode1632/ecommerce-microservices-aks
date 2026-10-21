-- Base de datos: Gestión de usuarios
-- Microservicio: usuarios
-- Nomenclatura boleta 2023630200

CREATE DATABASE IF NOT EXISTS t92023630200bdgu
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE t92023630200bdgu;

CREATE TABLE IF NOT EXISTS usuarios (
    id_usuario       INT           NOT NULL AUTO_INCREMENT,
    email            VARCHAR(255)  NOT NULL UNIQUE,
    password         VARCHAR(64)   NOT NULL,
    nombre           VARCHAR(100)  NOT NULL,
    apellido_paterno VARCHAR(100)  NOT NULL,
    apellido_materno VARCHAR(100),
    fecha_nacimiento TIMESTAMP     NOT NULL,
    telefono         BIGINT,
    genero           CHAR(1),
    token            VARCHAR(20),
    PRIMARY KEY (id_usuario)
);

CREATE TABLE IF NOT EXISTS fotos_usuarios (
    id_foto    INT      NOT NULL AUTO_INCREMENT,
    foto       LONGBLOB NOT NULL,
    id_usuario INT      NOT NULL,
    PRIMARY KEY (id_foto),
    CONSTRAINT fk_fotos_usuarios
        FOREIGN KEY (id_usuario)
        REFERENCES usuarios(id_usuario)
        ON DELETE CASCADE
        ON UPDATE CASCADE
);
