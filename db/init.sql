-- WebHardMon — esquema de aplicación
-- Base de datos: empresas, administradores del panel y licencias (API keys por portátil).
--
-- Este fichero se monta en /docker-entrypoint-initdb.d/ del contenedor MySQL
-- y se ejecuta automáticamente al crear la base de datos por primera vez.

CREATE DATABASE IF NOT EXISTS webhardmon
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

USE webhardmon;

-- ─── empresa ────────────────────────────────────────────────────────────────
-- Tenant raíz. Cada empresa agrupa sus admins y sus licencias.

CREATE TABLE empresa (
    id     BIGINT       NOT NULL AUTO_INCREMENT,
    nombre VARCHAR(255),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ─── administrador ──────────────────────────────────────────────────────────
-- Usuarios del panel web. La contraseña se almacena como hash bcrypt
-- (la capa de aplicación nunca guarda el texto en claro).

CREATE TABLE administrador (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    username   VARCHAR(255) NOT NULL,
    password   VARCHAR(255) NOT NULL,
    empresa_id BIGINT       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_username (username),
    CONSTRAINT fk_admin_empresa
        FOREIGN KEY (empresa_id) REFERENCES empresa (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ─── licencia ───────────────────────────────────────────────────────────────
-- API key del collector. Una licencia = un portátil autorizado para una empresa.
-- El collector envía (codigo, portatil); el backend verifica activa = 1.
-- La unicidad (empresa_id, portatil) impide que un mismo portátil tenga
-- dos licencias activas bajo la misma empresa.

CREATE TABLE licencia (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    codigo         VARCHAR(255) NOT NULL,
    activa         TINYINT(1)   NOT NULL DEFAULT 1,
    empresa_id     BIGINT       NOT NULL,
    portatil       VARCHAR(80)  NOT NULL,
    fecha_creacion DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_licencia_codigo (codigo),
    UNIQUE KEY uk_licencia_empresa_portatil (empresa_id, portatil),
    CONSTRAINT fk_licencia_empresa
        FOREIGN KEY (empresa_id) REFERENCES empresa (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ─── Datos de demostración (ELIMINAR en producción) ──────────────────────────
-- Permiten probar la verificación de NiFi sin pasar por el panel web:
--   DEMO-KEY-0001 -> activa   => NiFi acepta y publica en Kafka
--   DEMO-KEY-0002 -> inactiva => NiFi rechaza con HTTP 401

INSERT INTO empresa (id, nombre) VALUES (1, 'HardMon Demo');

INSERT INTO licencia (codigo, activa, empresa_id, portatil, fecha_creacion) VALUES
    ('DEMO-KEY-0001', 1, 1, 'portatil-01', NOW(6)),
    ('DEMO-KEY-0002', 0, 1, 'portatil-02', NOW(6));
