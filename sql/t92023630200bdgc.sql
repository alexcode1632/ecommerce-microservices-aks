-- Base de datos: Gestión de compras
-- Microservicio: compras
-- Nomenclatura boleta 2023630200
-- stock: solo id_articulo + cantidad (RNF-10.3.2)
-- carrito_compra: id_usuario, id_articulo, cantidad (RNF-10.3.1)

CREATE DATABASE IF NOT EXISTS t92023630200bdgc
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE t92023630200bdgc;

CREATE TABLE IF NOT EXISTS stock (
    id_articulo INT NOT NULL,
    cantidad    INT NOT NULL,
    UNIQUE KEY uk_stock_articulo (id_articulo)
);

CREATE TABLE IF NOT EXISTS carrito_compra (
    id_usuario  INT NOT NULL,
    id_articulo INT NOT NULL,
    cantidad    INT NOT NULL,
    UNIQUE KEY uk_carrito_usuario_articulo (id_usuario, id_articulo)
);
