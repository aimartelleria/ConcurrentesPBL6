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
- **Kafka** (brokers alcanzables por la malla WireGuard).
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
| `HOST` | `node-1` | **nombre WireGuard resoluble** |
| `PORT` | `6100` | puerto del registry RMI |
| `SEEDS` | `node-1:6100 node-2:6100 node-3:6100` | bootstrap gossip |
| `JVM_OPTS` | `-Djava.rmi.server.hostname=node-1` | **imprescindible**: RMI publica este host en el stub |

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
| `SEEDS` (args) | — | seeds RMI, p.ej. `node-1:6100 node-2:6100` |
| `JVM_OPTS` | — | TLS/RMI opcional |

> ⚠️ **RMI sobre WireGuard:** todos los `HOST`/`SEEDS`/`java.rmi.server.hostname`
> deben ser nombres/IPs **del WireGuard**, no de Docker, o los stubs RMI apuntarán a
> direcciones inalcanzables entre VMs.

## Qué direcciones cambian al desplegar (¿antes o después?)

**La imagen se construye UNA vez SIN direcciones.** Todo es configurable por env;
los valores se inyectan al **desplegar** (después de provisionar la infra, cuando
ya existen los nombres del WireGuard). Cambiar una dirección = cambiar la env y
reiniciar, **nunca** reconstruir la imagen. Plantilla de runtime: `.env.cloud.example`.

| Dirección | Local (por defecto) | Cloud (cambiar a) | Cuándo |
|---|---|---|---|
| `java.rmi.server.hostname` / `HOST` / `SEEDS` | `node-1`, `localhost` | nombre **WireGuard** de cada VM (`node-01`…) | al desplegar |
| `KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` | Kafka del compañero (GCP-A, WireGuard) | al desplegar |
| `SCHEMA_REGISTRY_URL` | `http://schema-registry:8085` | SR del compañero (WireGuard) | al desplegar |
| `CASSANDRA_CONTACT_POINTS` | `cassandra:9042` | Cassandra (GCP-A, WireGuard) | al desplegar |
| `CASSANDRA_LOCAL_DC` | `datacenter1` | nombre real del DC (confirmar) | al desplegar |
| `-nifi-url` (agente Go) | `http://localhost:8081/telemetry` | túnel Cloudflare `https://ingest.<zona>/telemetry` | al desplegar |
| imágenes (`PROJECT_ID`/`REGION`) | — | tag del registry (Harbor / Artifact Registry) | **antes** (build/push) |

**NO cambian:** los puertos (RMI 6100-6102, Kafka 9092, SR 8085, Cassandra 9042,
NiFi 8081/8443, MySQL 3306) ni los nombres lógicos (topic `telemetry`/`telemetry.DLT`,
keyspace `webhardmon`, grupo de consumidor).

**Orden:** ① (antes) build + push de imágenes → ② provisión de infra (OpenTofu/
Ansible) → ③ (después) inyectar las env del WireGuard y arrancar los contenedores.

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

## 🚀 Paso a paso para subirlo a cloud

> Regla de oro: la imagen se construye **una vez sin direcciones**; las direcciones
> del WireGuard se inyectan **al desplegar** (paso 4-5), no al construir.

**Prerrequisitos:** infra provisionada (VMs + WireGuard + Kafka + Schema Registry +
Cassandra + NiFi + túnel Cloudflare) y acceso al registry (Harbor / Artifact Registry).

**1. (Antes) Construir y publicar las imágenes**
```bash
docker build -t <REGISTRY>/rmi-server:latest -f server/Dockerfile . && docker push <REGISTRY>/rmi-server:latest
docker build -t <REGISTRY>/rmi-client:latest -f client/Dockerfile . && docker push <REGISTRY>/rmi-client:latest
```

**2. (Una vez) Crear el esquema en Cassandra**
```bash
cqlsh <cassandra-wg> -f db/schema-cassandra.cql   # keyspace webhardmon
```

**3. (Una vez) Registrar el esquema Avro** `schemas/telemetry.avsc` en el Schema
Registry para el subject `telemetry-value` (o dejar que NiFi lo registre al publicar).

**4. (Después) Desplegar los 3 nodos `rmi-server`** — cada uno en su VM, con SU nombre de WireGuard:
```bash
docker run -d --name rmi-node-1 \
  -e NODE_ID=node-01 -e HOST=node-01 -e PORT=6100 \
  -e SEEDS="node-01:6100 node-02:6100 node-03:6100" \
  -e JVM_OPTS="-Djava.rmi.server.hostname=node-01" \
  -p 6100:6100 <REGISTRY>/rmi-server:latest
# Repetir en node-02 y node-03 cambiando NODE_ID/HOST/hostname (PORT=6100 en cada VM).
```

**5. (Después) Desplegar el `rmi-client`** apuntando a la infra del compañero:
```bash
docker run -d --name rmi-client \
  -e KAFKA_BOOTSTRAP_SERVERS="<kafka-wg>:9092" \
  -e SCHEMA_REGISTRY_URL="http://<sr-wg>:8085" \
  -e CASSANDRA_CONTACT_POINTS="<cass-wg>:9042" \
  -e CASSANDRA_LOCAL_DC="<dc-real>" \
  -e CASSANDRA_KEYSPACE="webhardmon" \
  <REGISTRY>/rmi-client:latest \
  node-01:6100 node-02:6100 node-03:6100      # seeds RMI como argumentos
```
Plantilla completa de variables: `.env.cloud.example`.

**6. Conectar los agentes Go** (en los PCs de usuario, vía túnel Cloudflare):
```bash
./AgenteGo -mode=publisher -nifi-url="https://ingest.<zona>/telemetry" \
  -codigo="<licencia>" -portatil="<nombre-ordenador>"
```

**7. Verificar**
- Logs de `rmi-client`: `[OK] <equipo> stress=.. -> mediciones`.
- En Cassandra: `SELECT * FROM webhardmon.mediciones LIMIT 5;`
- Si el clúster RMI cae, los mensajes van a `telemetry.DLT` (no se pierden).

> Si el compañero despliega con Ansible, estos `docker run` equivalen a las tareas
> del playbook: lo esencial es inyectar **las mismas variables de entorno**.

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
