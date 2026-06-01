CREATE KEYSPACE IF NOT EXISTS webhardmon
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

-- Espejo de la tabla empresa de MySQL (enlaza ambas BBs por empresa_id)
CREATE TABLE IF NOT EXISTS webhardmon.empresas (
    empresa_id bigint,
    nombre     text,
    PRIMARY KEY (empresa_id)
);

-- Inventario de ordenadores.
-- codigo_licencia permite trazar directamente que licencia (MySQL) envio
-- los datos de este ordenador y verifica el agente Go que registro la maquina.
CREATE TABLE IF NOT EXISTS webhardmon.ordenadores (
    empresa_id      bigint,
    nombre          text,
    procesador      text,
    ram             text,
    almacenamiento  text,
    ultima_vez      timestamp,
    codigo_licencia text,
    PRIMARY KEY ((empresa_id), nombre)
);

-- Serie temporal de mediciones de telemetria.
-- ram y almacenamiento se repiten aqui (desnormalizados) para que
-- Grafana pueda filtrar los rankings por hardware sin JOINs.
CREATE TABLE IF NOT EXISTS webhardmon.mediciones (
    empresa_id      bigint,
    nombre          text,
    ts              timestamp,
    cpu_percent     double,
    ram_percent     double,
    disco_percent   double,
    temperatura     double,
    bateria_percent double,
    stress_score    double,
    ram             text,
    almacenamiento  text,
    PRIMARY KEY ((empresa_id), ts, nombre)
) WITH CLUSTERING ORDER BY (ts DESC, nombre ASC);
