# 🛡️ Gateway de ingesta NiFi — validación + enriquecimiento contra MySQL

NiFi se sitúa **entre los agentes Go y Kafka**. Por cada mensaje, **consulta MySQL
directamente** (NO la API de Java): valida la licencia y, en la misma consulta,
obtiene `empresa_id` + `nombre` (nombre_ordenador). Con eso **enriquece** el registro
y lo publica en Kafka serializado a **Avro** vía el **Schema Registry**. El clúster
Java solo añade `stress_score` y escribe en `mediciones`.

```
[Agente Go] --POST JSON /telemetry (header X-License-Code = codigo)--> [NiFi]
   │  JOIN en telemetriadb:  SELECT activa, empresa_id, nombre  (licencia→usuario→empresa)
   │     · 0 filas / activa=0 → HTTP 401 (rechazo)
   │     · 1 fila            → enriquece el registro con empresa_id + nombre
   ▼
 PublishKafkaRecord (JSON→Avro vía Schema Registry) → topic "telemetry"
   ▼
 [Clúster Java]  lee empresa_id+nombre del Avro → stress_score → Cassandra mediciones
```

> **Decisión de diseño:** la ingesta **no toca la API de Java** (`/api/agente/validar`
> sigue existiendo solo para el panel). NiFi resuelve licencia y enriquecimiento con
> **una sola consulta a MySQL**, así el clúster Java no necesita tocar MySQL.
> El agente emite **JSON** (no conoce su `empresa_id`); NiFi hace la serialización Avro.

---

## 1. Preparar MySQL: vista de lookup (una vez)

El modelo es normalizado (`licencia` → `usuario` → `empresa`). Para que el lookup de
NiFi resuelva el JOIN con una sola clave (`codigo`), se crea una **vista** en `telemetriadb`:

```sql
CREATE OR REPLACE VIEW licencia_lookup AS
SELECT l.codigo            AS codigo,
       l.activa            AS activa,
       u.empresa_id        AS empresa_id,
       u.nombre_ordenador  AS nombre
FROM licencia l
JOIN usuario  u ON u.id = l.usuario_id;
```

(La revocación sigue siendo `UPDATE licencia SET activa = 0 WHERE codigo = '...'`.)

---

## 2. Levantar la infraestructura

La ingesta (NiFi + MySQL) está en `docker-compose.ingest.yml` y se fusiona con el
compose "core" (que aporta `kafka` y `schema-registry`).

```bash
# Coloca antes el driver MySQL en nifi/drivers/ (ver nifi/drivers/README.md)
docker compose -f docker-compose.yml -f docker-compose.ingest.yml up -d
```
NiFi: consola en **https://localhost:8443/nifi**.

---

## 3. Controller Services

| Servicio | Config |
|---|---|
| `DBCPConnectionPool` | URL `jdbc:mysql://mysql:3306/telemetriadb`, driver `com.mysql.cj.jdbc.Driver`, jar `/opt/nifi/drivers/mysql-connector-j-8.4.0.jar`, user/pass `root`/`root` |
| `DatabaseRecordLookupService` | Connection Pool = el de arriba · **Table Name** `licencia_lookup` · **Lookup Key Column** `codigo` · **Value Columns** `activa,empresa_id,nombre` |
| `JsonTreeReader` | lee el JSON del agente |
| `AvroRecordSetWriter` | **Schema Write Strategy**: Confluent Schema Registry · esquema `telemetry` (= `schemas/telemetry.avsc`) |
| `ConfluentSchemaRegistry` | URL `http://schema-registry:8081` |
| `StandardHttpContextMap` | para el par request/response |

---

## 4. Flujo (procesadores encadenados)

1. **`HandleHttpRequest`** — Listening Port `8081`, Allowed Paths `/telemetry`, HTTP Context Map.
   El cuerpo es el JSON de métricas; `codigo` llega en el header `X-License-Code`.

2. **`UpdateRecord`** (o `JoltTransformRecord`) — mete el `codigo` del header dentro del
   registro (`/codigo = ${http.headers.x-license-code}`) para poder hacer el lookup por campo.

3. **`LookupRecord`** — Reader `JsonTreeReader`, Writer `JsonRecordSetWriter`,
   Lookup Service `DatabaseRecordLookupService`.
   - Key: `/codigo` → añade `/activa`, `/empresa_id`, `/nombre` al registro.
   - Relación **`unmatched`** (código inexistente) → **rechazo** (paso 6, 401).
   - Relación **`matched`** → paso 4.

4. **`RouteOnAttribute`/`QueryRecord`** — válido si `activa = 1` (y, opcional, que el
   `nombre` del registro coincida con el de la licencia).
   - válido → paso 5 · no válido → rechazo (paso 6, 401).

5. **`PublishKafkaRecord`** — Reader `JsonRecordSetWriter`→ **Writer `AvroRecordSetWriter`**
   (Confluent SR, esquema `telemetry`). Brokers `kafka:9092`, Topic `telemetry`.
   Solo se escriben los campos del esquema `telemetry.avsc` (empresa_id, nombre, ts,
   cpu_percent, …); el `codigo` extra se descarta. → `success` → `HandleHttpResponse` 200.

6. **`HandleHttpResponse`** (rechazo) — HTTP Status Code **401**.

> El `stress_score` NO lo pone NiFi: lo calcula el clúster Java y lo añade al escribir en `mediciones`.

---

## 5. Ejecutar el agente Go

El agente envía **JSON** + el header de licencia (no conoce su `empresa_id`):
```bash
cd AgenteGo-main
go run . -mode=publisher -nifi-url="http://localhost:8081/telemetry" \
         -codigo="WHM-TEST-TEST-TEST-AABB" -portatil="PC-TEST"
```
> ⚠️ **Cambio pendiente en el agente:** hoy serializa a Avro; con este diseño debe
> enviar **JSON** (NiFi se encarga del Avro). Es un cambio pequeño (`json.Marshal` en
> vez de `avro.Marshal`). Ver nota al final.

---

## 6. Notas

- **HTTPS** en producción: `StandardRestrictedSSLContextService` en `HandleHttpRequest`
  para que el `codigo` no viaje en claro (el agente cambia `-nifi-url` a `https://...`).
- **Cluster NiFi (HA)**: fuera de alcance del PBL (Kafka sí es HA; NiFi es nodo único).
- **Driver JDBC**: coloca el `.jar` en `nifi/drivers/` (ver su README).
- **El clúster Java no cambia**: ya lee `empresa_id`+`nombre` del Avro y escribe `mediciones`.
