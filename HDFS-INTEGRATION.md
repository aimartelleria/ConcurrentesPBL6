# Integración Java Cluster → HDFS (Parquet)

## Flujo de datos

```
Agente Go → NiFi → Kafka (topic: telemetry, Avro + Schema Registry)
  → Client.java (Java 21 Virtual Threads)
      → StressTask por RMI (round-robin + failover entre ClusterNodes)
      → Cassandra webhardmon.mediciones  (hot path / streaming)
      → HDFS Parquet                     (batch path)
```

El hot path (Cassandra) y el batch path (HDFS) son independientes: un fallo de HDFS
**no** interrumpe el flujo (se captura + log; Cassandra sigue y el offset se confirma).

## Componentes

### `client/pom.xml`
- `hadoop-common` + `hadoop-hdfs-client` 3.3.6 (excluyen avro/log4j/reload4j para evitar conflictos).
- `parquet-avro` 1.14.0. `avro` fijado a 1.11.3 (lo comparten kafka-avro-serializer y parquet-avro).
- Shade: `ServicesResourceTransformer` (SPI de FileSystem en el fat-JAR) + exclusión de `.SF/.DSA/.RSA`.

### `client/src/main/java/cluster/HdfsParquetWriter.java`
- Esquema Parquet `TelemetryEnriched`: todos los campos del Avro + `stress_score`.
- Particionado Hive-style: `/data/telemetry/year=YYYY/month=MM/day=DD/part-<uuid>-<ts>.parquet`.
- Compresión SNAPPY, row group 128 MB.
- Thread-safe (lock global, compatible con Virtual Threads); un writer por día, lazy, cerrado en shutdown hook.

### `client/src/main/java/cluster/Client.java`
- Variable de entorno `HDFS_URI` (vacío = HDFS desactivado).
- `processRecord` recibe el `HdfsParquetWriter`; tras escribir en Cassandra llama a `hdfs.write(...)`
  con todos los campos + `stress_score` (best-effort). Log: `[OK] ... -> Cassandra + HDFS`.

## Variable de entorno

| Variable | Default | Descripción |
|---|---|---|
| `HDFS_URI` | *(vacío)* | URI del NameNode (p.ej. `hdfs://<namenode-wireguard>:9000`). Vacío = HDFS desactivado |

## Infraestructura HDFS (nube local)

| Componente | Valor |
|---|---|
| NameNode | `hdfs://<namenode>:9000` (LXC Proxmox, nube local) |
| Replicación | Factor 2 · Block 128 MB |
| Autenticación | Simple (sin Kerberos), `dfs.permissions.enabled=false` |
| Acceso desde GCP-A | vía WireGuard (malla inter-nube) |
| Directorio base | `/data/telemetry/` |

## Verificación end-to-end

```bash
# Ficheros Parquet en HDFS
docker exec webhardmon-hdfs-namenode hdfs dfs -ls -R /data/telemetry/
```

## Puntos de atención
- **`procesador`** ahora sí se persiste en HDFS (no en Cassandra `mediciones`, que no tiene la columna).
- **Rotación de writers**: el writer de un día se abre lazy y se cierra en shutdown. En ejecuciones de
  varios días, los writers de días anteriores quedan abiertos hasta reinicio (añadir cierre por cambio de día si hace falta).
- **Dependencia de WireGuard**: si HDFS no es alcanzable, el write falla silenciosamente (Cassandra no se ve afectada) y se reintenta en el siguiente mensaje.
