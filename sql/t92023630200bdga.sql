-- Base de datos: Gestión de artículos
-- Microservicio: articulos
-- Nomenclatura boleta 2023630200
-- Nota: la tabla stock NO incluye cantidad (RNF-10.2.1)

CREATE DATABASE IF NOT EXISTS t92023630200bdga
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE t92023630200bdga;

CREATE TABLE IF NOT EXISTS stock (
    id_articulo INT            NOT NULL AUTO_INCREMENT,
    nombre      VARCHAR(255)   NOT NULL,
    descripcion VARCHAR(500),
    precio      DECIMAL(10,2)  NOT NULL,
    PRIMARY KEY (id_articulo)
);

CREATE TABLE IF NOT EXISTS fotos_articulos (
    id_foto     INT      NOT NULL AUTO_INCREMENT,
    fotografia  LONGBLOB NOT NULL,
    id_articulo INT      NOT NULL,
    PRIMARY KEY (id_foto),
    CONSTRAINT fk_fotos_articulos_stock
        FOREIGN KEY (id_articulo)
        REFERENCES stock(id_articulo)
        ON DELETE CASCADE
        ON UPDATE CASCADE
);
