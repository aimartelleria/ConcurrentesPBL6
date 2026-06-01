# 📦 Contrato de despliegue — Servicio Java StressScore

Entregable para integrar en `webhardmon-infra`. Son **dos imágenes** que forman el
componente "Java Cluster + RMI StressScore" de GCP-A:

| Imagen | Rol | Réplicas |
|---|---|---|
| `rmi-server` | Nodo del clúster RMI (calcula StressScore). Clúster P2P con gossip. | 3 (1 por VM) |
| `rmi-client` | Puente: consume Kafka (Avro/SR) → StressScore por RMI → escribe Cassandra. | 1 |

## Qué hace (flujo)
```
Kafka topic "telemetry" (Avro + Schema Registry)
   → rmi-client deserializa (KafkaAvroDeserializer)
   → StressScore en el clúster rmi-server (RMI, round-robin + failover)
   → INSERT en Cassandra webhardmon.mediciones
   (ack manual + dead-letter topic "telemetry.DLT")
```

## Dependencias que aporta TU infra (no van en estas imágenes)
- **Kafka** (brokers alcanzables por la malla Tailscale).
- **Schema Registry** (Confluent o Apicurio con API Confluent).
- **Cassandra** con el keyspace/tablas ya creados → aplica `db/schema-cassandra.cql`.

## Contrato de datos (Avro en Kafka)
El productor (NiFi) debe publicar registros que cumplan **`schemas/telemetry.avsc`**
(registrado en el Schema Registry para el subject `telemetry-value`). Campos:
`empresa_id(long), nombre(string), ts(timestamp-millis), cpu_percent, ram_percent,
disco_percent, temperatura?(double), bateria_percent?(double), ram(string),
almacenamiento(string), procesador?(string)`.

> `empresa_id` y `nombre` (= `nombre_ordenador`) los conoce NiFi tras validar la
> licencia → debe enriquecer el Avro con ellos antes de Kafka. El servicio Java
> añade `stress_score` y escribe la fila en `mediciones`.

## Variables de entorno

### `rmi-server` (cada nodo)
| Var | Ejemplo | Notas |
|---|---|---|
| `NODE_ID` | `node-1` | identificador único |
| `HOST` | `node-1.tailnet` | **nombre Tailscale resoluble** |
| `PORT` | `6100` | puerto del registry RMI |
| `SEEDS` | `node-1.tailnet:6100 node-2.tailnet:6100 node-3.tailnet:6100` | bootstrap gossip |
| `JVM_OPTS` | `-Djava.rmi.server.hostname=node-1.tailnet` | **imprescindible**: RMI publica este host en el stub |

### `rmi-client`
| Var | Por defecto | Notas |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | brokers |
| `SCHEMA_REGISTRY_URL` | `http://schema-registry:8085` | |
| `TELEMETRY_TOPIC` | `telemetry` | el DLT es `<topic>.DLT` |
| `CONSUMER_GROUP` | `telemetry_java_consumers` | |
| `CASSANDRA_CONTACT_POINTS` | `cassandra:9042` | coma-separado `host:port` |
| `CASSANDRA_LOCAL_DC` | `datacenter1` | datacenter local de Cassandra |
| `CASSANDRA_KEYSPACE` | `webhardmon` | |
| `SEEDS` (args) | — | seeds RMI, p.ej. `node-1.tailnet:6100 node-2.tailnet:6100` |
| `JVM_OPTS` | — | TLS/RMI opcional |

> ⚠️ **RMI sobre Tailscale:** todos los `HOST`/`SEEDS`/`java.rmi.server.hostname`
> deben ser nombres/IPs **del tailnet**, no de Docker, o los stubs RMI apuntarán a
> direcciones inalcanzables entre VMs.

## Construir y publicar al registry
```bash
# Servidor
docker build -t <REGISTRY>/rmi-server:latest -f server/Dockerfile .
docker push <REGISTRY>/rmi-server:latest
# Cliente
docker build -t <REGISTRY>/rmi-client:latest -f client/Dockerfile .
docker push <REGISTRY>/rmi-client:latest
```
`<REGISTRY>` = Harbor (local) o Artifact Registry (GCP-A).

## Probar en local (end-to-end, sin tu infra)
El `docker-compose.yml` levanta Kafka + Schema Registry + Cassandra (con esquema
auto-cargado) + 3 nodos + cliente:
```bash
docker compose up -d --build
# (opcional) ingesta NiFi + MySQL:
docker compose -f docker-compose.yml -f docker-compose.ingest.yml up -d
```

## Fuera de alcance (de momento)
- Tabla `ordenadores` (inventario) y `empresas`: el cliente solo escribe `mediciones`.
  Se puede añadir el upsert a `ordenadores` (procesador, ultima_vez, codigo_licencia)
  si lo necesitáis.
- Rama batch a HDFS/Parquet (Nivel 3).
