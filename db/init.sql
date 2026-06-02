-- WebHardMon — esquema MySQL local (alineado con el repo de la app WebHardMon).
-- BD de la aplicación: empresa, administrador (panel), usuario (ordenador) y
-- licencia (API key del agente). Se monta en /docker-entrypoint-initdb.d/ del
-- contenedor MySQL para las pruebas locales del flujo NiFi.

CREATE DATABASE IF NOT EXISTS telemetriadb
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE telemetriadb;

-- ─── empresa ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS empresa (
    id     BIGINT       NOT NULL AUTO_INCREMENT,
    nombre VARCHAR(255) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─── administrador (acceso al panel web) ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS administrador (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(255) NOT NULL,
    password   VARCHAR(255) NOT NULL,
    empresa_id BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_administrador_username (username),
    CONSTRAINT fk_administrador_empresa FOREIGN KEY (empresa_id) REFERENCES empresa (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─── usuario (empleado con un ordenador; nombre_ordenador == `nombre` en Cassandra) ──
CREATE TABLE IF NOT EXISTS usuario (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    nombre           VARCHAR(100) NOT NULL,
    nombre_ordenador VARCHAR(80)  NOT NULL,
    empresa_id       BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_usuario_empresa_ordenador (empresa_id, nombre_ordenador),
    CONSTRAINT fk_usuario_empresa FOREIGN KEY (empresa_id) REFERENCES empresa (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─── licencia (API key del agente, 1-1 con usuario) ──────────────────────────
CREATE TABLE IF NOT EXISTS licencia (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    codigo         VARCHAR(255) NOT NULL,
    activa         TINYINT(1)   NOT NULL DEFAULT 1,
    fecha_creacion DATETIME     NOT NULL,
    usuario_id     BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_licencia_codigo (codigo),
    UNIQUE KEY uk_licencia_usuario (usuario_id),
    CONSTRAINT fk_licencia_usuario FOREIGN KEY (usuario_id) REFERENCES usuario (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ─── vista de lookup para NiFi ────────────────────────────────────────────────
-- NiFi consulta esta vista con el `codigo`: valida (activa=1) y obtiene en la
-- misma consulta empresa_id + nombre para enriquecer el Avro. Resuelve el JOIN
-- licencia→usuario para que el lookup de NiFi use una sola clave.
CREATE OR REPLACE VIEW licencia_lookup AS
SELECT l.codigo           AS codigo,
       l.activa           AS activa,
       u.empresa_id       AS empresa_id,
       u.nombre_ordenador AS nombre
FROM licencia l
JOIN usuario  u ON u.id = l.usuario_id;

-- ─── datos de prueba ──────────────────────────────────────────────────────────
INSERT IGNORE INTO empresa (id, nombre) VALUES (1, 'Acme Corp');
INSERT IGNORE INTO usuario (id, nombre, nombre_ordenador, empresa_id)
VALUES (1, 'Usuario Prueba', 'PC-TEST', 1),
       (2, 'Usuario Dos',    'PC-DOS',  1);
INSERT IGNORE INTO licencia (id, codigo, activa, fecha_creacion, usuario_id) VALUES
       (1, 'WHM-TEST-TEST-TEST-AABB', 1, NOW(), 1),   -- activa  -> aceptada
       (2, 'WHM-OFF-OFF-OFF-OFF00',   0, NOW(), 2);    -- inactiva -> rechazada (401)
